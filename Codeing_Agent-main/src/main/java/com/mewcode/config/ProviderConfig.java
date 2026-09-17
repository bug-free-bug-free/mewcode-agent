// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.config;

import java.util.Map;

public class ProviderConfig {

    private static final Map<String, String> ENV_KEY_MAP = Map.of(
            "anthropic", "ANTHROPIC_API_KEY",
            "openai", "OPENAI_API_KEY",
            "openai-compat", "OPENAI_API_KEY"
    );

    private String name;
    private String protocol;
    private String baseUrl;
    private String model;
    private String apiKey;
    private boolean thinking;

    private int contextWindow;
    private int maxOutputTokens;

    /**
     * Layer-2 cache: context window auto-fetched from the provider's
     * {@code /v1/models/{model}} endpoint. Backfilled once when the LLM client
     * is constructed (see {@code AnthropicClient}); {@code null} means "not yet
     * fetched / fetch failed". Kept here so {@link #resolvedContextWindow()},
     * which has no client of its own, can read the result.
     */
    private volatile Integer fetchedContextWindow;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getProtocol() { return protocol; }

    public void setProtocol(String protocol) { this.protocol = protocol; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public boolean isThinking() { return thinking; }
    public void setThinking(boolean thinking) { this.thinking = thinking; }

    public int getContextWindow() { return contextWindow; }

    public void setContextWindow(int contextWindow) { this.contextWindow = contextWindow; }

    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }

    /**
     * Records the context window auto-fetched from the provider (layer 2).
     * Pass a value &gt; 0 to cache it; anything else is ignored so a failed
     * fetch never poisons the cache. Called once at client-construction time.
     */
    public void setFetchedContextWindow(int window) {
        if (window > 0) this.fetchedContextWindow = window;
    }

    /**
     * Resolve the effective context window with four layers of fallback,
     * highest priority first:
     *
     * <ol>
     *   <li>Hand-written {@code context_window} from config (&gt; 0) — always wins.</li>
     *   <li>Value auto-fetched from the provider's models endpoint and cached
     *       via {@link #setFetchedContextWindow(int)} (Anthropic protocol only;
     *       the fetch itself is best-effort and silently degrades on failure).</li>
     *   <li>Built-in model-name → window table (substring match, {@link #windowForModel}).</li>
     *   <li>Conservative default (200k for Claude, 128k otherwise).</li>
     * </ol>
     */
    public int resolvedContextWindow() {
        // Layer 1: explicit config override.
        if (contextWindow > 0) return contextWindow;
        // Layer 2: auto-fetched from the provider (cached at client creation).
        Integer fetched = fetchedContextWindow;
        if (fetched != null && fetched > 0) return fetched;
        // Layers 3 + 4: built-in table, then conservative default.
        return windowForModel(model);
    }

    /**
     * Built-in "model name → context window" lookup (layers 3 and 4).
     * Matches by substring, from most specific to most generic. The values are
     * sensible starting points only — they may drift as vendors update models,
     * so when a value is wrong set {@code context_window} in config to override.
     *
     * @param model the model id (may be {@code null})
     * @return a context window size in tokens; never 0
     */
    public static int windowForModel(String model) {
        String m = model == null ? "" : model.toLowerCase();
        // Most specific first.
        if (m.contains("1m") || m.contains("-1m")) return 1_000_000; // explicit 1M-context variants
        if (m.contains("gpt-4.1")) return 1_000_000;
        if (m.contains("gpt-4o")) return 128_000;
        if (m.contains("gpt-4-turbo")) return 128_000;
        if (m.contains("o1") || m.contains("o3") || m.contains("o4")) return 200_000; // OpenAI reasoning models
        if (m.contains("gpt-3.5")) return 16_385;
        if (m.contains("claude")) return 200_000;
        return 128_000; // conservative default
    }

    public int resolvedMaxOutputTokens() {
        if (maxOutputTokens > 0) return maxOutputTokens;
        return thinking ? 64_000 : 8192;
    }

    public String resolvedApiKey() {
        if (apiKey != null && !apiKey.isEmpty()) return apiKey;
        String envVar = ENV_KEY_MAP.get(protocol);
        if (envVar == null) return "";
        String val = System.getenv(envVar);
        return val != null ? val : "";
    }
}
