// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.worktree;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Manages git worktrees for parallel agent execution.
 * <p>
 * Each worktree gets its own branch and working directory under
 * {@code .mewcode/worktrees/<branch>}. Symlink directories (e.g.
 * node_modules) are linked from the main project root so that
 * worktrees share heavy dependency trees.
 */
public class WorktreeManager {

    public record WorktreeInfo(String path, String branch, Instant createdAt) {}

    private final String projectRoot;
    private final List<String> symlinkDirs;
    private final int staleCutoffHours;

    private final Map<String, WorktreeInfo> worktrees = new LinkedHashMap<>();

    public WorktreeManager(String projectRoot, List<String> symlinkDirs, int staleCutoffHours) {
        this.projectRoot = projectRoot;
        this.symlinkDirs = symlinkDirs != null ? symlinkDirs : List.of();
        this.staleCutoffHours = staleCutoffHours > 0 ? staleCutoffHours : 24;
    }

    public String getProjectRoot() { return projectRoot; }
    public List<String> getSymlinkDirs() { return symlinkDirs; }
    public int getStaleCutoffHours() { return staleCutoffHours; }

    /**
     * Creates a new git worktree for the given branch under
     * {@code .mewcode/worktrees/<branch>}.
     *
     * @param branch    the new branch name
     * @param targetDir optional override for the worktree directory; when
     *                  {@code null}, defaults to {@code .mewcode/worktrees/<branch>}
     * @return metadata about the created worktree
     */
    public synchronized WorktreeInfo create(String branch, Path targetDir) throws Exception {
        Path wtDir = targetDir != null
                ? targetDir
                : Path.of(projectRoot, ".mewcode", "worktrees", branch);

        // -B (uppercase) resets any orphan branch left behind by a removed worktree
        String output = runGit(projectRoot, "git", "worktree", "add", "-B", branch, wtDir.toString());

        // Post-creation setup: settings, hooks, symlinks, .worktreeinclude
        PostCreationSetup.perform(projectRoot, wtDir.toString(), symlinkDirs);

        var info = new WorktreeInfo(wtDir.toString(), branch, Instant.now());
        worktrees.put(branch, info);
        return info;
    }

    /**
     * Removes a worktree by branch name.
     */
    public synchronized void remove(String branch) throws Exception {
        WorktreeInfo info = worktrees.get(branch);
        if (info == null) {
            throw new IllegalArgumentException("worktree not found: " + branch);
        }

        runGit(projectRoot, "git", "worktree", "remove", info.path(), "--force");
        worktrees.remove(branch);
    }

    /**
     * Lists worktrees by parsing {@code git worktree list --porcelain} output.
     * <p>
     * Falls back to the in-memory map when porcelain parsing yields no results
     * (e.g. bare repos without linked worktrees).
     */
    public synchronized List<WorktreeInfo> list() {
        try {
            String output = runGit(projectRoot, "git", "worktree", "list", "--porcelain");
            List<WorktreeInfo> result = parsePorcelain(output);
            if (!result.isEmpty()) {
                return result;
            }
        } catch (Exception ignored) {
            // fall through to in-memory map
        }
        return new ArrayList<>(worktrees.values());
    }

    /**
     * Returns the worktree info for a branch if it is tracked in memory.
     */
    public synchronized Optional<WorktreeInfo> get(String branch) {
        return Optional.ofNullable(worktrees.get(branch));
    }

    /**
     * Removes worktrees older than the given number of hours.
     *
     * @param cutoffHours maximum age in hours; uses the configured default when {@code <= 0}
     * @return the number of worktrees removed
     */
    public synchronized int cleanupStale(int cutoffHours) {
        int hours = cutoffHours > 0 ? cutoffHours : staleCutoffHours;
        Instant cutoff = Instant.now().minusSeconds((long) hours * 3600);
        int removed = 0;

        var it = worktrees.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            WorktreeInfo info = entry.getValue();
            if (info.createdAt().isBefore(cutoff)) {
                try {
                    runGit(projectRoot, "git", "worktree", "remove", info.path(), "--force");
                    it.remove();
                    removed++;
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            }
        }
        return removed;
    }

    /**
     * Removes all tracked worktrees (best-effort).
     */
    public synchronized void removeAll() {
        var it = worktrees.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            try {
                runGit(projectRoot, "git", "worktree", "remove", entry.getValue().path(), "--force");
            } catch (Exception ignored) {
                // best-effort
            }
            it.remove();
        }
    }

    /**
     * Detects uncommitted changes in a worktree via {@code git diff --stat}.
     *
     * @param worktreePath the path of the worktree to inspect
     * @return the diff stat output, or empty string if clean
     */
    public static String detectChanges(String worktreePath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git", "diff", "--stat");
        pb.directory(Path.of(worktreePath).toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes());
        }

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git diff timed out in " + worktreePath);
        }
        if (process.exitValue() != 0) {
            throw new IOException("git diff failed: " + output);
        }
        return output.strip();
    }

    // ---- internal helpers ----

    private static String runGit(String workDir, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(Path.of(workDir).toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes());
        }

        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git command timed out: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            throw new IOException(String.join(" ", command) + ": " + output);
        }
        return output;
    }

    /**
     * Parses porcelain output from {@code git worktree list --porcelain}.
     * Each block is separated by a blank line and contains lines like:
     * <pre>
     * worktree /path/to/wt
     * HEAD abc123
     * branch refs/heads/branch-name
     * </pre>
     */
    private static List<WorktreeInfo> parsePorcelain(String output) {
        List<WorktreeInfo> result = new ArrayList<>();
        String currentPath = null;
        String currentBranch = null;

        for (String line : output.split("\n")) {
            if (line.startsWith("worktree ")) {
                currentPath = line.substring("worktree ".length()).strip();
            } else if (line.startsWith("branch ")) {
                String ref = line.substring("branch ".length()).strip();
                // refs/heads/my-branch -> my-branch
                if (ref.startsWith("refs/heads/")) {
                    currentBranch = ref.substring("refs/heads/".length());
                } else {
                    currentBranch = ref;
                }
            } else if (line.isBlank()) {
                if (currentPath != null && currentBranch != null) {
                    result.add(new WorktreeInfo(currentPath, currentBranch, Instant.now()));
                }
                currentPath = null;
                currentBranch = null;
            }
        }
        // handle last block (no trailing blank line)
        if (currentPath != null && currentBranch != null) {
            result.add(new WorktreeInfo(currentPath, currentBranch, Instant.now()));
        }
        return result;
    }
}
