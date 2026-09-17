// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.compact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.ThinkingBlock;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.StreamEvent;
import com.mewcode.session.SessionManager;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * Two-layer context compaction: Layer 1 offloads and snips locally,
 * Layer 2 triggers a full LLM summary when tokens exceed 80%.
 */
public final class ContextCompactor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // AUTOCOMPACT_THRESHOLD is the legacy ratio gate (kept for reference only).
    // The live decision now uses the absolute-token formula below, aligned with
    // Claude Code's autoCompact.ts: trigger on used-tokens >= effectiveWindow − margin.
    private static final double AUTOCOMPACT_THRESHOLD = 0.80;

    // SUMMARY_OUTPUT_RESERVE reserves room for the summary response itself, so the
    // effective window is contextWindow − min(model maxOutput, SUMMARY_OUTPUT_RESERVE).
    private static final int SUMMARY_OUTPUT_RESERVE = 20_000;
    // AUTO_COMPACT_SAFETY_MARGIN sets the soft auto-compact trigger line below the
    // effective window.
    private static final int AUTO_COMPACT_SAFETY_MARGIN = 13_000;
    // MANUAL_COMPACT_SAFETY_MARGIN sets the hard-block line: once used tokens cross
    // effectiveWindow − MANUAL_COMPACT_SAFETY_MARGIN, we force a compaction rather
    // than rely on the soft trigger.
    private static final int MANUAL_COMPACT_SAFETY_MARGIN = 3_000;

    private static final int SINGLE_RESULT_LIMIT = 50_000;
    private static final int MESSAGE_AGGREGATE_LIMIT = 200_000;
    private static final int OLD_RESULT_SNIP_CHARS = 2_000;
    private static final int KEEP_RECENT_TURNS = 10;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    // ── messagesToKeep window (aligned with Claude Code compact.ts) ────────
    // A Layer 2 compaction no longer collapses the whole conversation into a
    // lone summary; it keeps the recent verbatim tail and only summarizes the
    // older prefix. KEEP_RECENT_TOKENS is the floor we try to keep, capped by
    // KEEP_MAX_TOKENS; MIN_KEEP_MESSAGES guarantees at least a few raw turns
    // survive even when they are tiny.
    private static final int KEEP_RECENT_TOKENS = 10_000;
    private static final int MIN_KEEP_MESSAGES = 5;
    private static final int KEEP_MAX_TOKENS = 40_000;

    private static final String SPILL_SUBDIR = ".mewcode/tool_results";

    /** Recovery limits applied to the attachment block appended after a Layer 2 summary. */
    public static final int RECOVERY_FILE_LIMIT = 5;
    public static final int RECOVERY_TOKENS_PER_FILE = 5_000;
    public static final int RECOVERY_SKILLS_BUDGET = 25_000;
    public static final int RECOVERY_TOKENS_PER_SKILL = 5_000;
    private static final double RECOVERY_CHARS_PER_TOKEN = 3.5;
    private static final DateTimeFormatter RECOVERY_TS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private static final String SUMMARY_SYSTEM_PROMPT = """
            Your task is to create a detailed summary of the conversation so far, paying close attention to the user's explicit requests and your previous actions.

            Before providing your final summary, wrap your analysis in <analysis> tags to organize your thoughts. In your analysis:
            1. Chronologically walk through each message, identifying requests, approaches, decisions, file paths, errors and fixes, user feedback.
            2. Double-check for technical accuracy and completeness.

            After your analysis, output your final summary wrapped in <summary> tags. The summary MUST preserve:
            - All file paths that were read, modified, or created
            - Key decisions and their rationale
            - The current task/goal and overall progress
            - Any pending work or next steps
            - Error states and their resolution
            - Important code snippets or patterns discussed

            Output structure:

            <analysis>
            [Your thought process]
            </analysis>

            <summary>
            [The final compact summary]
            </summary>""";

    private ContextCompactor() {}

    // ── Circuit Breaker ────────────────────────────────────────────────

    public static class AutoCompactTrackingState {
        private int consecutiveFailures;

        public boolean isTripped() {
            return consecutiveFailures >= MAX_CONSECUTIVE_FAILURES;
        }

        public void recordFailure() {
            consecutiveFailures++;
        }

        public void reset() {
            consecutiveFailures = 0;
        }
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Returns the absolute used-token line at which Layer 2 should fire.
     * effectiveWindow = contextWindow − min(maxOutput, SUMMARY_OUTPUT_RESERVE);
     * the threshold is effectiveWindow minus the safety margin (manual margin for
     * the hard-block line, auto margin for the soft trigger).
     */
    private static int computeCompactThreshold(int contextWindow, int maxOutput, boolean manual) {
        int reserve = SUMMARY_OUTPUT_RESERVE;
        if (maxOutput > 0 && maxOutput < reserve) {
            reserve = maxOutput;
        }
        int effectiveWindow = contextWindow - reserve;
        int margin = manual ? MANUAL_COMPACT_SAFETY_MARGIN : AUTO_COMPACT_SAFETY_MARGIN;
        return effectiveWindow - margin;
    }

    /**
     * Layer 1 runs unconditionally (offload + snip). Layer 2 fires when used
     * tokens reach the auto-compact threshold (effectiveWindow − auto margin);
     * once they cross the hard-block line (effectiveWindow − manual margin) it
     * forces a compaction. See {@link #computeCompactThreshold}.
     */
    public static String manage(ConversationManager conv, LlmClient client,
                                int contextWindow, int maxOutput, String workDir,
                                AutoCompactTrackingState tracking,
                                RecoveryState recovery,
                                List<Map<String, Object>> toolSchemas) {
        return manage(conv, client, contextWindow, maxOutput, workDir, null, tracking,
                recovery, toolSchemas, null);
    }

    public static String manage(ConversationManager conv, LlmClient client,
                                int contextWindow, int maxOutput, String workDir,
                                AutoCompactTrackingState tracking,
                                RecoveryState recovery,
                                List<Map<String, Object>> toolSchemas,
                                UsageAnchor anchor) {
        return manage(conv, client, contextWindow, maxOutput, workDir, null, tracking,
                recovery, toolSchemas, anchor);
    }

    /**
     * Layer 1 + Layer 2 management. {@code workDir} + {@code sessionId} locate the
     * on-disk session log; when both are non-blank a Layer 2 compaction also
     * appends a {@code compact_boundary} record (summary + kept tail) so a later
     * resume can rebuild the compacted state. They are null/blank for sub-agents
     * and one-shot callers, in which case no boundary is written.
     */
    public static String manage(ConversationManager conv, LlmClient client,
                                int contextWindow, int maxOutput, String workDir, String sessionId,
                                AutoCompactTrackingState tracking,
                                RecoveryState recovery,
                                List<Map<String, Object>> toolSchemas,
                                UsageAnchor anchor) {
        String l1 = offloadAndSnip(conv, workDir);

        int tokens = currentTokens(conv.getMessages(), anchor);
        // Soft auto-compact trigger: used tokens >= effectiveWindow − auto margin.
        if (tokens < computeCompactThreshold(contextWindow, maxOutput, false)) {
            return l1;
        }

        // Hard-block line: once used tokens cross effectiveWindow − manual margin,
        // force a compaction (bypassing the circuit breaker) instead of the soft
        // auto path, since the context is too close to the wall to risk skipping.
        if (tokens >= computeCompactThreshold(contextWindow, maxOutput, true)) {
            return forceCompact(conv, client, contextWindow, workDir, sessionId, recovery, toolSchemas);
        }

        if (tracking == null || !tracking.isTripped()) {
            try {
                String l2 = autoCompact(conv, client, contextWindow, workDir, sessionId, recovery, toolSchemas);
                if (tracking != null) tracking.reset();
                return l2;
            } catch (Exception e) {
                if (tracking != null) tracking.recordFailure();
            }
        }
        return l1;
    }

    /** Force a full auto-compact regardless of current token usage (no session boundary). */
    public static String forceCompact(ConversationManager conv, LlmClient client, int contextWindow,
                                      RecoveryState recovery, List<Map<String, Object>> toolSchemas) {
        return autoCompact(conv, client, contextWindow, null, null, recovery, toolSchemas);
    }

    /**
     * Force a full auto-compact, writing a compact_boundary into the session log
     * when {@code workDir}/{@code sessionId} are provided.
     */
    public static String forceCompact(ConversationManager conv, LlmClient client, int contextWindow,
                                      String workDir, String sessionId,
                                      RecoveryState recovery, List<Map<String, Object>> toolSchemas) {
        return autoCompact(conv, client, contextWindow, workDir, sessionId, recovery, toolSchemas);
    }

    /**
     * Real API-usage anchor for context-window accounting. Captured after each
     * stream ends: {@code baselineTokens} = input + cacheRead + cacheCreation +
     * output reported by the provider, and {@code anchorCount} = the number of
     * conversation messages present when that usage was measured. Everything
     * after {@code anchorCount} is estimated incrementally on top of the
     * baseline, so a cache hit (where the real input is far below the raw
     * character estimate) no longer inflates the compaction decision.
     */
    public record UsageAnchor(int baselineTokens, int anchorCount) {}

    /**
     * Current used-token estimate for the compaction decision.
     *
     * <p>With a real-usage {@code anchor}: {@code baselineTokens} plus a
     * character estimate of only the messages appended after the anchor
     * (index >= anchorCount). Without an anchor (cold start, before any stream
     * has reported usage) it falls back to estimating all messages, matching
     * the legacy behaviour so the first turn still works.
     */
    public static int currentTokens(List<Message> messages, UsageAnchor anchor) {
        if (anchor == null || anchor.anchorCount() < 0
                || anchor.anchorCount() > messages.size()) {
            return estimateTokens(messages);
        }
        List<Message> appended = messages.subList(anchor.anchorCount(), messages.size());
        return anchor.baselineTokens() + estimateTokens(appended);
    }

    /** Estimate the token count for a list of messages using a simple heuristic. */
    public static int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message m : messages) {
            total += (int) (safeLength(m.getContent()) / 3.5) + 4;

            if (m.getToolUses() != null) {
                for (ToolUseBlock tu : m.getToolUses()) {
                    String argsJson;
                    try {
                        argsJson = MAPPER.writeValueAsString(tu.arguments());
                    } catch (JsonProcessingException e) {
                        argsJson = "{}";
                    }
                    total += 50 + (int) (argsJson.length() / 3.5);
                }
            }

            if (m.getToolResults() != null) {
                for (ToolResultBlock tr : m.getToolResults()) {
                    total += (int) (safeLength(tr.content()) / 3.5) + 10;
                }
            }

            if (m.getThinkingBlocks() != null) {
                for (ThinkingBlock tb : m.getThinkingBlocks()) {
                    total += (int) (safeLength(tb.thinking()) / 3.5);
                }
            }
        }
        return total;
    }

    // ── Layer 1: Offload & Snip ────────────────────────────────────────

    static String offloadAndSnip(ConversationManager conv, String workDir) {
        List<Message> messages = conv.getMessagesMutable();
        if (messages.isEmpty()) return "";

        String spillDir = workDir != null
                ? Path.of(workDir, SPILL_SUBDIR).toString()
                : null;
        int spillCount = 0;
        int snipCount = 0;
        int savedChars = 0;
        boolean changed = false;

        int boundary = Math.max(0, messages.size() - KEEP_RECENT_TURNS * 3);

        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg.getToolResults() == null) continue;

            List<ToolResultBlock> results = new ArrayList<>(msg.getToolResults());
            boolean msgChanged = false;

            // Per-result spill: single result above SINGLE_RESULT_LIMIT
            for (int j = 0; j < results.size(); j++) {
                ToolResultBlock tr = results.get(j);
                if (alreadyProcessed(tr.content()) || safeLength(tr.content()) <= SINGLE_RESULT_LIMIT) {
                    continue;
                }
                if (spillDir == null) continue;
                Path path = writeSpill(spillDir, tr.toolUseId(), tr.content());
                if (path == null) continue;

                savedChars += tr.content().length();
                results.set(j, new ToolResultBlock(
                        tr.toolUseId(),
                        String.format("[Result of %d chars saved to %s]",
                                tr.content().length(), path),
                        tr.isError()));
                spillCount++;
                msgChanged = true;
            }

            // Per-message aggregate spill
            int agg = 0;
            for (ToolResultBlock tr : results) {
                agg += safeLength(tr.content());
            }
            if (agg > MESSAGE_AGGREGATE_LIMIT && spillDir != null) {
                for (int j = 0; j < results.size(); j++) {
                    ToolResultBlock tr = results.get(j);
                    if (alreadyProcessed(tr.content()) || safeLength(tr.content()) <= 200) {
                        continue;
                    }
                    Path path = writeSpill(spillDir, tr.toolUseId(), tr.content());
                    if (path == null) continue;

                    savedChars += tr.content().length();
                    results.set(j, new ToolResultBlock(
                            tr.toolUseId(),
                            String.format("[Result of %d chars saved to %s]",
                                    tr.content().length(), path),
                            tr.isError()));
                    spillCount++;
                    msgChanged = true;
                }
            }

            // Snip stale results past the recent-turns boundary
            if (i < boundary) {
                for (int j = 0; j < results.size(); j++) {
                    ToolResultBlock tr = results.get(j);
                    if (alreadyProcessed(tr.content()) || safeLength(tr.content()) <= OLD_RESULT_SNIP_CHARS) {
                        continue;
                    }
                    results.set(j, new ToolResultBlock(
                            tr.toolUseId(),
                            String.format("[Stale output snipped: %d chars]", tr.content().length()),
                            tr.isError()));
                    snipCount++;
                    msgChanged = true;
                }
            }

            if (msgChanged) {
                msg.setToolResults(results);
                changed = true;
            }
        }

        if (!changed) return "";
        rebuildConversation(conv, messages);

        var parts = new ArrayList<String>();
        if (spillCount > 0) parts.add(String.format("spilled %d tool result(s) to disk", spillCount));
        if (snipCount > 0) parts.add(String.format("snipped %d stale result(s)", snipCount));
        return String.format("%s (~%d chars freed)", String.join("; ", parts), savedChars);
    }

    // ── Layer 2: Auto-compact ──────────────────────────────────────────

    /**
     * Pick the index where the verbatim "keep" tail begins, mirroring Claude
     * Code's messagesToKeep selection.
     *
     * <p>Walk backwards from the end accumulating each message's estimated
     * tokens. We stop and keep everything from the current index once either
     * floor (KEEP_RECENT_TOKENS of tokens, or MIN_KEEP_MESSAGES of messages) is
     * met — whichever comes first. The accumulator is also capped: if adding a
     * message would push the kept tail over KEEP_MAX_TOKENS we stop before it.
     *
     * <p>Pairing protection: a {@code user} message carrying tool_result blocks
     * must never be kept without its originating {@code assistant} tool_use
     * message. If the chosen boundary lands on such a message, we walk back one
     * more (to include the assistant turn that issued the tool_use), so we never
     * keep an orphaned half of a tool_use↔tool_result pair.
     *
     * @return the start index of the keep window, or 0 when everything fits in
     *         the keep window (nothing left to summarize).
     */
    static int computeKeepStartIndex(List<Message> messages) {
        int n = messages.size();
        if (n == 0) return 0;

        int accumulated = 0;
        int kept = 0;
        int keepStart = n; // exclusive walk: keepStart is the first kept index
        for (int i = n - 1; i >= 0; i--) {
            int msgTokens = estimateTokens(List.of(messages.get(i)));
            // Cap: if this message would push the kept tail past the ceiling,
            // stop now and leave it in the summarized prefix.
            if (accumulated + msgTokens > KEEP_MAX_TOKENS && kept > 0) {
                break;
            }
            accumulated += msgTokens;
            kept++;
            keepStart = i;
            // Floor: satisfied as soon as EITHER threshold is reached.
            if (accumulated >= KEEP_RECENT_TOKENS || kept >= MIN_KEEP_MESSAGES) {
                break;
            }
        }

        // Pairing protection: never start the keep window on a user message that
        // only carries tool_result blocks — that would orphan it from its
        // assistant tool_use. Move the boundary back to include the assistant
        // turn (and skip any further dangling tool_result messages).
        while (keepStart > 0 && isToolResultMessage(messages.get(keepStart))) {
            keepStart--;
        }
        return keepStart;
    }

    private static boolean isToolResultMessage(Message m) {
        return "user".equals(m.getRole())
                && m.getToolResults() != null
                && !m.getToolResults().isEmpty();
    }

    private static String autoCompact(ConversationManager conv, LlmClient client, int contextWindow,
                                      String workDir, String sessionId,
                                      RecoveryState recovery, List<Map<String, Object>> toolSchemas) {
        List<Message> messages = conv.getMessages();
        int beforeTokens = estimateTokens(messages);

        // Keep the recent verbatim tail; only summarize the older prefix.
        int keepStartIndex = computeKeepStartIndex(messages);

        // Degenerate cases: nothing to summarize (everything is in the keep
        // window) or the prefix is too small to be worth a summary round-trip.
        // Fall back to the original behaviour of leaving the conversation as-is.
        if (keepStartIndex <= 0 || keepStartIndex < MIN_KEEP_MESSAGES) {
            return "";
        }

        List<Message> toSummarize = messages.subList(0, keepStartIndex);
        List<Message> toKeep = messages.subList(keepStartIndex, messages.size());

        String serialized = serializeForSummary(toSummarize, 500);
        String summaryRaw = requestSummary(client,
                SUMMARY_SYSTEM_PROMPT + "\n\n" + serialized);
        String summaryText = formatCompactSummary(summaryRaw);

        // Persist a compact_boundary record so a later resume can rebuild this
        // compacted state (summary + kept tail) instead of replaying the full
        // pre-compaction transcript. Append-only: the original prefix messages
        // stay in the file but won't be replayed past this boundary. The kept tail
        // is inlined as role+content text (matching how the session log already
        // stores messages — text only, no tool blocks). The boundary stores the
        // pure summary text, not the recovery attachment, since the recovery
        // snapshots are an in-memory rebuild aid unavailable on resume. Skipped
        // when sessionId/workDir is null/blank (tests, one-shot callers).
        if (workDir != null && !workDir.isBlank() && sessionId != null && !sessionId.isBlank()) {
            List<SessionManager.KeepMessage> keepRecords = new ArrayList<>(toKeep.size());
            for (Message m : toKeep) {
                keepRecords.add(new SessionManager.KeepMessage(m.getRole(), nullSafe(m.getContent())));
            }
            SessionManager.saveCompactBoundary(workDir, sessionId, summaryText, keepRecords);
        }

        String content = "本次会话延续自之前的对话，因上下文空间不足进行了压缩。以下是早期对话的摘要：\n\n" + summaryText;
        if (!toKeep.isEmpty()) {
            content += "\n\n近期消息已原样保留。";
        }
        if (workDir != null && !workDir.isBlank() && sessionId != null && !sessionId.isBlank()) {
            content += "\n\n如果你需要压缩前的具体细节（代码片段、报错信息等），请用 ReadFile 读取完整会话记录："
                    + Path.of(workDir, ".mewcode", "sessions", sessionId + ".jsonl");
        }
        String attachment = buildRecoveryAttachment(recovery, toolSchemas);
        if (!attachment.isEmpty()) {
            content += "\n\n---\n\n" + attachment;
        }

        // Rebuild = summary (user) + recent verbatim tail (no assistant ack).
        ConversationManager compacted = new ConversationManager();
        compacted.addUserMessage(content);
        for (Message m : toKeep) {
            appendMessage(compacted, m);
        }

        replaceConversation(conv, compacted);

        int afterTokens = estimateTokens(conv.getMessages());
        return String.format("Compacted: %d -> %d estimated tokens", beforeTokens, afterTokens);
    }

    // ── Post-compact recovery attachment ───────────────────────────────

    /**
     * Render the four-section recovery block that gets appended to the
     * summary user message. Returns "" when there is nothing worth
     * emitting so the caller can keep the summary clean.
     *
     * @param state        per-agent snapshots of recent file reads + skills
     * @param toolSchemas  the schemas the agent will send on the next request
     */
    public static String buildRecoveryAttachment(RecoveryState state,
                                                 List<Map<String, Object>> toolSchemas) {
        var sb = new StringBuilder();

        if (state != null) {
            var files = state.snapshotFiles(RECOVERY_FILE_LIMIT);
            if (!files.isEmpty()) {
                sb.append("## Recently read files\n\n")
                  .append("These snapshots are what the file-reading tool last returned. ")
                  .append("Re-open with the tool if you need the current bytes.\n\n");
                for (var f : files) {
                    String body = truncateByTokens(f.content(), RECOVERY_TOKENS_PER_FILE);
                    sb.append("### ").append(f.path())
                      .append("  (read ").append(RECOVERY_TS.format(f.timestamp())).append(")\n\n")
                      .append("```\n").append(body);
                    if (!body.endsWith("\n")) sb.append('\n');
                    sb.append("```\n\n");
                }
            }
        }

        if (state != null) {
            var skills = state.snapshotSkills();
            if (!skills.isEmpty()) {
                var section = new StringBuilder();
                section.append("## Active skills\n\n")
                       .append("These skills were invoked earlier in the session. ")
                       .append("Continue to follow each SOP when its triggering condition applies.\n\n");
                int used = 0;
                boolean emitted = false;
                for (var sk : skills) {
                    String body = truncateByTokens(sk.body(), RECOVERY_TOKENS_PER_SKILL);
                    int tokens = approxTokens(body) + approxTokens(sk.name()) + 8;
                    if (used + tokens > RECOVERY_SKILLS_BUDGET) break;
                    used += tokens;
                    section.append("### ").append(sk.name()).append("\n\n")
                           .append(body).append("\n\n");
                    emitted = true;
                }
                if (emitted) sb.append(section);
            }
        }

        if (toolSchemas != null && !toolSchemas.isEmpty()) {
            sb.append("## Available tools\n\n")
              .append("You still have access to the following tools — call them directly when the task needs one:\n\n");
            for (var t : toolSchemas) {
                if (t == null) continue;
                Object nameObj = t.get("name");
                if (nameObj == null) continue;
                String name = nameObj.toString();
                if (name.isEmpty()) continue;
                Object descObj = t.get("description");
                String desc = descObj == null ? "" : firstLine(descObj.toString());
                if (!desc.isEmpty()) {
                    sb.append("- ").append(name).append(" — ").append(desc).append('\n');
                } else {
                    sb.append("- ").append(name).append('\n');
                }
            }
            sb.append('\n');
        }

        if (sb.length() == 0) return "";

        sb.append("## Note\n\nEverything above the divider is reconstructed context. ")
          .append("For exact code, error strings, or user-typed text, re-read the source rather than ")
          .append("guess from the summary.\n");
        return sb.toString();
    }

    private static int approxTokens(String s) {
        if (s == null || s.isEmpty()) return 0;
        return (int) (s.length() / RECOVERY_CHARS_PER_TOKEN);
    }

    private static String truncateByTokens(String s, int tokenBudget) {
        if (s == null || s.isEmpty() || tokenBudget <= 0) return s == null ? "" : s;
        if (approxTokens(s) <= tokenBudget) return s;
        int maxChars = (int) (tokenBudget * RECOVERY_CHARS_PER_TOKEN);
        if (maxChars <= 0 || maxChars >= s.length()) return s;
        return s.substring(0, maxChars) + "\n… (content truncated)";
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        for (String line : s.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) return trimmed;
        }
        return "";
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static boolean alreadyProcessed(String s) {
        return s != null && (s.startsWith("[Result of ") || s.startsWith("[Stale output snipped:"));
    }

    private static Path writeSpill(String spillDir, String toolUseId, String content) {
        try {
            Path dir = Path.of(spillDir);
            Files.createDirectories(dir);
            Path file = dir.resolve(toolUseId);
            Files.writeString(file, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return file;
        } catch (FileAlreadyExistsException e) {
            return Path.of(spillDir).resolve(toolUseId);
        } catch (IOException e) {
            return null;
        }
    }

    static String formatCompactSummary(String raw) {
        int start = raw.indexOf("<summary>");
        int end = raw.indexOf("</summary>");
        if (start >= 0 && end > start) {
            return raw.substring(start + "<summary>".length(), end).strip();
        }
        return raw.strip();
    }

    private static String requestSummary(LlmClient client, String prompt) {
        ConversationManager summaryConv = new ConversationManager();
        summaryConv.addUserMessage(prompt);

        BlockingQueue<StreamEvent> events = client.stream(summaryConv, null);
        var summary = new StringBuilder();

        try {
            while (true) {
                StreamEvent ev = events.take();
                if (ev instanceof StreamEvent.TextDelta td) {
                    summary.append(td.text());
                } else if (ev instanceof StreamEvent.Error err) {
                    throw new RuntimeException("LLM summary failed: " + err.message());
                } else if (ev instanceof StreamEvent.StreamEnd) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Summary interrupted", e);
        }

        return summary.toString();
    }

    private static String serializeForSummary(List<Message> messages, int toolResultCap) {
        var sb = new StringBuilder();
        for (Message m : messages) {
            sb.append(String.format("[%s]: %s\n", m.getRole(), nullSafe(m.getContent())));

            if (m.getToolUses() != null) {
                for (ToolUseBlock tu : m.getToolUses()) {
                    sb.append(String.format("[tool_use %s]: %s\n", tu.toolName(), tu.toolUseId()));
                }
            }
            if (m.getToolResults() != null) {
                for (ToolResultBlock tr : m.getToolResults()) {
                    String content = nullSafe(tr.content());
                    if (content.length() > toolResultCap) {
                        content = content.substring(0, toolResultCap) + "...";
                    }
                    sb.append(String.format("[tool_result]: %s\n", content));
                }
            }
        }
        return sb.toString();
    }

    private static void appendMessage(ConversationManager conv, Message m) {
        if (m.getToolUses() != null && !m.getToolUses().isEmpty()) {
            conv.addAssistantFull(m.getContent(), m.getThinkingBlocks(), m.getToolUses());
        } else if (m.getToolResults() != null && !m.getToolResults().isEmpty()) {
            conv.addToolResultsMessage(m.getToolResults());
        } else if ("user".equals(m.getRole())) {
            conv.addUserMessage(m.getContent());
        } else if ("assistant".equals(m.getRole())) {
            conv.addAssistantFull(m.getContent(), m.getThinkingBlocks(), null);
        }
    }

    private static void rebuildConversation(ConversationManager conv, List<Message> messages) {
        ConversationManager rebuilt = new ConversationManager();
        for (Message m : messages) {
            appendMessage(rebuilt, m);
        }
        replaceConversation(conv, rebuilt);
    }

    private static void replaceConversation(ConversationManager target, ConversationManager source) {
        List<Message> targetList = target.getMessagesMutable();
        targetList.clear();
        targetList.addAll(source.getMessages());
    }

    private static int safeLength(String s) {
        return s == null ? 0 : s.length();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
