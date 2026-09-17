// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.compact;

import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.StreamEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

class ContextCompactorTest {

    /** Minimal stub: emits a fixed <summary> and ends, regardless of input. */
    private static final class StubSummaryClient implements LlmClient {
        @Override
        public BlockingQueue<StreamEvent> stream(ConversationManager conv,
                                                 List<Map<String, Object>> tools) {
            BlockingQueue<StreamEvent> q = new LinkedBlockingQueue<>();
            q.add(new StreamEvent.TextDelta(
                    "<summary>old prefix summarized</summary>"));
            q.add(new StreamEvent.StreamEnd("end_turn", 0, 0));
            return q;
        }
    }

    @Test
    void estimateTokensEmpty() {
        assertEquals(0, ContextCompactor.estimateTokens(List.of()));
    }

    @Test
    void estimateTokensWithContent() {
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("hello world");
        int tokens = ContextCompactor.estimateTokens(conv.getMessages());
        assertTrue(tokens > 0, "should estimate non-zero tokens for non-empty message");
    }

    @Test
    void currentTokensFallsBackToCharEstimateWhenNoAnchor() {
        // Cold start: no real usage yet, so currentTokens must equal the legacy
        // whole-conversation character estimate.
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("hello world");
        conv.addAssistantMessage("a reply with some content");

        int estimate = ContextCompactor.estimateTokens(conv.getMessages());
        int current = ContextCompactor.currentTokens(conv.getMessages(), null);
        assertEquals(estimate, current, "no anchor → fall back to whole-conversation estimate");
    }

    @Test
    void currentTokensUsesBaselinePlusIncrementWhenAnchored() {
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("first turn");          // index 0
        conv.addAssistantMessage("first response"); // index 1
        // Anchor after the first two messages with a real baseline far below any
        // character estimate (e.g. a cache hit reported a small real input).
        var anchor = new ContextCompactor.UsageAnchor(5_000, conv.size());

        // Two more messages appended after the anchor.
        conv.addUserMessage("second turn");
        conv.addAssistantMessage("second response");

        var appended = conv.getMessages().subList(2, conv.getMessages().size());
        int increment = ContextCompactor.estimateTokens(appended);

        int current = ContextCompactor.currentTokens(conv.getMessages(), anchor);
        assertEquals(5_000 + increment, current,
                "anchored → baseline plus estimate of only the appended messages");
        // And it must ignore the character cost of the pre-anchor messages.
        assertTrue(current < ContextCompactor.estimateTokens(conv.getMessages()) + 5_000);
    }

    @Test
    void currentTokensFallsBackWhenAnchorCountOutOfRange() {
        // A stale anchor whose count exceeds the (now compacted) message list must
        // not throw and must degrade to the whole-conversation estimate.
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("only message");

        var stale = new ContextCompactor.UsageAnchor(9_999, 50);
        int current = ContextCompactor.currentTokens(conv.getMessages(), stale);
        assertEquals(ContextCompactor.estimateTokens(conv.getMessages()), current,
                "out-of-range anchorCount → safe fallback to char estimate");
    }

    @Test
    void offloadSpillsLargeResult(@TempDir Path tempDir) {
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("run a command");

        String bigContent = "x".repeat(20_000);
        conv.addToolResultsMessage(List.of(
                new ToolResultBlock("tu-1", bigContent, false)));

        String result = ContextCompactor.offloadAndSnip(conv, tempDir.toString());
        assertFalse(result.isEmpty(), "should report spill activity");

        String content = conv.getMessages().get(1).getToolResults().get(0).content();
        assertTrue(content.startsWith("[Result of "), "result should be replaced with stub");

        Path spillFile = tempDir.resolve(".mewcode/tool_results/tu-1");
        assertTrue(Files.exists(spillFile), "spill file should exist on disk");
    }

    @Test
    void offloadIsIdempotent(@TempDir Path tempDir) {
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("run");
        conv.addToolResultsMessage(List.of(
                new ToolResultBlock("tu-2", "y".repeat(20_000), false)));

        ContextCompactor.offloadAndSnip(conv, tempDir.toString());
        String secondResult = ContextCompactor.offloadAndSnip(conv, tempDir.toString());

        assertEquals("", secondResult, "second pass should be a no-op");
    }

