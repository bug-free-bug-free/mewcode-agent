// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.tool.impl;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class BashTool implements Tool {

    private static final int MAX_TIMEOUT = 600;

    // 这些命令的 exit code 1 不算错误（例如 grep 没匹配到、diff 文件有差异、test 条件为 false）
    // 只有 exit code >= 2 才视为真正的错误
    private static final Set<String> EXIT_ONE_OK_COMMANDS = Set.of(
            "grep", "egrep", "fgrep", "rg",  // exit 1 = 没有匹配
            "diff",                            // exit 1 = 文件有差异
            "find",                            // exit 1 = 部分成功
            "test", "["                        // exit 1 = 条件为 false
    );

    private static final String DESCRIPTION = """
            Execute a shell command and return stdout and stderr.

            IMPORTANT: Avoid using this tool to run cat, head, tail, sed, awk, or echo commands. \
            Instead use the dedicated ReadFile, EditFile, or WriteFile tools which provide a better experience.

            Usage notes:
            - The working directory persists between commands, but shell state does not.
            - Always quote file paths containing spaces with double quotes.
            - Try to maintain your current working directory using absolute paths; avoid cd unless the user explicitly requests it.
            - Optional timeout in seconds (max 600). Default is 120s.
            - When issuing multiple independent commands, make separate parallel tool calls instead of chaining with &&.
            - Use && to chain sequential dependent commands. Use ; only when you don't care if earlier commands fail.
            - DO NOT use newlines to separate commands.

            Git Safety Protocol:
            - NEVER run destructive git commands (push --force, reset --hard, checkout ., clean -f, branch -D) unless the user explicitly requests it.
            - NEVER skip hooks (--no-verify) unless the user explicitly requests it.
            - Prefer creating a new commit rather than amending an existing one.
            - Before running destructive operations, consider safer alternatives.

            Avoid unnecessary sleep commands. Do not retry failing commands in a sleep loop — diagnose the root cause instead.
            When using find, search from "." or a specific path, not "/" — scanning the full filesystem is too expensive.""";

    @Override
    public String name() {
        return "Bash";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.COMMAND;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "command", Map.of("type", "string", "description", "Shell command to execute"),
                                "timeout", Map.of("type", "integer", "description", "Timeout in seconds (max 600)", "default", 120)
                        ),
                        "required", List.of("command")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String command = stringArg(args, "command", "");
        if (command.isEmpty()) {
            return ToolResult.error("Error: command is required");
        }

        int timeout = intArg(args, "timeout", 120);
        if (timeout > MAX_TIMEOUT) {
            timeout = MAX_TIMEOUT;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // Read stdout and stderr concurrently to avoid blocking
            String stdout;
            String stderr;
            try (InputStream stdoutStream = process.getInputStream();
                 InputStream stderrStream = process.getErrorStream()) {
                byte[] stdoutBytes = stdoutStream.readAllBytes();
                stderr = new String(stderrStream.readAllBytes());
                stdout = new String(stdoutBytes);
            }

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("Error: command timed out after " + timeout + "s");
            }

            int exitCode = process.exitValue();

            var sb = new StringBuilder();
            sb.append("$ ").append(command).append('\n');
            if (!stdout.isEmpty()) {
                sb.append(stdout);
                if (!stdout.endsWith("\n")) {
                    sb.append('\n');
                }
            }
            if (!stderr.isEmpty()) {
                sb.append("STDERR: ").append(stderr);
                if (!stderr.endsWith("\n")) {
                    sb.append('\n');
                }
            }
            sb.append("(exit code ").append(exitCode).append(')');

            boolean isError = interpretExitCode(command, exitCode);
            return new ToolResult(sb.toString(), isError);

        } catch (IOException e) {
            return ToolResult.error("Error executing command: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Error: command interrupted");
        }
    }

    /**
     * 根据命令语义判断 exit code 是否代表错误。
     * 管道命令取最后一段（bash 默认返回最后一个命令的 exit code），
     * 对于 grep/diff/find/test 等命令，exit code 1 属于正常结果而非错误。
     */
    private boolean interpretExitCode(String command, int exitCode) {
        if (exitCode == 0) {
            return false;
        }

        String baseCmd = extractBaseCommand(command);
        if (EXIT_ONE_OK_COMMANDS.contains(baseCmd)) {
            // 这些命令只有 exit code >= 2 才算错误
            return exitCode >= 2;
        }

        // 默认：非零都算错误
        return true;
    }

    /**
     * 从完整命令字符串中提取基础命令名。
     * 处理管道（取最后一段）、路径前缀（取 basename）、env 前缀等。
     */
    private String extractBaseCommand(String command) {
        String cmd = command.strip();

        // 管道：取最后一段，因为 bash 的 exit code 由管道最后一个命令决定
        int pipeIdx = cmd.lastIndexOf('|');
        if (pipeIdx >= 0 && pipeIdx < cmd.length() - 1) {
            cmd = cmd.substring(pipeIdx + 1).strip();
        }

        // 跳过 env 变量赋值前缀（如 FOO=bar grep ...）
        while (cmd.contains("=") && !cmd.startsWith("=")) {
            int spaceIdx = cmd.indexOf(' ');
            int eqIdx = cmd.indexOf('=');
            if (eqIdx < spaceIdx || spaceIdx == -1) {
                // 这一段是环境变量赋值，跳过
                if (spaceIdx == -1) break;
                cmd = cmd.substring(spaceIdx + 1).strip();
            } else {
                break;
            }
        }

        // 取第一个 token（命令名本身）
        String[] parts = cmd.split("\\s+", 2);
        String token = parts[0];

        // 处理路径前缀，如 /usr/bin/grep → grep
        int slashIdx = token.lastIndexOf('/');
        if (slashIdx >= 0 && slashIdx < token.length() - 1) {
            token = token.substring(slashIdx + 1);
        }

        return token;
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }

    private static int intArg(Map<String, Object> args, String key, int def) {
        var v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        return def;
    }
}
