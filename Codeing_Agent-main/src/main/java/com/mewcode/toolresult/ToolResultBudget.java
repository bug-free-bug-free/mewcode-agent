// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.toolresult;

import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.ToolResultBlock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Layer 1 tool-result budget — Design B. Walks the input conversation,
 * decides each tool_result's fate against {@link ContentReplacementState},
 * and returns a NEW {@link ConversationManager} with replacements applied.
 * The input conv is never mutated. {@code state.seenIds} /
 * {@code state.replacements} are mutated to record this turn's decisions;
 * subsequent calls re-apply those decisions verbatim (byte-identical
 * preview strings, no I/O) so the prompt-cache prefix stays stable across
 * turns.
 *
 * <p>This replaces the dead {@code ContextCompactor.applyToolResultBudget}
 * which (a) only handled single-result spill, (b) was never called from
 * the main loop, and (c) mutated the input conversation.
 */
public final class ToolResultBudget {

    /** Per-result spill threshold. */
    public static final int SINGLE_RESULT_LIMIT = 50_000;

    /** Per-message aggregate spill threshold. */
    public static final int MESSAGE_AGGREGATE_LIMIT = 200_000;

    /** Stale-tail snip threshold. */
    public static final int OLD_RESULT_SNIP_CHARS = 2_000;

    /** Recent-window boundary that stale-snip never touches. */
    public static final int KEEP_RECENT_TURNS = 10;

    /** Tool-results spill subdirectory relative to sessionDir. */
    public static final String SPILL_SUBDIR = "tool_results";

    private static final String PERSISTED_TAG_PREFIX = "[Result of ";
    private static final String SNIPPED_TAG_PREFIX = "[Stale output snipped:";

    private ToolResultBudget() {}

    public static ApplyResult apply(
            ConversationManager conv,
            Path sessionDir,
            ContentReplacementState state
    ) {
        List<Message> messages = conv.getMessages();
        if (messages.isEmpty()) {
            return new ApplyResult(conv, List.of());
        }

        Path spillDir = sessionDir.resolve(SPILL_SUBDIR);
        List<ContentReplacementRecord> records = new ArrayList<>();
        List<Message> newHistory = new ArrayList<>(messages.size());

        for (Message msg : messages) {
            List<ToolResultBlock> trs = msg.getToolResults();
            if (trs == null || trs.isEmpty()) {
                newHistory.add(msg);
                continue;
            }

            Map<String, String> decisions = new HashMap<>(trs.size() * 2);
            List<ToolResultBlock> fresh = new ArrayList<>();

            for (ToolResultBlock tr : trs) {
                String id = tr.toolUseId();
                String existing = state.replacements().get(id);
                if (existing != null) {
                    decisions.put(id, existing);
                    continue;
                }
                if (state.seenIds().contains(id)) {
                    decisions.put(id, tr.content());
                    continue;
                }
                if (isAlreadyReplaced(tr.content())) {
                    // External pre-tagged content — freeze as the tag itself.
                    state.seenIds().add(id);
                    state.replacements().put(id, tr.content());
                    decisions.put(id, tr.content());
                    records.add(ContentReplacementRecord.toolResult(id, tr.content()));
                    continue;
                }
                fresh.add(tr);
            }

            // Pass 1: persist any single result above SINGLE_RESULT_LIMIT.
            Set<String> persistedByP1 = new HashSet<>();
            for (ToolResultBlock tr : fresh) {
                if (tr.content().length() <= SINGLE_RESULT_LIMIT) continue;
                String preview = spillAndPreview(spillDir, tr);
                if (preview == null) {
                    // Spill failed — freeze as raw, never revisit.
                    state.seenIds().add(tr.toolUseId());
                    decisions.put(tr.toolUseId(), tr.content());
                    persistedByP1.add(tr.toolUseId());
                    continue;
                }
                decisions.put(tr.toolUseId(), preview);
                state.seenIds().add(tr.toolUseId());
                state.replacements().put(tr.toolUseId(), preview);
                records.add(ContentReplacementRecord.toolResult(tr.toolUseId(), preview));
                persistedByP1.add(tr.toolUseId());
            }

            // Pass 2: aggregate-spill the largest remaining fresh candidates
            // until total ≤ MESSAGE_AGGREGATE_LIMIT.
            List<ToolResultBlock> remaining = new ArrayList<>();
            for (ToolResultBlock tr : fresh) {
                if (!persistedByP1.contains(tr.toolUseId())) {
                    remaining.add(tr);
                }
            }

            int total = 0;
            for (String content : decisions.values()) total += content.length();
            for (ToolResultBlock tr : remaining) total += tr.content().length();

            if (total > MESSAGE_AGGREGATE_LIMIT && !remaining.isEmpty()) {
                List<ToolResultBlock> sorted = new ArrayList<>(remaining);
                sorted.sort(Comparator.comparingInt((ToolResultBlock t) -> t.content().length()).reversed());
                for (ToolResultBlock tr : sorted) {
                    if (total <= MESSAGE_AGGREGATE_LIMIT) break;
                    String preview = spillAndPreview(spillDir, tr);
                    if (preview == null) {
                        state.seenIds().add(tr.toolUseId());
                        decisions.put(tr.toolUseId(), tr.content());
                        continue;
                    }
                    decisions.put(tr.toolUseId(), preview);
                    state.seenIds().add(tr.toolUseId());
                    state.replacements().put(tr.toolUseId(), preview);
                    records.add(ContentReplacementRecord.toolResult(tr.toolUseId(), preview));
                    total -= tr.content().length() - preview.length();
                }
            }

            // Freeze remaining fresh as "seen but not replaced".
            for (ToolResultBlock tr : fresh) {
                if (decisions.containsKey(tr.toolUseId())) continue;
                state.seenIds().add(tr.toolUseId());
                decisions.put(tr.toolUseId(), tr.content());
            }

            // Materialize new tool_results in original order.
            List<ToolResultBlock> newResults = new ArrayList<>(trs.size());
            for (ToolResultBlock tr : trs) {
                newResults.add(new ToolResultBlock(
                        tr.toolUseId(),
                        decisions.get(tr.toolUseId()),
                        tr.isError()
                ));
            }
            newHistory.add(copyMessageWithResults(msg, newResults));
        }

        // Pass 3: stale-snip on the new history (stateless; boundary-crossing
        // drift is accepted as out-of-scope for this state machine).
        newHistory = snipStale(newHistory);

        return new ApplyResult(buildManager(newHistory), records);
    }

