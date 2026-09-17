// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.llm;

import com.mewcode.config.ProviderConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies layer 2 (auto-fetch) degrades gracefully: pointed at an
 * unreachable endpoint, {@code fetchModelContextWindow()} must return 0
 * without throwing, construction must not blow up, and the config's resolved
 * window must fall back to the built-in table.
 *
 * <p>We don't have a live Anthropic endpoint in tests (the smoke-test config
 * uses an OpenAI-compatible proxy that returns nothing useful here), so we
 * point at a bogus base URL — which is exactly the failure mode the
 * degradation path must survive.
 */
class AnthropicClientContextWindowTest {

    private static ProviderConfig anthropicCfg(String baseUrl) {
        var cfg = new ProviderConfig();
        cfg.setProtocol("anthropic");
        cfg.setBaseUrl(baseUrl);
        cfg.setModel("claude-sonnet-4-6");
        cfg.setApiKey("sk-test-not-a-real-key"); // non-empty so the ctor proceeds
        return cfg;
    }

    @Test
    void constructionDoesNotThrowWhenFetchFails() {
        // 127.0.0.1:1 is a closed port → connection refused quickly.
        var cfg = anthropicCfg("http://127.0.0.1:1");
        assertDoesNotThrow(() -> new AnthropicClient(cfg, "system"));
    }

    @Test
    void fetchReturnsZeroOnUnreachableEndpoint() {
        var cfg = anthropicCfg("http://127.0.0.1:1");
        var client = new AnthropicClient(cfg, "system");
        // Best-effort fetch must yield 0 (unavailable), never throw.
        assertEquals(0, client.fetchModelContextWindow());
    }

    @Test
    void resolvedWindowFallsBackToTableWhenFetchFails() {
        var cfg = anthropicCfg("http://127.0.0.1:1");
        // Constructing the client triggers the (failing) auto-fetch + backfill.
        new AnthropicClient(cfg, "system");
        // Cache stayed empty → resolution drops to the built-in table (claude → 200k).
        assertEquals(200_000, cfg.resolvedContextWindow());
    }

    @Test
    void configOverrideStillWinsEvenWithFailedFetch() {
        var cfg = anthropicCfg("http://127.0.0.1:1");
        cfg.setContextWindow(50_000);
        new AnthropicClient(cfg, "system");
        assertEquals(50_000, cfg.resolvedContextWindow());
    }
}
