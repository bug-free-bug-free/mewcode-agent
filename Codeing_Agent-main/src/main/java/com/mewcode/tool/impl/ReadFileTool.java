// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.tool.impl;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import com.mewcode.tool.FileStateCache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class ReadFileTool implements Tool {

    private FileStateCache fileStateCache;

    public void setFileStateCache(FileStateCache c) { this.fileStateCache = c; }

    private static final String DESCRIPTION = """
            Read a file and return its contents with line numbers.

            Usage notes:
            - The file_path parameter should be an absolute path when possible.
            - By default reads up to 2000 lines from the beginning of the file.
            - Use offset and limit to read specific parts of large files. Only read what you need.
            - Results are returned with line numbers (1-based) for easy reference.
            - This tool can only read files, not directories. Use Glob to list directory contents.
            - Do NOT re-read a file you just edited to verify — EditFile would have errored if the change failed.""";

    @Override
    public String name() {
        return "ReadFile";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "file_path", Map.of("type", "string", "description", "Absolute or relative path to the file to read"),
                                "offset", Map.of("type", "integer", "description", "Line offset to start reading from (0-based)", "default", 0),
                                "limit", Map.of("type", "integer", "description", "Maximum number of lines to read", "default", 2000)
                        ),
                        "required", List.of("file_path")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String filePath = stringArg(args, "file_path", "");
        if (filePath.isEmpty()) {
            return ToolResult.error("Error: file_path is required");
        }

        int offset = intArg(args, "offset", 0);
        int limit = intArg(args, "limit", 2000);

        Path path = Path.of(filePath);

        if (!Files.exists(path)) {
            return ToolResult.error("Error: file not found: " + filePath);
        }
        if (Files.isDirectory(path)) {
            return ToolResult.error("Error: not a file: " + filePath);
        }

        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            return ToolResult.error("Error reading file: " + e.getMessage());
        }

        String[] lines = content.split("\n", -1);

        if (offset >= lines.length) {
            return ToolResult.success("");
        }

        int end = offset + limit;
        if (end > lines.length) {
            end = lines.length;
        }

        // Record in file-state cache so EditFile/WriteFile know the file has been read
        if (fileStateCache != null) {
            try {
                long mtime = Files.getLastModifiedTime(path).toMillis();
                fileStateCache.record(path.toAbsolutePath().toString(), content, mtime);
            } catch (IOException ignored) {
                // best-effort: don't fail the read because of mtime lookup
            }
        }

        var sb = new StringBuilder();
        for (int i = offset; i < end; i++) {
            if (i > offset) {
                sb.append('\n');
            }
            sb.append(i + 1).append('\t').append(lines[i]);
        }

        return ToolResult.success(sb.toString());
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