    @Test
    void snipsStaleResults(@TempDir Path tempDir) {
        ConversationManager conv = new ConversationManager();

        // Add one old message with a large-ish (but under spill limit) tool result
        conv.addUserMessage("old task");
        conv.addToolResultsMessage(List.of(
                new ToolResultBlock("tu-old", "z".repeat(3_000), false)));

        // KEEP_RECENT_TURNS*3 = 30 messages; need >30 after the old ones
        for (int i = 0; i < 16; i++) {
            conv.addUserMessage("turn " + i);
            conv.addAssistantMessage("reply " + i);
        }

        String result = ContextCompactor.offloadAndSnip(conv, tempDir.toString());
        assertFalse(result.isEmpty(), "should report snip activity");

        String content = conv.getMessages().get(1).getToolResults().get(0).content();
        assertTrue(content.startsWith("[Stale output snipped:"),
                "old result should be snipped, got: " + content);
    }

    @Test
    void doesNotSnipRecentResults(@TempDir Path tempDir) {
        ConversationManager conv = new ConversationManager();

        // Only a few turns, so everything is "recent"
        conv.addUserMessage("task");
        String original = "z".repeat(3_000);
        conv.addToolResultsMessage(List.of(
                new ToolResultBlock("tu-recent", original, false)));
        conv.addUserMessage("follow up");
        conv.addAssistantMessage("ok");

        String result = ContextCompactor.offloadAndSnip(conv, tempDir.toString());
        assertEquals("", result, "should not snip recent results");

        String content = conv.getMessages().get(1).getToolResults().get(0).content();
        assertEquals(original, content, "recent result should be untouched");
    }

    @Test
    void formatCompactSummaryExtractsSummaryTag() {
        String raw = "<analysis>some analysis</analysis>\n<summary>the real summary</summary>";
        assertEquals("the real summary", ContextCompactor.formatCompactSummary(raw));
    }

    @Test
    void formatCompactSummaryFallback() {
        String raw = "no tags here, just plain text";
        assertEquals(raw, ContextCompactor.formatCompactSummary(raw));
    }

    // ── messagesToKeep window ──────────────────────────────────────────

    @Test
    void keepStartReturnsZeroWhenEverythingFitsInKeepWindow() {
        // Fewer than MIN_KEEP_MESSAGES messages → the whole thing is the keep
        // window, nothing left to summarize.
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("one");
        conv.addAssistantMessage("two");
        assertEquals(0, ContextCompactor.computeKeepStartIndex(conv.getMessages()));
    }

    @Test
    void keepStartLeavesAtLeastMinKeepMessagesForSummarizablePrefix() {
        // Many small messages: MIN_KEEP_MESSAGES (=5) is the floor that trips
        // first, so the keep window is exactly the last 5 messages.
        ConversationManager conv = new ConversationManager();
        for (int i = 0; i < 20; i++) {
            conv.addUserMessage("u" + i);
            conv.addAssistantMessage("a" + i);
        }
        int n = conv.size();
        int keepStart = ContextCompactor.computeKeepStartIndex(conv.getMessages());
        assertEquals(n - 5, keepStart,
                "small messages → MIN_KEEP_MESSAGES floor keeps exactly the last 5");
    }

    @Test
    void compactKeepsRecentMessagesVerbatim() {
        ConversationManager conv = new ConversationManager();
        // Old prefix that will be summarized away.
        for (int i = 0; i < 12; i++) {
            conv.addUserMessage("old user msg " + i + " " + "x".repeat(200));
            conv.addAssistantMessage("old reply " + i + " " + "y".repeat(200));
        }
        // Recent verbatim tail with distinctive markers.
        conv.addUserMessage("RECENT_MARKER_ALPHA latest question");
        conv.addAssistantMessage("RECENT_MARKER_BETA latest answer");

        String result = ContextCompactor.forceCompact(
                conv, new StubSummaryClient(), 100_000, null, null);
        assertFalse(result.isEmpty(), "compaction should have run");

        List<Message> after = conv.getMessages();
        // Summary user message must lead (no assistant ack after it).
        assertTrue(after.get(0).getContent().contains("本次会话延续自之前的对话"));
        assertTrue(after.get(0).getContent().contains("old prefix summarized"));

        // The recent originals must survive verbatim — not be replaced by the summary.
        String joined = after.stream().map(Message::getContent).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(joined.contains("RECENT_MARKER_ALPHA latest question"),
                "recent user message must be kept verbatim");
        assertTrue(joined.contains("RECENT_MARKER_BETA latest answer"),
                "recent assistant message must be kept verbatim");
    }

