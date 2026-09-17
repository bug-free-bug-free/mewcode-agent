// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-before-edit enforcement cache.
 * Tracks which files have been read (via ReadFile) so that EditFile / WriteFile
 * can refuse to modify a file that the model has never seen — mirroring the
 * same guard that Claude Code applies.
 */
public class FileStateCache {

    public record FileState(String content, long mtimeMs) {}

    private final ConcurrentHashMap<String, FileState> cache = new ConcurrentHashMap<>();

    /** Record a file that was just read. Stores full content + mtime. */
    public void record(String absPath, String content, long mtimeMs) {
        cache.put(absPath, new FileState(content, mtimeMs));
    }

    /** Update cache after a successful edit/write. Re-reads mtime from disk. */
    public void update(String absPath, String newContent) {
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(Path.of(absPath)).toMillis();
        } catch (IOException e) {
            mtime = System.currentTimeMillis();
        }
        cache.put(absPath, new FileState(newContent, mtime));
    }

    /** Returns null if the file has never been read. */
    public FileState get(String absPath) {
        return cache.get(absPath);
    }

    /**
     * Validate that a file is safe to edit/write.
     * Returns null if OK, or an error message string if the edit should be blocked.
     */
    public String validate(String absPath) {
        FileState state = cache.get(absPath);
        if (state == null) {
            return "Error: file has not been read yet. Read it first before editing.";
        }
        long currentMtime;
        try {
            currentMtime = Files.getLastModifiedTime(Path.of(absPath)).toMillis();
        } catch (IOException e) {
            // File might have been deleted; let the caller's own exists-check handle it
            return null;
        }
        if (currentMtime > state.mtimeMs()) {
            return "Error: file has been modified since last read. Read it again before editing.";
        }
        return null;
    }
}
