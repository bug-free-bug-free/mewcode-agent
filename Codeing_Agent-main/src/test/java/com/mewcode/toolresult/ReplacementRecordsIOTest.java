// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.toolresult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplacementRecordsIOTest {

    @Test
    void appendAndLoadRoundtrip(@TempDir Path dir) throws Exception {
        var first = List.of(
                ContentReplacementRecord.toolResult("a", "aaa"),
                ContentReplacementRecord.toolResult("b", "bbb")
        );
        ReplacementRecordsIO.append(dir, first);
        ReplacementRecordsIO.append(dir, List.of(
                ContentReplacementRecord.toolResult("c", "ccc")
        ));

        var loaded = ReplacementRecordsIO.load(dir);
        assertEquals(3, loaded.size());
        assertEquals("a", loaded.get(0).toolUseId());
        assertEquals("b", loaded.get(1).toolUseId());
        assertEquals("c", loaded.get(2).toolUseId());
        for (var r : loaded) {
            assertEquals(ContentReplacementRecord.KIND_TOOL_RESULT, r.kind());
        }
    }

    @Test
    void loadMissingFile(@TempDir Path dir) throws Exception {
        var loaded = ReplacementRecordsIO.load(dir);
        assertTrue(loaded.isEmpty());
    }
}

