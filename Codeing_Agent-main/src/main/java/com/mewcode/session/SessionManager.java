// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.conversation.ConversationManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

public class SessionManager {

    /**
     * TYPE_COMPACT_BOUNDARY marks a session record as a compaction boundary
     * rather than a plain conversation message. A boundary record's content
     * holds a JSON blob (see {@link CompactBoundary}) carrying the summary text
     * plus the recent tail (keep) preserved verbatim at compaction time. Plain
     * messages leave {@code type} null/empty, so old sessions and normal turns
     * are unaffected (append-only, backward-compatible).
     */
    public static final String TYPE_COMPACT_BOUNDARY = "compact_boundary";

    /**
     * A session record. {@code type} distinguishes record kinds: empty/null (the
     * default) means a plain conversation message; {@link #TYPE_COMPACT_BOUNDARY}
     * means {@code content} is a {@link CompactBoundary} JSON blob written by
     * {@link #saveCompactBoundary}.
     */
    public record SessionMessage(String role, String type, String content, long timestamp) {
        /** Convenience constructor for plain (non-boundary) messages. */
        public SessionMessage(String role, String content, long timestamp) {
            this(role, null, content, timestamp);
        }

        public boolean isCompactBoundary() {
            return TYPE_COMPACT_BOUNDARY.equals(type);
        }
    }

    /**
     * One verbatim message preserved in the recent tail at compaction time. Only
     * role + content text is stored, matching how the session log already
     * persists messages (text only, no tool blocks).
     */
    public record KeepMessage(String role, String content) {}

    /**
     * Structured payload stored (as JSON) in the content of a boundary record.
     * {@code summary} is the LLM-produced summary of the older prefix; {@code keep}
     * is the recent tail kept verbatim. On resume the compacted state is rebuilt
     * as: [user message = summary] + keep + any plain messages appended after the
     * boundary.
     */
    public record CompactBoundary(String summary, List<KeepMessage> keep) {}

    /** Result of {@link #findLastCompactBoundary}: the boundary and the plain messages after it. */
    public record BoundaryScan(CompactBoundary boundary, List<SessionMessage> after, boolean found) {}

    public record SessionInfo(String id, String firstMessage, int messageCount,
                              long fileSize, String gitBranch, Instant modTime) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path sessionsDir(String workDir) {
        return Path.of(workDir, ".mewcode", "sessions");
    }

    // ---- ID generation ----

