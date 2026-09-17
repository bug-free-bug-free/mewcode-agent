// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.worktree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * Post-creation setup for newly created worktrees.
 */
public final class PostCreationSetup {

    private static final Logger log = Logger.getLogger(PostCreationSetup.class.getName());

    private PostCreationSetup() {}

    public static void perform(String repoRoot, String worktreePath, List<String> symlinkDirs) {
        copySettingsLocal(repoRoot, worktreePath);
        configureHooksPath(repoRoot, worktreePath);
        symlinkDirectories(repoRoot, worktreePath, symlinkDirs);
        copyWorktreeIncludeFiles(repoRoot, worktreePath);
    }

    private static void copySettingsLocal(String repoRoot, String worktreePath) {
        Path src = Path.of(repoRoot, ".mewcode", "settings.local.json");
        Path dst = Path.of(worktreePath, ".mewcode", "settings.local.json");
        if (!Files.exists(src)) return;
        try {
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst);
        } catch (IOException e) {
            log.fine("Failed to copy settings.local.json: " + e.getMessage());
        }
    }

    private static void configureHooksPath(String repoRoot, String worktreePath) {
        String[] candidates = {".husky", ".git/hooks"};
        String hooksPath = null;
        for (String c : candidates) {
            Path p = Path.of(repoRoot, c);
            if (Files.isDirectory(p)) {
                hooksPath = p.toString();
                break;
            }
        }
        if (hooksPath == null) return;
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "config", "core.hooksPath", hooksPath);
            pb.directory(Path.of(worktreePath).toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            proc.waitFor();
        } catch (Exception e) {
            log.fine("Failed to configure hooks path: " + e.getMessage());
        }
    }

    private static void symlinkDirectories(String repoRoot, String worktreePath, List<String> dirs) {
        if (dirs == null) return;
        for (String dir : dirs) {
            if (dir.contains("..")) continue;
            Path src = Path.of(repoRoot, dir);
            Path dst = Path.of(worktreePath, dir);
            if (!Files.exists(src) || Files.exists(dst)) continue;
            try {
                Files.createSymbolicLink(dst, src);
            } catch (IOException e) {
                log.fine("Failed to symlink " + dir + ": " + e.getMessage());
            }
        }
    }

    private static void copyWorktreeIncludeFiles(String repoRoot, String worktreePath) {
        Path includeFile = Path.of(repoRoot, ".worktreeinclude");
        if (!Files.exists(includeFile)) return;
        try {
            List<String> patterns = Files.readAllLines(includeFile).stream()
                    .map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .toList();
            if (patterns.isEmpty()) return;

            ProcessBuilder pb = new ProcessBuilder("git", "ls-files",
                    "--others", "--ignored", "--exclude-standard", "--directory");
            pb.directory(Path.of(repoRoot).toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
            if (proc.exitValue() != 0 || output.isBlank()) return;

            for (String entry : output.strip().split("\n")) {
                if (entry.endsWith("/") || entry.isBlank()) continue;
                if (matchesAnyPattern(entry, patterns)) {
                    Path src = Path.of(repoRoot, entry);
                    Path dst = Path.of(worktreePath, entry);
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst);
                }
            }
        } catch (Exception e) {
            log.fine("Failed to copy worktreeinclude files: " + e.getMessage());
        }
    }

    private static boolean matchesAnyPattern(String path, List<String> patterns) {
        String baseName = Path.of(path).getFileName().toString();
        for (String p : patterns) {
            String normalized = p.startsWith("/") ? p.substring(1) : p;
            if (normalized.equals(path) || normalized.equals(baseName)) return true;
            if (normalized.endsWith("/") && path.startsWith(normalized)) return true;
        }
        return false;
    }
}
