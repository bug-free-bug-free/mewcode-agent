// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.toolresult;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContentReplacementStateTest {

    @Test
    void newReturnsEmpty() {
        var s = new ContentReplacementState();
        assertTrue(s.seenIds().isEmpty());
        assertTrue(s.replacements().isEmpty());
    }

    @Test
    void copyIsIndependent() {
        var src = new ContentReplacementState();
        src.seenIds().add("a");
        src.replacements().put("a", "preview_a");

        var copy = src.copy();
        copy.seenIds().add("b");
        copy.replacements().put("b", "preview_b");

        assertFalse(src.seenIds().contains("b"), "source mutated through copy.seenIds");
        assertFalse(src.replacements().containsKey("b"), "source mutated through copy.replacements");
        assertTrue(copy.seenIds().contains("a"));
        assertEquals("preview_a", copy.replacements().get("a"));
    }
}

