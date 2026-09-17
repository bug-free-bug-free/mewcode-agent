// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.hook;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class HookEngine {

    // ---- Event names ----

    public enum EventName {
        SESSION_START("session_start"),
        SESSION_END("session_end"),
        TURN_START("turn_start"),
        TURN_END("turn_end"),
        PRE_SEND("pre_send"),
        POST_RECEIVE("post_receive"),
        PRE_TOOL_USE("pre_tool_use"),
        POST_TOOL_USE("post_tool_use"),
        SHUTDOWN("shutdown");

        private final String value;

        EventName(String value) { this.value = value; }

        public String value() { return value; }
    }

    // ---- Action types ----

    public enum ActionType {
        COMMAND("command"),
        SCRIPT("script"),
        PROMPT("prompt");

        private final String value;

        ActionType(String value) { this.value = value; }

        public String value() { return value; }
    }

    // ---- Data records ----

    public record Action(ActionType type, String command, String message) {}

    public record Hook(String id, EventName event, String condition, Action action, boolean reject) {}

    public record HookContext(EventName event, String toolName, Map<String, Object> toolArgs,
                              String filePath, String message, String error) {}

    public record HookResult(String hookId, String output, boolean success, boolean reject) {}

    public record PreToolResult(boolean rejected, String message) {}

    // ---- Engine state ----

    private final List<Hook> hooks = new ArrayList<>();
    private final List<HookResult> notifications = new ArrayList<>();

    // ---- Hook registration ----

    public void addHook(Hook hook) {
        hooks.add(hook);
    }

    public void loadHooks(List<Hook> hookList) {
        hooks.clear();
        hooks.addAll(hookList);
    }

    // ---- Hook execution ----

    public List<HookResult> runHooks(HookContext ctx) {
        List<HookResult> results = new ArrayList<>();
        for (Hook h : hooks) {
            if (h.event() != ctx.event()) {
                continue;
            }
            if (h.condition() != null && !h.condition().isEmpty()
                    && !evaluateCondition(h.condition(), ctx)) {
                continue;
            }
            HookResult result = executeAction(h, ctx);
            results.add(result);
            notifications.add(result);
        }
        return results;
    }

    public PreToolResult runPreToolHooks(String toolName, Map<String, Object> args) {
        HookContext ctx = new HookContext(
                EventName.PRE_TOOL_USE, toolName, args, null, null, null);
        for (Hook h : hooks) {
            if (h.event() != EventName.PRE_TOOL_USE) {
                continue;
            }
            if (h.condition() != null && !h.condition().isEmpty()
                    && !evaluateCondition(h.condition(), ctx)) {
                continue;
            }
            if (h.reject()) {
                HookResult result = executeAction(h, ctx);
                return new PreToolResult(true, result.output());
            }
        }
        return new PreToolResult(false, "");
    }

    // ---- Notifications ----

    public List<HookResult> drainNotifications() {
        List<HookResult> result = List.copyOf(notifications);
        notifications.clear();
        return result;
    }

    // ---- Condition evaluation ----

    private boolean evaluateCondition(String condition, HookContext ctx) {
        String cond = condition.strip();

        // Equality: "tool==bash"
        if (cond.contains("==")) {
            String[] parts = cond.split("==", 2);
            String left = parts[0].strip();
            String right = stripQuotes(parts[1].strip());
            return resolveVar(left, ctx).equals(right);
        }

        // Regex match: "event=~session.*"
        if (cond.contains("=~")) {
            String[] parts = cond.split("=~", 2);
            String left = parts[0].strip();
            String pattern = stripQuotes(parts[1].strip());
            String val = resolveVar(left, ctx);
            try {
                return Pattern.matches(pattern, val);
            } catch (PatternSyntaxException e) {
                return false;
            }
        }

        // Unknown operator -- treat as truthy (same as Go)
        return true;
    }

    private String resolveVar(String name, HookContext ctx) {
        return switch (name) {
            case "tool"      -> ctx.toolName()  != null ? ctx.toolName()  : "";
            case "event"     -> ctx.event()     != null ? ctx.event().value() : "";
            case "file_path" -> ctx.filePath()  != null ? ctx.filePath()  : "";
            case "message"   -> ctx.message()   != null ? ctx.message()   : "";

            default -> {
                if (name.startsWith("args.") && ctx.toolArgs() != null) {
                    String key = name.substring("args.".length());
                    Object v = ctx.toolArgs().get(key);
                    yield v != null ? String.valueOf(v) : "";
                }
                yield "";
            }
        };
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last  = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"')
                    || (first == '\'' && last == '\'')
                    || (first == '/' && last == '/')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    // ---- Action execution ----

    private HookResult executeAction(Hook h, HookContext ctx) {
        return switch (h.action().type()) {
            case COMMAND -> executeCommand(h, ctx);
            case PROMPT  -> new HookResult(h.id(), h.action().message(), true, h.reject());
            default      -> new HookResult(h.id(),
                    "Unknown action type: " + h.action().type().value(), false, false);
        };
    }

    private HookResult executeCommand(Hook h, HookContext ctx) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", h.action().command());
            Map<String, String> env = pb.environment();
            env.put("MEWCODE_EVENT", ctx.event() != null ? ctx.event().value() : "");
            env.put("MEWCODE_TOOL",  ctx.toolName() != null ? ctx.toolName() : "");
            pb.redirectErrorStream(false);

            Process proc = pb.start();
            String stdout = new String(proc.getInputStream().readAllBytes()).strip();
            String stderr = new String(proc.getErrorStream().readAllBytes()).strip();
            int code = proc.waitFor();

            String output = stdout;
            if (!stderr.isEmpty()) {
                output = output.isEmpty() ? stderr : output + "\n" + stderr;
            }
            return new HookResult(h.id(), output, code == 0, h.reject());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new HookResult(h.id(), "Failed to execute hook: " + e.getMessage(), false, h.reject());
        }
    }
}
