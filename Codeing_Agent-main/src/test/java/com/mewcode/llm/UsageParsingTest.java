// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers usage parsing for the real-token compaction anchor: the StreamEnd
 * record carries cache read/creation buckets, and OpenAI-compatible usage is
 * split so the cached portion is not double-counted in the anchor sum.
 */
class UsageParsingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void streamEndDefaultsCacheFieldsToZero() {
        // The 3-arg constructor (used by providers without a cache breakdown)
        // must leave both cache buckets at 0.
        var end = new StreamEvent.StreamEnd("end_turn", 100, 20);
        assertEquals(0, end.cacheReadTokens());
        assertEquals(0, end.cacheCreationTokens());
    }

    @Test
    void streamEndCarriesCacheFields() {
        var end = new StreamEvent.StreamEnd("end_turn", 100, 20, 4_000, 1_500);
        assertEquals(100, end.inputTokens());
        assertEquals(20, end.outputTokens());
        assertEquals(4_000, end.cacheReadTokens());
        assertEquals(1_500, end.cacheCreationTokens());
    }

    @Test
    void openAiCompatUsageSplitsCachedFromPrompt() {
        // prompt_tokens includes cached_tokens; extractUsage must split them so
        // input + cacheRead reconstructs the original prompt total exactly once.
        JsonNode root = parse("""
                {"usage":{"prompt_tokens":10000,"completion_tokens":500,
                          "prompt_tokens_details":{"cached_tokens":7000}}}""");
        int[] usage = OpenAiCompatClient.extractUsage(root);
        assertEquals(3_000, usage[0], "input = prompt - cached");
        assertEquals(500, usage[1], "output");
        assertEquals(7_000, usage[2], "cacheRead from prompt_tokens_details");
        assertEquals(10_000, usage[0] + usage[2], "no double-count of the cache hit");
    }

    @Test
    void openAiCompatUsageDefaultsCacheToZeroWhenAbsent() {
        JsonNode root = parse("""
                {"usage":{"prompt_tokens":800,"completion_tokens":40}}""");
        int[] usage = OpenAiCompatClient.extractUsage(root);
        assertEquals(800, usage[0]);
        assertEquals(40, usage[1]);
        assertEquals(0, usage[2], "no cached_tokens → cacheRead is 0");
    }

    @Test
    void openAiCompatUsageMissingReturnsZeros() {
        int[] usage = OpenAiCompatClient.extractUsage(parse("{}"));
        assertArrayEquals(new int[]{0, 0, 0}, usage);
    }
}
