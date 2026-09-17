// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.compact;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryAttachmentTest {

    @Test
    void emptyWhenNothingRecorded() {
        assertEquals("", ContextCompactor.buildRecoveryAttachment(null, null));
        assertEquals("", ContextCompactor.buildRecoveryAttachment(new RecoveryState(), List.of()));
    }

    @Test
    void emitsAllSections() {
        var state = new RecoveryState();
        state.recordFileRead("/tmp/a.java", "class A {}\n");
        state.recordSkillInvocation("planner", "step 1\nstep 2\n");
        var schemas = List.<Map<String, Object>>of(
                Map.of("name", "ReadFile",
                       "description", "Read a file and return contents.\nWith line numbers."),
                Map.of("name", "Bash", "description", ""));

        String out = ContextCompactor.buildRecoveryAttachment(state, schemas);

        assertTrue(out.contains("/tmp/a.java"), "expected file path in attachment");
        assertTrue(out.contains("planner"), "expected skill name");
        assertTrue(out.contains("- ReadFile — Read a file and return contents."),
                "expected tool line with first-line description");
        assertTrue(out.contains("- Bash"), "expected bare tool line without description");
        assertTrue(out.contains("Note"), "expected closing note section");
    }

    @Test
    void fileLimitAndNewestFirst() throws Exception {
        var state = new RecoveryState();
        for (int i = 0; i < 7; i++) {
            state.recordFileRead("/f" + i, "x");
        }
        // Force deterministic timestamps via reflection so ordering is observable.
        Field filesField = RecoveryState.class.getDeclaredField("files");
        filesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var files = (Map<String, RecoveryState.FileReadRecord>) filesField.get(state);
        long base = 1_000_000L;
        for (int i = 0; i < 7; i++) {
            var key = "/f" + i;
            files.put(key, new RecoveryState.FileReadRecord(key, "x", Instant.ofEpochSecond(base + i)));
        }

        var snapshot = state.snapshotFiles(ContextCompactor.RECOVERY_FILE_LIMIT);
        assertEquals(5, snapshot.size());
        assertEquals("/f6", snapshot.get(0).path(), "newest first");
        assertEquals("/f2", snapshot.get(snapshot.size() - 1).path());
    }

    @Test
    void truncatesPerFile() {
        int charBudget = (int) (ContextCompactor.RECOVERY_TOKENS_PER_FILE * 3.5 * 3);
        String huge = "x".repeat(charBudget);
        var state = new RecoveryState();
        state.recordFileRead("/big", huge);
        String out = ContextCompactor.buildRecoveryAttachment(state, null);
        assertTrue(out.contains("(content truncated)"));
    }

    @Test
    void skillBudget() throws Exception {
        var state = new RecoveryState();
        int bodyChars = (int) (ContextCompactor.RECOVERY_TOKENS_PER_SKILL * 3.5);
        String body = "y".repeat(bodyChars);
        for (int i = 0; i < 6; i++) {
            state.recordSkillInvocation("skill-" + i, body);
        }
        // Pin timestamps so newest-first ordering is deterministic.
        Field skillsField = RecoveryState.class.getDeclaredField("skills");
        skillsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var skills = (Map<String, RecoveryState.SkillInvocationRecord>) skillsField.get(state);
        long base = 1_000_000L;
        for (int i = 0; i < 6; i++) {
            var name = "skill-" + i;
            skills.put(name, new RecoveryState.SkillInvocationRecord(name, body, Instant.ofEpochSecond(base + i)));
        }

        String out = ContextCompactor.buildRecoveryAttachment(state, null);
        // 25K / 5K per skill ⇒ at most 5.
        int emitted = 0;
        int idx = 0;
        while ((idx = out.indexOf("### skill-", idx)) >= 0) {
            emitted++;
            idx++;
        }
        assertTrue(emitted >= 1 && emitted <= 5, "emitted " + emitted + " skills, expected 1..5");
    }
}