    private static final int PREVIEW_CHARS = 2_000;

    private static String buildSpillPreview(String content, Path path) {
        int sizeKB = content.length() / 1024;
        String preview = content.length() <= PREVIEW_CHARS
                ? content : content.substring(0, PREVIEW_CHARS);
        boolean hasMore = content.length() > PREVIEW_CHARS;
        StringBuilder sb = new StringBuilder();
        sb.append("<persisted-output>\n");
        sb.append("输出太大（").append(sizeKB).append("KB），完整内容已保存到：\n");
        sb.append(path).append("\n\n");
        sb.append("预览（前 2KB）：\n").append(preview);
        if (hasMore) sb.append("\n...");
        sb.append("\n</persisted-output>");
        return sb.toString();
    }

    private static String spillAndPreview(Path spillDir, ToolResultBlock tr) {
        try {
            Files.createDirectories(spillDir);
            Path file = spillDir.resolve(tr.toolUseId());
            if (Files.exists(file) && Files.size(file) == tr.content().length()) {
                return buildSpillPreview(tr.content(), file);
            }
            Files.writeString(file, tr.content());
            return buildSpillPreview(tr.content(), file);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isAlreadyReplaced(String s) {
        return s != null && (s.startsWith(PERSISTED_TAG_PREFIX) || s.startsWith(SNIPPED_TAG_PREFIX));
    }

    private static List<Message> snipStale(List<Message> messages) {
        int totalTurns = 0;
        for (Message m : messages) {
            if ("assistant".equals(m.getRole()) && (m.getToolUses() == null || m.getToolUses().isEmpty())) {
                totalTurns++;
            }
        }
        if (totalTurns <= KEEP_RECENT_TURNS) return messages;

        List<Message> out = new ArrayList<>(messages.size());
        int turnsSeen = 0;
        int oldBoundary = totalTurns - KEEP_RECENT_TURNS;

        for (Message m : messages) {
            if ("assistant".equals(m.getRole()) && (m.getToolUses() == null || m.getToolUses().isEmpty())) {
                turnsSeen++;
            }
            if (turnsSeen > oldBoundary || m.getToolResults() == null || m.getToolResults().isEmpty()) {
                out.add(m);
                continue;
            }
            List<ToolResultBlock> newResults = new ArrayList<>();
            boolean changed = false;
            for (ToolResultBlock tr : m.getToolResults()) {
                if (isAlreadyReplaced(tr.content()) || tr.content().length() <= OLD_RESULT_SNIP_CHARS) {
                    newResults.add(tr);
                    continue;
                }
                newResults.add(new ToolResultBlock(
                        tr.toolUseId(),
                        "[Stale output snipped: " + tr.content().length() + " chars]",
                        tr.isError()
                ));
                changed = true;
            }
            out.add(changed ? copyMessageWithResults(m, newResults) : m);
        }
        return out;
    }

    private static Message copyMessageWithResults(Message src, List<ToolResultBlock> newResults) {
        Message copy = new Message(src.getRole(), src.getContent());
        copy.setThinkingBlocks(src.getThinkingBlocks());
        copy.setToolUses(src.getToolUses());
        copy.setToolResults(newResults);
        return copy;
    }

    /**
     * Build a fresh ConversationManager from a message list. ConversationManager
     * exposes addToolResultsMessage/addAssistantFull/addUserMessage as the only
     * mutation points, so we replay through those to produce an independent
     * instance without touching the source's internal history list.
     */
    private static ConversationManager buildManager(List<Message> messages) {
        ConversationManager out = new ConversationManager();
        for (Message m : messages) {
            boolean hasToolUses = m.getToolUses() != null && !m.getToolUses().isEmpty();
            boolean hasToolResults = m.getToolResults() != null && !m.getToolResults().isEmpty();
            if (hasToolUses) {
                out.addAssistantFull(m.getContent(), m.getThinkingBlocks(), m.getToolUses());
            } else if (hasToolResults) {
                out.addToolResultsMessage(m.getToolResults());
            } else if ("user".equals(m.getRole())) {
                out.addUserMessage(m.getContent());
            } else if ("assistant".equals(m.getRole())) {
                out.addAssistantMessage(m.getContent());
            }
        }
        return out;
    }
}