    @Test
    void compactDoesNotSplitToolUseToolResultPair() {
        ConversationManager conv = new ConversationManager();
        // Filler prefix so there is something to summarize.
        for (int i = 0; i < 12; i++) {
            conv.addUserMessage("filler " + i + " " + "x".repeat(300));
            conv.addAssistantMessage("reply " + i + " " + "y".repeat(300));
        }
        // A tool_use / tool_result pair right at the tail boundary.
        conv.addAssistantFull("calling tool", null,
                List.of(new ToolUseBlock("tu-pair", "ReadFile", Map.of("path", "/x"))));
        conv.addToolResultsMessage(List.of(
                new ToolResultBlock("tu-pair", "z".repeat(1_000), false)));
        conv.addUserMessage("after the tool");
        conv.addAssistantMessage("done");

        int keepStart = ContextCompactor.computeKeepStartIndex(conv.getMessages());
        Message boundary = conv.getMessages().get(keepStart);
        // The keep window must never START on a tool_result-only user message,
        // which would orphan it from its assistant tool_use.
        boolean isOrphanResult = "user".equals(boundary.getRole())
                && boundary.getToolResults() != null
                && !boundary.getToolResults().isEmpty();
        assertFalse(isOrphanResult,
                "keepStart must not land on an orphaned tool_result message");

        // After compaction every kept tool_result must have its tool_use kept too.
        ContextCompactor.forceCompact(conv, new StubSummaryClient(), 100_000, null, null);
        assertToolPairsBalanced(conv.getMessages());
    }

    @Test
    void summaryCoversOnlyPrefixNotKeptTail() {
        ConversationManager conv = new ConversationManager();
        for (int i = 0; i < 12; i++) {
            conv.addUserMessage("PREFIX_ONLY " + i + " " + "x".repeat(200));
            conv.addAssistantMessage("prefix reply " + i + " " + "y".repeat(200));
        }
        conv.addUserMessage("KEPT_TAIL question");
        conv.addAssistantMessage("KEPT_TAIL answer");

        int keepStartBefore = ContextCompactor.computeKeepStartIndex(conv.getMessages());
        List<Message> before = conv.getMessages();
        // Serialized payload handed to the LLM must include only the prefix.
        List<Message> prefix = before.subList(0, keepStartBefore);
        List<Message> kept = before.subList(keepStartBefore, before.size());
        assertTrue(prefix.stream().anyMatch(m -> m.getContent() != null
                && m.getContent().contains("PREFIX_ONLY")));
        assertTrue(kept.stream().anyMatch(m -> m.getContent() != null
                && m.getContent().contains("KEPT_TAIL")),
                "the kept tail must sit outside the summarized prefix");

        ContextCompactor.forceCompact(conv, new StubSummaryClient(), 100_000, null, null);
        // Kept tail still present verbatim after compaction.
        String joined = conv.getMessages().stream()
                .map(Message::getContent).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(joined.contains("KEPT_TAIL question"));
        assertTrue(joined.contains("KEPT_TAIL answer"));
    }

    @Test
    void compactDegradesToNoOpWhenTooFewMessages() {
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("only a couple");
        conv.addAssistantMessage("of messages here");

        int sizeBefore = conv.size();
        String result = ContextCompactor.forceCompact(
                conv, new StubSummaryClient(), 100_000, null, null);
        assertEquals("", result, "too few messages → no-op, no summary round-trip");
        assertEquals(sizeBefore, conv.size(), "conversation must be left untouched");
        assertEquals("only a couple", conv.getMessages().get(0).getContent());
    }

    /** Assert every tool_result in the list has a matching earlier tool_use. */
    private static void assertToolPairsBalanced(List<Message> messages) {
        java.util.Set<String> seenToolUse = new java.util.HashSet<>();
        for (Message m : messages) {
            if (m.getToolUses() != null) {
                for (ToolUseBlock tu : m.getToolUses()) {
                    seenToolUse.add(tu.toolUseId());
                }
            }
            if (m.getToolResults() != null) {
                for (ToolResultBlock tr : m.getToolResults()) {
                    assertTrue(seenToolUse.contains(tr.toolUseId()),
                            "tool_result " + tr.toolUseId()
                                    + " has no preceding tool_use (orphaned pair)");
                }
            }
        }
    }

    @Test
    void circuitBreakerTripsAfterThreeFailures() {
        var tracking = new ContextCompactor.AutoCompactTrackingState();
        assertFalse(tracking.isTripped());

        tracking.recordFailure();
        tracking.recordFailure();
        assertFalse(tracking.isTripped());

        tracking.recordFailure();
        assertTrue(tracking.isTripped());

        tracking.reset();
        assertFalse(tracking.isTripped());
    }
}

