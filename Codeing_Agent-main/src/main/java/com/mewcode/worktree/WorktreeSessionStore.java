// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.worktree;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists WorktreeSession to disk and manages the global singleton.
 */
public final class WorktreeSessionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile WorktreeSession currentSession;

    private WorktreeSessionStore() {}

    public static WorktreeSession getCurrentSession() {
        return currentSession;
    }

    public static void restoreSession(WorktreeSession session) {
        currentSession = session;
    }

    public static void save(String repoRoot, WorktreeSession session) throws IOException {
        Path path = sessionPath(repoRoot);
        if (session == null) {
            Files.deleteIfExists(path);
            return;
        }
        Files.createDirectories(path.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), session);
    }

    public static WorktreeSession load(String repoRoot) {
        Path path = sessionPath(repoRoot);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return MAPPER.readValue(path.toFile(), WorktreeSession.class);
        } catch (IOException e) {
            return null;
        }
    }

    static void clearForTesting() {
        currentSession = null;
    }

    private static Path sessionPath(String repoRoot) {
        return Path.of(repoRoot, ".mewcode", "worktree_session.json");
    }
}
