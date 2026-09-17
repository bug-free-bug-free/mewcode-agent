// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.tool.impl;

import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WriteFileTool implements Tool {

    private com.mewcode.filehistory.FileHistory fileHistory;
    private FileStateCache fileStateCache;

    public void setFileHistory(com.mewcode.filehistory.FileHistory fh) { this.fileHistory = fh; }
    public void setFileStateCache(FileStateCache c) { this.fileStateCache = c; }

    private static final String DESCRIPTION = """
            Write content to a file, creating parent directories if needed. Overwrites existing files.

            Usage notes:
            - If modifying an existing file, prefer EditFile over WriteFile — it only sends the diff.
            - Use this tool only to create new files or for complete rewrites.
            - You MUST read existing files with ReadFile before overwriting them.
            - NEVER create documentation files (*.md) or README files unless explicitly requested.""";

    @Override
    public String name() {
        return "WriteFile";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.WRITE;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "file_path", Map.of("type", "string", "description", "Path to the file to write"),
                                "content", Map.of("type", "string", "description", "Content to write to the file")
                        ),
                        "required", List.of("file_path", "content")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String filePath = stringArg(args, "file_path", "");
        String content = stringArg(args, "content", "");

        if (filePath.isEmpty()) {
            return ToolResult.error("Error: file_path is required");
        }

        if (fileHistory != null) fileHistory.trackEdit(filePath);

        Path path = Path.of(filePath);

        // Read-before-write enforcement — skip for new files
        if (fileStateCache != null && Files.exists(path)) {
            String absPath = path.toAbsolutePath().toString();
            String err = fileStateCache.validate(absPath);
            if (err != null) return ToolResult.error(err);
        }

        boolean posix = path.getFileSystem().supportedFileAttributeViews().contains("posix");

        try {
            Path parent = path.getParent();
            if (parent != null) {
                if (posix) {
                    Set<PosixFilePermission> dirPerms = PosixFilePermissions.fromString("rwxr-xr-x");
                    Files.createDirectories(parent, PosixFilePermissions.asFileAttribute(dirPerms));
                } else {
                    Files.createDirectories(parent);
                }
            }
        } catch (IOException e) {
            return ToolResult.error("Error creating directories: " + e.getMessage());
        }

        try {
            Files.writeString(path, content);
            if (posix) {
                Set<PosixFilePermission> filePerms = PosixFilePermissions.fromString("rw-r--r--");
                Files.setPosixFilePermissions(path, filePerms);
            }
        } catch (IOException e) {
            return ToolResult.error("Error writing file: " + e.getMessage());
        }

        // Update cache with new content + mtime
        if (fileStateCache != null) {
            fileStateCache.update(path.toAbsolutePath().toString(), content);
        }

        return ToolResult.success("Successfully wrote to " + filePath);
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }
}