    public static String newId() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }

    // ---- Persistence ----

    public static void saveMessage(String workDir, String sessionId, String role, String content) {
        saveRecord(workDir, sessionId, role, null, content);
    }

    /**
     * Append a compaction boundary record so a later resume can rebuild the
     * compacted state (summary + kept tail) instead of replaying the full
     * pre-compaction transcript. Append-only: the original prefix messages stay
     * in the file but won't be replayed past this boundary (see
     * {@link #findLastCompactBoundary}). The summary + keep are inlined into the
     * record's content as a {@link CompactBoundary} JSON blob. No-op when
     * workDir/sessionId is null/blank (tests, one-shot callers).
     */
    public static void saveCompactBoundary(String workDir, String sessionId,
                                           String summary, List<KeepMessage> keep) {
        if (workDir == null || workDir.isBlank() || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            String blob = MAPPER.writeValueAsString(
                    new CompactBoundary(summary, keep == null ? List.of() : keep));
            saveRecord(workDir, sessionId, "system", TYPE_COMPACT_BOUNDARY, blob);
        } catch (JsonProcessingException ignored) {
            // best-effort: a failed boundary just means the next resume replays
            // verbatim, which is still correct (backward-compatible).
        }
    }

    private static void saveRecord(String workDir, String sessionId,
                                   String role, String type, String content) {
        try {
            Path baseDir = sessionsDir(workDir);
            Files.createDirectories(baseDir);
            Path file = baseDir.resolve(sessionId + ".jsonl");
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("role", role);
            // omit `type` for plain messages so old readers and old sessions are
            // unaffected (matches Go's `omitempty`).
            if (type != null && !type.isEmpty()) {
                line.put("type", type);
            }
            line.put("content", content);
            line.put("ts", Instant.now().getEpochSecond());
            String json = MAPPER.writeValueAsString(line) + "\n";
            Files.writeString(file, json, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // best-effort, same as the Go version
        }
    }

    public static List<SessionMessage> loadSession(String workDir, String sessionId) {
        Path file = sessionsDir(workDir).resolve(sessionId + ".jsonl");
        if (!Files.exists(file)) {
            return List.of();
        }
        List<SessionMessage> messages = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = MAPPER.readValue(line, Map.class);
                    String role = (String) map.get("role");
                    String type = (String) map.get("type");
                    String content = (String) map.get("content");
                    long ts = map.get("ts") instanceof Number n ? n.longValue() : 0L;
                    if (content != null && !content.isEmpty()) {
                        messages.add(new SessionMessage(role, type, content, ts));
                    }
                } catch (IOException ignored) {
                    // skip malformed lines
                }
            }
        } catch (IOException ignored) {
            // return whatever we collected so far
        }
        return messages;
    }

    // ---- Compaction-boundary scanning ----

    /**
     * Scan the loaded records for the LAST compaction boundary. Returns the
     * parsed boundary plus the plain (non-boundary) messages appended after it.
     * When no boundary exists (or its blob is corrupt) {@code found} is false and
     * the caller should replay all records verbatim — backward-compatible with
     * old sessions that have no boundary records.
     */
    public static BoundaryScan findLastCompactBoundary(List<SessionMessage> messages) {
        int last = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).isCompactBoundary()) {
                last = i;
            }
        }
        if (last < 0) {
            return new BoundaryScan(null, List.of(), false);
        }
        CompactBoundary boundary;
        try {
            boundary = MAPPER.readValue(messages.get(last).content(), CompactBoundary.class);
        } catch (IOException e) {
            // Corrupt boundary blob — fall back to full replay rather than losing
            // the conversation.
            return new BoundaryScan(null, List.of(), false);
        }
        List<SessionMessage> after = new ArrayList<>();
        for (int i = last + 1; i < messages.size(); i++) {
            SessionMessage m = messages.get(i);
            if (m.isCompactBoundary()) continue; // defensive; we targeted the final one
            after.add(m);
        }
        return new BoundaryScan(boundary, after, true);
    }

    // ---- Conversation rebuild ----

    /**
     * Compaction-aware rebuild. If the session contains a {@code compact_boundary},
     * the live conversation is the compacted state — [summary as user message] +
     * kept tail + any plain messages appended after the boundary — and the
     * original pre-compaction prefix is NOT replayed (it stays in the file for
     * audit). Without a boundary (old sessions) everything is replayed verbatim.
     */
    public static ConversationManager rebuildConversation(List<SessionMessage> messages) {
        BoundaryScan scan = findLastCompactBoundary(messages);
        if (!scan.found()) {
            return replay(messages);
        }
        List<SessionMessage> replay = new ArrayList<>();
        // Summary becomes the leading user message with the same Chinese framing
        // as autoCompact, so the model sees a consistent context header on resume.
        String resumeSummary = "本次会话延续自之前的对话，因上下文空间不足进行了压缩。以下是早期对话的摘要：\n\n"
                + scan.boundary().summary();
        if (!scan.boundary().keep().isEmpty()) {
            resumeSummary += "\n\n近期消息已原样保留。";
        }
        replay.add(new SessionMessage("user", resumeSummary, 0L));
        for (KeepMessage k : scan.boundary().keep()) {
            replay.add(new SessionMessage(k.role(), k.content(), 0L));
        }
        replay.addAll(scan.after());
        return replay(replay);
    }

    private static ConversationManager replay(List<SessionMessage> messages) {
        ConversationManager conversation = new ConversationManager();
        for (SessionMessage msg : messages) {
            if (msg.isCompactBoundary()) continue; // never replay the raw boundary blob
            switch (msg.role()) {
                case "assistant" -> conversation.addAssistantMessage(msg.content());
                default -> conversation.addUserMessage(msg.content());
            }
        }
        return conversation;
    }

    // ---- Listing ----

    public static List<SessionInfo> listSessions(String workDir) {
        Path baseDir = sessionsDir(workDir);
        if (!Files.isDirectory(baseDir)) {
            return List.of();
        }
        String branch = currentGitBranch(workDir);
        List<SessionInfo> sessions = new ArrayList<>();
        try (Stream<Path> paths = Files.list(baseDir)) {
            paths.filter(p -> p.toString().endsWith(".jsonl"))
                 .filter(Files::isRegularFile)
                 .forEach(p -> {
                     String fileName = p.getFileName().toString();
                     String id = fileName.substring(0, fileName.length() - ".jsonl".length());
                     try {
                         long fileSize = Files.size(p);
                         Instant modTime = Files.getLastModifiedTime(p).toInstant();
                         List<SessionMessage> msgs = loadSession(workDir, id);
                         String first = msgs.stream()
                                 .filter(m -> "user".equals(m.role()))
                                 .map(SessionMessage::content)
                                 .findFirst()
                                 .orElse("");
                         sessions.add(new SessionInfo(id, first, msgs.size(),
                                 fileSize, branch, modTime));
                     } catch (IOException ignored) {
                         // skip this file
                     }
                 });
        } catch (IOException ignored) {
            // return empty
        }
        sessions.sort(Comparator.comparing(SessionInfo::modTime).reversed());
        return sessions;
    }

    // ---- Git branch ----

    public static String currentGitBranch(String workDir) {
        try {
            Process proc = new ProcessBuilder("git", "-C", workDir, "rev-parse", "--abbrev-ref", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(proc.getInputStream().readAllBytes()).trim();
            int code = proc.waitFor();
            return code == 0 ? output : "";
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }

    // ---- Formatting helpers ----

    public static String formatRelativeTime(Instant t) {
        Duration d = Duration.between(t, Instant.now());
        long seconds = d.getSeconds();
        if (seconds < 60) {
            return "just now";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes == 1 ? "1 minute ago" : minutes + " minutes ago";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours == 1 ? "1 hour ago" : hours + " hours ago";
        }
        long days = hours / 24;
        if (days < 7) {
            return days == 1 ? "1 day ago" : days + " days ago";
        }
        long weeks = days / 7;
        return weeks == 1 ? "1 week ago" : weeks + " weeks ago";
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            double kb = bytes / 1024.0;
            return kb == (long) kb
                    ? String.format("%.0fKB", kb)
                    : String.format("%.1fKB", kb);
        }
        double mb = bytes / 1024.0 / 1024.0;
        return String.format("%.1fMB", mb);
    }

    // ---- Search ----

    public static boolean matchesSearch(SessionInfo s, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String q = query.toLowerCase();
        return s.firstMessage().toLowerCase().contains(q)
                || s.id().toLowerCase().contains(q);
    }
}
