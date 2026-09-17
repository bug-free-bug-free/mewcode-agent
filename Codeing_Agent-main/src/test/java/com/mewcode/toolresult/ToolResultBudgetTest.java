// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.toolresult;

import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.ToolResultBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultBudgetTest {

    private static String repeat(String c, int n) {
        return c.repeat(n);
    }

    private static ConversationManager oneToolResultMsg(ToolResultBlock... results) {
        var conv = new ConversationManager();
        conv.addToolResultsMessage(List.of(results));
        return conv;
    }

    @Test
    void applyDoesNotMutateConv(@TempDir Path dir) {
        String big = repeat("x", ToolResultBudget.SINGLE_RESULT_LIMIT + 100);
        var conv = oneToolResultMsg(new ToolResultBlock("t1", big, false));
        var state = new ContentReplacementState();

        String origContent = conv.getMessages().get(0).getToolResults().get(0).content();

        ApplyResult result = ToolResultBudget.apply(conv, dir, state);
        assertNotSame(conv, result.apiConv(), "Design B requires a fresh ConversationManager");

        String stillOrig = conv.getMessages().get(0).getToolResults().get(0).content();
        assertEquals(origContent, stillOrig, "original conv must not be mutated");

        String apiContent = result.apiConv().getMessages().get(0).getToolResults().get(0).content();
        assertTrue(apiContent.startsWith("[Result of "), "api_conv must carry the spill preview");
    }

    @Test
    void firstCallFreezesUnreplaced(@TempDir Path dir) {
        String small = repeat("y", 100);
        var conv = oneToolResultMsg(new ToolResultBlock("t1", small, false));
        var state = new ContentReplacementState();

        ApplyResult result = ToolResultBudget.apply(conv, dir, state);
        assertTrue(state.seenIds().contains("t1"));
        assertFalse(state.replacements().containsKey("t1"));
        assertTrue(result.newRecords().isEmpty());
    }

    @Test
    void replacementByteIdentical(@TempDir Path dir) {
        String big = repeat("z", ToolResultBudget.SINGLE_RESULT_LIMIT + 200);
        var conv = oneToolResultMsg(new ToolResultBlock("t_big", big, false));
        var state = new ContentReplacementState();

        ApplyResult r1 = ToolResultBudget.apply(conv, dir, state);
        ApplyResult r2 = ToolResultBudget.apply(conv, dir, state);

        String c1 = r1.apiConv().getMessages().get(0).getToolResults().get(0).content();
        String c2 = r2.apiConv().getMessages().get(0).getToolResults().get(0).content();
        assertEquals(c1, c2, "second pass must produce byte-identical content");
        assertEquals(1, r1.newRecords().size());
        assertTrue(r2.newRecords().isEmpty(), "re-apply must not produce new records");
        assertEquals(c1, state.replacements().get("t_big"));
    }

    @Test
    void frozenNeverReplaced(@TempDir Path dir) {
        // Turn 1: a single result well under the aggregate budget.
        int quarter = ToolResultBudget.MESSAGE_AGGREGATE_LIMIT / 4;
        var first = new ToolResultBlock("t1", repeat("a", quarter), false);
        var conv = oneToolResultMsg(first);
        var state = new ContentReplacementState();
        ToolResultBudget.apply(conv, dir, state);
        assertFalse(state.replacements().containsKey("t1"));

        // Turn 2: force the same message to contain a NEW huge result that
        // blows past the single-result limit. t1 must remain raw because its
        // decision was frozen at turn 1, even though the message is now over
        // budget.
        var huge = new ToolResultBlock("t2",
                repeat("b", ToolResultBudget.SINGLE_RESULT_LIMIT + 200), false);
        var conv2 = oneToolResultMsg(first, huge);

        ApplyResult result = ToolResultBudget.apply(conv2, dir, state);
        String t1Content = result.apiConv().getMessages().get(0).getToolResults().stream()
                .filter(tr -> tr.toolUseId().equals("t1"))
                .findFirst().orElseThrow().content();
        assertEquals(first.content(), t1Content, "t1 must stay raw — its decision was frozen");
        assertFalse(state.replacements().containsKey("t1"),
                "t1 must never enter Replacements after being frozen");
    }

    @Test
    void aggregateOnlyPicksFresh(@TempDir Path dir) {
        int bigUnder = ToolResultBudget.SINGLE_RESULT_LIMIT - 1;
        var results = new ArrayList<ToolResultBlock>();
        for (String id : new String[]{"t1", "t2", "t3", "t4", "t5"}) {
            results.add(new ToolResultBlock(id, repeat("a", bigUnder), false));
        }
        var conv = new ConversationManager();
        conv.addToolResultsMessage(results);
        var state = new ContentReplacementState();

        ApplyResult result = ToolResultBudget.apply(conv, dir, state);
        int total = 0;
        for (var tr : result.apiConv().getMessages().get(0).getToolResults()) {
            total += tr.content().length();
        }
        assertTrue(total <= ToolResultBudget.MESSAGE_AGGREGATE_LIMIT,
                "api_conv aggregate %d exceeds limit %d".formatted(
                        total, ToolResultBudget.MESSAGE_AGGREGATE_LIMIT));
        assertFalse(result.newRecords().isEmpty());
        for (String id : new String[]{"t1", "t2", "t3", "t4", "t5"}) {
            assertTrue(state.seenIds().contains(id), id + " missing from SeenIDs");
        }
    }

    @Test
    void reconstructFromRecords() {
        var msg = new Message("user", "");
        msg.setToolResults(List.of(
                new ToolResultBlock("t1", "raw", false),
                new ToolResultBlock("t2", "raw", false)
        ));
        var records = List.of(
                ContentReplacementRecord.toolResult("t1", "t1_preview")
        );
        var state = ContentReplacementLifecycle.reconstruct(List.of(msg), records, null);
        assertTrue(state.seenIds().contains("t1"));
        assertTrue(state.seenIds().contains("t2"));
        assertEquals("t1_preview", state.replacements().get("t1"));
        assertFalse(state.replacements().containsKey("t2"));
    }

    @Test
    void reconstructWithInheritedParent() {
        var msg = new Message("user", "");
        msg.setToolResults(List.of(
                new ToolResultBlock("t_parent", "raw", false),
                new ToolResultBlock("t_child", "raw", false)
        ));
        var records = List.of(
                ContentReplacementRecord.toolResult("t_child", "child_preview")
        );
        var inherited = Map.of("t_parent", "parent_preview");
        var state = ContentReplacementLifecycle.reconstruct(List.of(msg), records, inherited);
        assertEquals("child_preview", state.replacements().get("t_child"));
        assertEquals("parent_preview", state.replacements().get("t_parent"));
    }
}

