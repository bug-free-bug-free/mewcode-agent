// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * LLM client targeting the OpenAI Chat Completions API ({@code /chat/completions}).
 * <p>
 * This is the "compat" variant — it speaks the widely-adopted Chat Completions
 * wire format instead of the newer Responses API, making it compatible with any
 * provider that exposes a {@code /chat/completions} endpoint (vLLM, Ollama,
 * Together, Groq, etc.).
 */
public class OpenAiCompatClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String systemPrompt;
    private volatile int maxOutputTokens;

    public OpenAiCompatClient(ProviderConfig cfg, String systemPrompt) {
        String key = cfg.resolvedApiKey();
        if (key.isEmpty()) {
            throw new LlmException.AuthenticationException(
                    "API key not found for openai-compat provider '" + cfg.getName()
                            + "'. Set it in .mewcode/config.yaml or via OPENAI_API_KEY env var.");
        }
        this.apiKey = key;
        this.baseUrl = cfg.getBaseUrl().replaceAll("/+$", "");
        this.model = cfg.getModel();
        this.systemPrompt = systemPrompt;
        this.maxOutputTokens = cfg.resolvedMaxOutputTokens();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public void setMaxOutputTokens(int tokens) {
        this.maxOutputTokens = tokens;
    }

    // ------------------------------------------------------------------
    // Streaming
    // ------------------------------------------------------------------

    @Override
    public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
        var queue = new LinkedBlockingQueue<StreamEvent>(64);

        Thread.startVirtualThread(() -> {
            try {
                doStream(conv, tools, queue);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                try {
                    queue.put(new StreamEvent.Error(classifyError(e).getMessage()));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        return queue;
    }

    private void doStream(ConversationManager conv, List<Map<String, Object>> tools,
                           BlockingQueue<StreamEvent> queue) throws Exception {

        String body = buildRequestBody(conv.getMessages(), tools);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<java.io.InputStream> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream());

        int statusCode = response.statusCode();
        if (statusCode != 200) {
            String errBody;
            try (var is = response.body()) {
                errBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw new IOException("HTTP " + statusCode + ": " + errBody);
        }

        // Accumulation state for tool-call deltas (keyed by index)
        var toolNames = new HashMap<Integer, StringBuilder>();
        var toolArgs = new HashMap<Integer, StringBuilder>();
        var toolIds = new HashMap<Integer, String>();
        boolean streamEnded = false;

        try (var reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;       // blank SSE separator
                if (line.startsWith(":")) continue;  // SSE comment

                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();

                if ("[DONE]".equals(data)) {
                    // Flush any in-flight tool calls then emit StreamEnd if not already done
                    flushPendingToolCalls(queue, toolNames, toolArgs, toolIds);
                    if (!streamEnded) {
                        queue.put(new StreamEvent.StreamEnd("end_turn", 0, 0));
                    }
                    break;
                }

                streamEnded = handleSseData(data, queue, toolNames, toolArgs, toolIds);
            }
        }
    }

    // ------------------------------------------------------------------
    // SSE chunk handling
    // ------------------------------------------------------------------

    /**
     * Process a single SSE data payload.
     *
     * @return true if a StreamEnd event was emitted (i.e. finish_reason seen)
     */
    private boolean handleSseData(String data, BlockingQueue<StreamEvent> queue,
                                   Map<Integer, StringBuilder> toolNames,
                                   Map<Integer, StringBuilder> toolArgs,
                                   Map<Integer, String> toolIds) throws InterruptedException {
        JsonNode root;
        try {
            root = MAPPER.readTree(data);
        } catch (JsonProcessingException e) {
            return false; // skip unparsable chunks
        }

        // ---- error object ----
        if (root.has("error")) {
            var errNode = root.get("error");
            String errMsg = errNode.has("message") ? errNode.get("message").asText() : errNode.toString();
            queue.put(new StreamEvent.Error(errMsg));
            return false;
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            // Might be a usage-only chunk (stream_options.include_usage)
            return emitUsageIfPresent(root, queue);
        }

        JsonNode choice = choices.get(0);
        JsonNode delta = choice.path("delta");

        // ---- text content ----
        if (delta.has("content") && !delta.get("content").isNull()) {
            String text = delta.get("content").asText();
            if (!text.isEmpty()) {
                queue.put(new StreamEvent.TextDelta(text));
            }
        }

        // ---- tool calls (deltas) ----
        if (delta.has("tool_calls") && delta.get("tool_calls").isArray()) {
            for (JsonNode tc : delta.get("tool_calls")) {
                int idx = tc.path("index").asInt(0);

                // id is present only in the first chunk for a given tool call
                if (tc.has("id") && !tc.get("id").isNull()) {
                    toolIds.put(idx, tc.get("id").asText());
                }

                JsonNode fn = tc.path("function");
                if (fn.has("name") && !fn.get("name").isNull()) {
                    toolNames.computeIfAbsent(idx, k -> new StringBuilder()).append(fn.get("name").asText());
                    // Emit start once we know the name
                    String name = toolNames.get(idx).toString();
                    String callId = toolIds.getOrDefault(idx, "call_" + idx);
                    queue.put(new StreamEvent.ToolCallStart(callId, name));
                }
                if (fn.has("arguments") && !fn.get("arguments").isNull()) {
                    String argChunk = fn.get("arguments").asText();
                    toolArgs.computeIfAbsent(idx, k -> new StringBuilder()).append(argChunk);
                    queue.put(new StreamEvent.ToolCallDelta(argChunk));
                }
            }
        }

        // ---- finish_reason ----
        String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isNull()
                ? choice.get("finish_reason").asText() : null;

        if ("tool_calls".equals(finishReason)) {
            flushPendingToolCalls(queue, toolNames, toolArgs, toolIds);
            // Don't emit StreamEnd yet — the agent loop will continue after tool execution
            return false;
        } else if ("stop".equals(finishReason) || "length".equals(finishReason)) {
            String stopReason = "length".equals(finishReason) ? "max_tokens" : "end_turn";
            int[] usage = extractUsage(root);
            queue.put(new StreamEvent.StreamEnd(stopReason, usage[0], usage[1], usage[2], 0));
            return true;
        }

        return false;
    }

    // ------------------------------------------------------------------
    // Tool-call flush
    // ------------------------------------------------------------------

    private void flushPendingToolCalls(BlockingQueue<StreamEvent> queue,
                                       Map<Integer, StringBuilder> toolNames,
                                       Map<Integer, StringBuilder> toolArgs,
                                       Map<Integer, String> toolIds) throws InterruptedException {
        if (toolNames.isEmpty()) return;

        var sorted = new ArrayList<>(toolNames.keySet());
        Collections.sort(sorted);

        for (int idx : sorted) {
            String name = toolNames.get(idx).toString();
            String callId = toolIds.getOrDefault(idx, "call_" + idx);
            String rawArgs = toolArgs.containsKey(idx) ? toolArgs.get(idx).toString() : "{}";

            Map<String, Object> args;
            try {
                @SuppressWarnings("unchecked")
                var parsed = MAPPER.readValue(rawArgs, Map.class);
                args = parsed;
            } catch (Exception e) {
                args = Map.of();
            }
            queue.put(new StreamEvent.ToolCallComplete(callId, name, args));
        }

        toolNames.clear();
        toolArgs.clear();
        toolIds.clear();
    }

    // ------------------------------------------------------------------
    // Usage extraction
    // ------------------------------------------------------------------

    private boolean emitUsageIfPresent(JsonNode root, BlockingQueue<StreamEvent> queue) throws InterruptedException {
        int[] usage = extractUsage(root);
        if (usage[0] > 0 || usage[1] > 0) {
            queue.put(new StreamEvent.StreamEnd("end_turn", usage[0], usage[1], usage[2], 0));
            return true;
        }
        return false;
    }

    /**
     * Returns {input, output, cacheRead}. OpenAI-compatible providers have no
     * cache-creation concept; cacheRead is read from
     * prompt_tokens_details.cached_tokens when present, else 0.
     */
    static int[] extractUsage(JsonNode root) {
        JsonNode usage = root.path("usage");
        if (usage.isMissingNode()) return new int[]{0, 0, 0};
        int promptTokens = usage.path("prompt_tokens").asInt(0);
        int output = usage.path("completion_tokens").asInt(0);
        int cacheRead = usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
        // OpenAI's prompt_tokens already includes the cached portion; split it out
        // so the anchor's (input + cacheRead + cacheCreation + output) does not
        // double-count the cache hit.
        int input = Math.max(0, promptTokens - cacheRead);
        return new int[]{input, output, cacheRead};
    }

    // ------------------------------------------------------------------
    // Request body building
    // ------------------------------------------------------------------

    private String buildRequestBody(List<Message> messages, List<Map<String, Object>> tools)
            throws JsonProcessingException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("stream", true);
        root.put("max_tokens", maxOutputTokens);

        // stream_options: ask for usage in stream
        ObjectNode streamOpts = MAPPER.createObjectNode();
        streamOpts.put("include_usage", true);
        root.set("stream_options", streamOpts);

        // messages
        root.set("messages", buildChatMessages(messages));

        // tools
        if (tools != null && !tools.isEmpty()) {
            root.set("tools", buildToolsArray(tools));
        }

        return MAPPER.writeValueAsString(root);
    }

    @SuppressWarnings("unchecked")
    private ArrayNode buildChatMessages(List<Message> messages) {
        ArrayNode arr = MAPPER.createArrayNode();

        // System prompt
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            ObjectNode sys = MAPPER.createObjectNode();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            arr.add(sys);
        }

        for (var msg : messages) {
            boolean hasToolUses = msg.getToolUses() != null && !msg.getToolUses().isEmpty();
            boolean hasToolResults = msg.getToolResults() != null && !msg.getToolResults().isEmpty();

            if ("assistant".equals(msg.getRole()) && hasToolUses) {
                // Assistant message with tool calls
                ObjectNode node = MAPPER.createObjectNode();
                node.put("role", "assistant");
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    node.put("content", msg.getContent());
                } else {
                    node.putNull("content");
                }

                ArrayNode toolCallsArr = MAPPER.createArrayNode();
                for (var tu : msg.getToolUses()) {
                    ObjectNode tc = MAPPER.createObjectNode();
                    tc.put("id", tu.toolUseId());
                    tc.put("type", "function");
                    ObjectNode fn = MAPPER.createObjectNode();
                    fn.put("name", tu.toolName());
                    try {
                        fn.put("arguments", MAPPER.writeValueAsString(tu.arguments()));
                    } catch (JsonProcessingException e) {
                        fn.put("arguments", "{}");
                    }
                    tc.set("function", fn);
                    toolCallsArr.add(tc);
                }
                node.set("tool_calls", toolCallsArr);
                arr.add(node);

            } else if (hasToolResults) {
                // Tool result messages — one per result
                for (var tr : msg.getToolResults()) {
                    ObjectNode node = MAPPER.createObjectNode();
                    node.put("role", "tool");
                    node.put("tool_call_id", tr.toolUseId());
                    node.put("content", tr.content());
                    arr.add(node);
                }
            } else {
                // Plain user or assistant message (skip thinking blocks — not supported)
                ObjectNode node = MAPPER.createObjectNode();
                node.put("role", msg.getRole());
                node.put("content", msg.getContent() != null ? msg.getContent() : "");
                arr.add(node);
            }
        }

        return arr;
    }

    @SuppressWarnings("unchecked")
    private ArrayNode buildToolsArray(List<Map<String, Object>> tools) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (var schema : tools) {
            ObjectNode tool = MAPPER.createObjectNode();
            tool.put("type", "function");

            ObjectNode fn = MAPPER.createObjectNode();
            fn.put("name", (String) schema.get("name"));
            if (schema.containsKey("description")) {
                fn.put("description", Objects.toString(schema.get("description"), ""));
            }

            var params = (Map<String, Object>) schema.getOrDefault("parameters",
                    schema.getOrDefault("input_schema", Map.of()));
            fn.set("parameters", MAPPER.valueToTree(params));

            tool.set("function", fn);
            arr.add(tool);
        }
        return arr;
    }

    // ------------------------------------------------------------------
    // Error classification
    // ------------------------------------------------------------------

    private LlmException classifyError(Exception e) {
        if (e instanceof LlmException le) return le;
        String msg = e.getMessage() != null ? e.getMessage() : "";
        String lower = msg.toLowerCase();

        if (msg.startsWith("HTTP 401") || lower.contains("unauthorized") || lower.contains("invalid api key")) {
            return new LlmException.AuthenticationException("Invalid API key: " + msg);
        }
        if (msg.startsWith("HTTP 429") || lower.contains("rate limit")) {
            return new LlmException.RateLimitException("Rate limited. Please wait.", "");
        }
        if (lower.contains("context_length_exceeded") || lower.contains("prompt is too long")
                || lower.contains("too many tokens") || msg.startsWith("HTTP 413")) {
            return new LlmException.ContextTooLongException("Context too long: " + msg);
        }
        if (e instanceof IOException) {
            return new LlmException.NetworkException("Network error: " + msg, e);
        }
        if (msg.startsWith("HTTP 4") || msg.startsWith("HTTP 5")) {
            return new LlmException("API error: " + msg, e);
        }
        return new LlmException("Unexpected error: " + msg, e);
    }
}
