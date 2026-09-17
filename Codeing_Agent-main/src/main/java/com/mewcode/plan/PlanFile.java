// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.plan;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Manages plan files stored under {@code .mewcode/plans/} in the working
 * directory.
 * <p>
 * A plan slug is generated from word lists and a timestamp, and the
 * singleton plan path is cached for the lifetime of the process.
 */
public class PlanFile {

    private static final String PLANS_DIR = ".mewcode/plans";

    private static final String[] ADJECTIVES = {
            "bright", "calm", "bold", "swift", "quiet",
            "vivid", "clear", "keen", "warm", "cool",
            "sharp", "light", "deep", "pure", "soft",
    };

    private static final String[] NOUNS = {
            "plan", "draft", "design", "sketch", "blueprint",
            "outline", "strategy", "approach", "scheme", "map",
            "vision", "path", "route", "guide", "frame",
    };

    private static String currentPlanPath;

    // ── Slug generation ─────────────────────────────────────────────────

    /**
     * Generates a human-friendly slug such as {@code bold-sketch-0515-1423}.
     * Uses the current nanosecond timestamp modulo the word-list lengths,
     * matching the Go implementation's selection logic.
     */
    public static String generateSlug() {
        long nanos = System.nanoTime();
        int ai = (int) ((nanos / 1000) % ADJECTIVES.length);
        int ni = (int) ((nanos / 100) % NOUNS.length);
        if (ai < 0) ai += ADJECTIVES.length;
        if (ni < 0) ni += NOUNS.length;
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("MMdd-HHmm"));
        return ADJECTIVES[ai] + "-" + NOUNS[ni] + "-" + timestamp;
    }

    // ── Path management ─────────────────────────────────────────────────

    public static String getOrCreatePlanPath(String workDir) {
        if (currentPlanPath != null) {
            return currentPlanPath;
        }
        Path dir = Path.of(workDir, PLANS_DIR);
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // best effort
        }
        String slug = generateSlug();
        currentPlanPath = dir.resolve(slug + ".md").toString();
        return currentPlanPath;
    }

    public static String getPlanFilePath(String workDir) {
        if (currentPlanPath != null) {
            return currentPlanPath;
        }
        return getOrCreatePlanPath(workDir);
    }

    public static void setPlanFilePath(String path) {
        currentPlanPath = path;
    }

    public static void resetPlanPath() {
        currentPlanPath = null;
    }

    // ── Persistence ─────────────────────────────────────────────────────

    public static boolean planExists() {
        return currentPlanPath != null && Files.exists(Path.of(currentPlanPath));
    }

    public static String loadPlan() throws IOException {
        if (currentPlanPath == null) {
            return "";
        }
        Path path = Path.of(currentPlanPath);
        if (!Files.exists(path)) {
            return "";
        }
        return Files.readString(path);
    }

    public static void savePlan(String workDir, String content) throws IOException {
        String path = getOrCreatePlanPath(workDir);
        Path target = Path.of(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    // ── Utilities ───────────────────────────────────────────────────────

    /**
     * Returns {@code true} when {@code targetPath} refers to the same file
     * as {@code planPath} (after normalization) or when one is a suffix of
     * the other. This matches the Go helper
     * {@code IsPlanFilePath(targetPath, planPath)}.
     */
    public static boolean isPlanFilePath(String targetPath, String planPath) {
        if (planPath == null || planPath.isBlank()) {
            return false;
        }
        String cleanTarget = Path.of(targetPath).normalize().toString();
        String cleanPlan = Path.of(planPath).normalize().toString();
        return cleanTarget.equals(cleanPlan) || cleanTarget.endsWith(cleanPlan);
    }
}
