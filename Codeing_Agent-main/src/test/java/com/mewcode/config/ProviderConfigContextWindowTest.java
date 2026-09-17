// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the four-layer context-window resolution in {@link ProviderConfig}:
 * <ol>
 *   <li>explicit config {@code context_window} wins;</li>
 *   <li>auto-fetched value (cached) used when no config override;</li>
 *   <li>built-in model→window table;</li>
 *   <li>conservative default.</li>
 * </ol>
 * Plus the graceful-degradation contract: a failed fetch (cache never set, or
 * set to a non-positive value) must fall through to the table, never crash.
 */
class ProviderConfigContextWindowTest {

    private static ProviderConfig cfg(String model) {
        var c = new ProviderConfig();
        c.setModel(model);
        return c;
    }

    // ---- Layer 1: explicit config override is highest priority ----

    @Test
    void configContextWindowWinsOverEverything() {
        var c = cfg("claude-sonnet-4-6");
        c.setContextWindow(12_345);
        // Even if a fetched value is present, config still wins.
        c.setFetchedContextWindow(999_999);
        assertEquals(12_345, c.resolvedContextWindow());
    }

    @Test
    void configContextWindowWinsForUnknownModel() {
        var c = cfg("some-exotic-model");
        c.setContextWindow(64_000);
        assertEquals(64_000, c.resolvedContextWindow());
    }

    // ---- Layer 2: auto-fetched value, cached ----

    @Test
    void fetchedValueUsedWhenNoConfigOverride() {
        var c = cfg("claude-sonnet-4-6"); // table would say 200k
        c.setFetchedContextWindow(321_000);
        assertEquals(321_000, c.resolvedContextWindow());
    }

    @Test
    void fetchedNonPositiveIsIgnoredAndFallsThrough() {
        var c = cfg("gpt-4o"); // table → 128k
        // Simulate a failed/empty fetch: must not poison the cache.
        c.setFetchedContextWindow(0);
        c.setFetchedContextWindow(-1);
        assertEquals(128_000, c.resolvedContextWindow());
    }

    // ---- Layer 3: built-in model→window table (substring match) ----

    @Test
    void tableMatchesEachModelToExpectedWindow() {
        // 1M-context variants (substring "1m")
        assertEquals(1_000_000, ProviderConfig.windowForModel("claude-sonnet-4-6-1m"));
        assertEquals(1_000_000, ProviderConfig.windowForModel("some-model-1m-preview"));
        // gpt-4.1 family
        assertEquals(1_000_000, ProviderConfig.windowForModel("gpt-4.1-mini"));
        // gpt-4o
        assertEquals(128_000, ProviderConfig.windowForModel("gpt-4o"));
        assertEquals(128_000, ProviderConfig.windowForModel("gpt-4o-mini"));
        // gpt-4-turbo
        assertEquals(128_000, ProviderConfig.windowForModel("gpt-4-turbo-2024-04-09"));
        // reasoning models o1/o3/o4
        assertEquals(200_000, ProviderConfig.windowForModel("o1-preview"));
        assertEquals(200_000, ProviderConfig.windowForModel("o3-mini"));
        assertEquals(200_000, ProviderConfig.windowForModel("o4-mini"));
        // gpt-3.5
        assertEquals(16_385, ProviderConfig.windowForModel("gpt-3.5-turbo"));
        // claude
        assertEquals(200_000, ProviderConfig.windowForModel("claude-opus-4-6"));
        // case-insensitive
        assertEquals(200_000, ProviderConfig.windowForModel("Claude-Haiku"));
    }

    @Test
    void oneMillionBeatsMoreGenericMatches() {
        // A "1m" claude variant must resolve to 1M, not the generic claude 200k.
        assertEquals(1_000_000, ProviderConfig.windowForModel("claude-sonnet-4-6-1m"));
        // gpt-4.1 still 1M even though it also contains "gpt-4".
        assertEquals(1_000_000, ProviderConfig.windowForModel("gpt-4.1"));
    }

    // ---- Layer 4: conservative default ----

    @Test
    void defaultsWhenNothingMatches() {
        assertEquals(128_000, ProviderConfig.windowForModel("totally-unknown-llm"));
        assertEquals(128_000, ProviderConfig.windowForModel(null));
        assertEquals(128_000, ProviderConfig.windowForModel(""));
    }

    @Test
    void resolveFallsBackToTableThenDefaultWithoutFetch() {
        // No config override, no fetched value → table.
        assertEquals(200_000, cfg("claude-opus-4-6").resolvedContextWindow());
        // No config, no fetch, no table hit → default.
        assertEquals(128_000, cfg("mystery-model").resolvedContextWindow());
    }
}
