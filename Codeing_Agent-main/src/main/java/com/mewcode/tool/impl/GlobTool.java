// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.tool.impl;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GlobTool implements Tool {

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".venv", "node_modules", "__pycache__", ".tox", ".mypy_cache"
    );

    private static final String DESCRIPTION = """
            Find files matching a glob pattern, returning relative paths sorted by modification time (most recent first).

            Usage notes:
            - Supports patterns like "**/*.py", "src/**/*.ts", "*.go".
            - Search from "." or a specific path, never from "/".
            - Automatically skips .git, node_modules, __pycache__, and similar directories.
            - Use this instead of find or ls commands via Bash.""";

    @Override
    public String name() {
        return "Glob";
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
                                "pattern", Map.of("type", "string", "description", "Glob pattern to match (e.g. '**/*.py')"),
                                "path", Map.of("type", "string", "description", "Base directory to search from", "default", ".")
                        ),
                        "required", List.of("pattern")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String pattern = stringArg(args, "pattern", "");
        String basePath = stringArg(args, "path", ".");
        if (basePath.isEmpty()) {
            basePath = ".";
        }
        if (pattern.isEmpty()) {
            return ToolResult.error("Error: pattern is required");
        }

        Path root = Path.of(basePath);
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return ToolResult.error("Error: path not found: " + basePath);
        }

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        var matches = new ArrayList<String>();

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (SKIP_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path rel = root.relativize(file);
                    if (matcher.matches(file.getFileName()) || matcher.matches(rel)) {
                        matches.add(rel.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return ToolResult.error("Error: " + e.getMessage());
        }

        // 按修改时间倒序，最近修改的排前面
        matches.sort((a, b) -> {
            try {
                long ma = Files.getLastModifiedTime(root.resolve(a)).toMillis();
                long mb = Files.getLastModifiedTime(root.resolve(b)).toMillis();
                return Long.compare(mb, ma);
            } catch (IOException e) {
                return a.compareTo(b);
            }
        });
        if (matches.isEmpty()) {
            return ToolResult.success("No files matched the pattern.");
        }
        return ToolResult.success(String.join("\n", matches));
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }
}
