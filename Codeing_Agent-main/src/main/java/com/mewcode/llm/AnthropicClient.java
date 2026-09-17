// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.llm;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class AnthropicClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final com.anthropic.client.AnthropicClient sdkClient;
    private final String model;

    private final boolean thinking;
    private final String systemPrompt;
    private volatile int maxOutputTokens;

    public AnthropicClient(ProviderConfig cfg, String systemPrompt) {
        String apiKey = cfg.resolvedApiKey();
        if (apiKey.isEmpty()) {
            throw new LlmException.AuthenticationException(
                    "Anthropic API key not found. Set it in .mewcode/config.yaml or via ANTHROPIC_API_KEY env var.");
        }
        this.sdkClient = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(cfg.getBaseUrl())
                .build();
        this.model = ModelResolver.resolve(cfg.getModel());
        this.thinking = cfg.isThinking();
        this.systemPrompt = systemPrompt;
        this.maxOutputTokens = cfg.resolvedMaxOutputTokens();

        // Layer 2 of context-window resolution: best-effort fetch from the
        // provider's models endpoint, cached back onto cfg so a later
        // cfg.resolvedContextWindow() can use it. Never blocks startup or
        // throws — any failure silently degrades to the built-in table.
        cfg.setFetchedContextWindow(fetchModelContextWindow());
    }

    /**
     * Fetch the model's context window from {@code GET {base_url}/v1/models/{model}}
     * (Anthropic protocol only), reading {@code ModelInfo.max_input_tokens}.
     *
     * <p>Best-effort: returns {@code 0} on any error (network, auth, unknown
     * model, missing field, timeout). Never throws — callers treat 0 as
     * "unavailable" and fall through to the next resolution layer.
     *
     * @return max input tokens (&gt; 0) on success, or {@code 0} on any failure
     */
    int fetchModelContextWindow() {
        try {
            var info = sdkClient.models().retrieve(
                    model,
                    com.anthropic.core.RequestOptions.builder()
                            .timeout(java.time.Duration.ofSeconds(5))
                            .build());
            return info.maxInputTokens()
                    .map(Long::intValue)
                    .filter(v -> v > 0)
                    .orElse(0);
        } catch (Exception | Error e) {
            // Swallow everything: this must never block or break startup.
            return 0;
        }
    }

    @Override
    public void setMaxOutputTokens(int tokens) {
        this.maxOutputTokens = tokens;
    }

    @Override
    public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
        var queue = new LinkedBlockingQueue<StreamEvent>(64);

        Thread.startVirtualThread(() -> {
            try {
                doStream(conv, tools, queue);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                try { queue.put(new StreamEvent.Error(classifyError(e).getMessage())); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        });

        return queue;
    }

    private void doStream(ConversationManager conv, List<Map<String, Object>> tools,
                          BlockingQueue<StreamEvent> queue) throws Exception {

        // Anchor the prompt cache on the longest-stable prefix: system, then
        // tools, then the tail of the final user message. ContentReplacementState
        // in com.mewcode.toolresult is what keeps tool_result content past
        // these breakpoints byte-stable across turns.
        var systemBlock = TextBlockParam.builder()
                .text(systemPrompt)
                .cacheControl(CacheControlEphemeral.builder().build())
                .build();
        var messageParams = buildMessages(conv.getMessages());
        markLastUserTailForCache(messageParams);
        var paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxOutputTokens)
                .system(MessageCreateParams.System.ofTextBlockParams(List.of(systemBlock)))
                .messages(messageParams);

        if (thinking) {
            if (ModelResolver.supportsAdaptiveThinking(model)) {
                paramsBuilder.thinking(ThinkingConfigAdaptive.builder().build());
            } else {
                paramsBuilder.thinking(ThinkingConfigEnabled.builder()
                        .budgetTokens(maxOutputTokens - 1)
                        .build());
            }
        }

        if (tools != null && !tools.isEmpty()) {
            // Tool schemas are stable across turns, so marking the last
            // tool effectively caches the entire tools block on the wire.
            for (int i = 0; i < tools.size(); i++) {
                boolean isLast = (i == tools.size() - 1);
                paramsBuilder.addTool(buildTool(tools.get(i), isLast));
            }
        }

        String currentToolName = "";
        String currentToolId = "";
        var jsonAccum = new StringBuilder();
        var thinkingAccum = new StringBuilder();
        String thinkingSignature = "";
        boolean inThinking = false;
        int inputTokens = 0, outputTokens = 0;
        int cacheReadTokens = 0, cacheCreationTokens = 0;
        String stopReason = "end_turn";

        try (StreamResponse<RawMessageStreamEvent> streamResponse =
                     sdkClient.messages().createStreaming(paramsBuilder.build())) {

            var iterator = streamResponse.stream().iterator();
            while (iterator.hasNext()) {
                var event = iterator.next();
                if (event.isContentBlockStart()) {
                    var startEvent = event.asContentBlockStart();
                    var block = startEvent.contentBlock();
                    if (block.isThinking()) {
                        inThinking = true;
                        thinkingAccum.setLength(0);
                        thinkingSignature = "";
                    } else if (block.isToolUse()) {
                        var tu = block.asToolUse();
                        currentToolName = tu.name();
                        currentToolId = tu.id();
                        jsonAccum.setLength(0);
                        queue.put(new StreamEvent.ToolCallStart(currentToolId, currentToolName));
                    }
                } else if (event.isContentBlockDelta()) {
                    var delta = event.asContentBlockDelta().delta();
                    if (delta.isThinking()) {
                        String text = delta.asThinking().thinking();
                        thinkingAccum.append(text);
                        queue.put(new StreamEvent.ThinkingDelta(text));
                    } else if (delta.isSignature()) {
                        thinkingSignature = delta.asSignature().signature();
                    } else if (delta.isText()) {
                        queue.put(new StreamEvent.TextDelta(delta.asText().text()));
                    } else if (delta.isInputJson()) {
                        String partialJson = delta.asInputJson().partialJson();
                        jsonAccum.append(partialJson);
                        queue.put(new StreamEvent.ToolCallDelta(partialJson));
                    }
                } else if (event.isContentBlockStop()) {
                    if (inThinking) {
                        queue.put(new StreamEvent.ThinkingComplete(
                                thinkingAccum.toString(), thinkingSignature));
                        inThinking = false;
                    }
                    if (!currentToolName.isEmpty()) {
                        Map<String, Object> args;
                        try {
                            @SuppressWarnings("unchecked")
                            var parsed = MAPPER.readValue(jsonAccum.toString(), Map.class);
                            args = parsed;
                        } catch (Exception e) {
                            args = new HashMap<>();
                        }
                        queue.put(new StreamEvent.ToolCallComplete(
                                currentToolId, currentToolName, args));
                        currentToolName = "";
                        currentToolId = "";
                        jsonAccum.setLength(0);
                    }
                } else if (event.isMessageDelta()) {
                    var msgDelta = event.asMessageDelta();
                    var sr = msgDelta.delta().stopReason();
                    if (sr.isPresent()) {
                        stopReason = sr.get().asString();
                    }
                    var usage = msgDelta.usage();
                    outputTokens = (int) usage.outputTokens();
                    if (usage.inputTokens().isPresent()) {
                        inputTokens = usage.inputTokens().get().intValue();
                    }
                    if (usage.cacheReadInputTokens().isPresent()) {
                        cacheReadTokens = usage.cacheReadInputTokens().get().intValue();
                    }
                    if (usage.cacheCreationInputTokens().isPresent()) {
                        cacheCreationTokens = usage.cacheCreationInputTokens().get().intValue();
                    }
                } else if (event.isMessageStart()) {
                    var msg = event.asMessageStart().message();
                    var usage = msg.usage();
                    inputTokens = (int) usage.inputTokens();
                    if (usage.cacheReadInputTokens().isPresent()) {
                        cacheReadTokens = usage.cacheReadInputTokens().get().intValue();
                    }
                    if (usage.cacheCreationInputTokens().isPresent()) {
                        cacheCreationTokens = usage.cacheCreationInputTokens().get().intValue();
                    }
                }
            }
        }

        queue.put(new StreamEvent.StreamEnd(
                stopReason != null ? stopReason : "end_turn", inputTokens, outputTokens,
                cacheReadTokens, cacheCreationTokens));
    }

    private List<MessageParam> buildMessages(List<Message> messages) {
        var result = new ArrayList<MessageParam>();
        for (var msg : messages) {
            boolean hasThinking = msg.getThinkingBlocks() != null && !msg.getThinkingBlocks().isEmpty();
            boolean hasToolUses = msg.getToolUses() != null && !msg.getToolUses().isEmpty();

            if ("assistant".equals(msg.getRole()) && (hasThinking || hasToolUses)) {
                var content = new ArrayList<ContentBlockParam>();
                if (hasThinking) {
                    for (var tb : msg.getThinkingBlocks()) {
                        content.add(ContentBlockParam.ofThinking(
                                ThinkingBlockParam.builder()
                                        .thinking(tb.thinking())
                                        .signature(tb.signature())
                                        .build()));
                    }
                }
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    content.add(ContentBlockParam.ofText(
                            TextBlockParam.builder().text(msg.getContent()).build()));
                }
                if (hasToolUses) {
                    for (var tu : msg.getToolUses()) {
                        content.add(ContentBlockParam.ofToolUse(
                                ToolUseBlockParam.builder()
                                        .id(tu.toolUseId())
                                        .name(tu.toolName())
                                        .input(JsonValue.from(tu.arguments()))
                                        .build()));
                    }
                }
                if (content.isEmpty()) {
                    content.add(ContentBlockParam.ofText(
                            TextBlockParam.builder().text("").build()));
                }
                result.add(MessageParam.builder()
                        .role(MessageParam.Role.ASSISTANT)
                        .contentOfBlockParams(content)
                        .build());
            } else if (msg.getToolResults() != null && !msg.getToolResults().isEmpty()) {
                var content = new ArrayList<ContentBlockParam>();
                for (var tr : msg.getToolResults()) {
                    content.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(tr.toolUseId())
                                    .content(tr.content())
                                    .isError(tr.isError())
                                    .build()));
                }
                result.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(content)
                        .build());
            } else {
                if (!result.isEmpty()) {
                    var prev = result.getLast();
                    if (prev.role().asString().equals(msg.getRole())) {
                        var merged = prev.toBuilder();
                        // SDK doesn't easily merge content, so we use addAssistant/addUser helpers
                        // For simplicity, just add a new message. The API handles consecutive same-role
                        // by requiring alternating roles, so we merge text.
                    }
                }
                var builder = MessageParam.builder()
                        .content(msg.getContent());
                if ("assistant".equals(msg.getRole())) {
                    builder.role(MessageParam.Role.ASSISTANT);
                } else {
                    builder.role(MessageParam.Role.USER);
                }
                result.add(builder.build());
            }
        }
        return mergeConsecutiveSameRole(result);
    }

    private List<MessageParam> mergeConsecutiveSameRole(List<MessageParam> messages) {
        if (messages.size() <= 1) return messages;
        var merged = new ArrayList<MessageParam>();
        merged.add(messages.getFirst());
        for (int i = 1; i < messages.size(); i++) {
            var prev = merged.getLast();
            var curr = messages.get(i);
            if (prev.role().equals(curr.role())) {
                // Both are simple text content — merge them
                var prevContent = prev.content();
                var currContent = curr.content();
                if (prevContent.isString() && currContent.isString()) {
                    merged.set(merged.size() - 1, MessageParam.builder()
                            .role(prev.role())
                            .content(prevContent.asString() + "\n\n" + currContent.asString())
                            .build());
                } else {
                    // One has block params — just append as-is, let API handle
                    merged.add(curr);
                }
            } else {
                merged.add(curr);
            }
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private Tool buildTool(Map<String, Object> schema, boolean markCache) {
        var builder = Tool.builder()
                .name((String) schema.get("name"));
        if (schema.containsKey("description")) {
            builder.description((String) schema.get("description"));
        }
        var inputSchema = (Map<String, Object>) schema.getOrDefault("input_schema",
                Map.of("type", "object", "properties", Map.of()));
        builder.inputSchema(Tool.InputSchema.builder()
                .type(JsonValue.from(inputSchema.getOrDefault("type", "object")))
                .putAllAdditionalProperties(toJsonValueMap(inputSchema))
                .build());
        if (markCache) {
            builder.cacheControl(CacheControlEphemeral.builder().build());
        }
        return builder.build();
    }

    /**
     * Attach an ephemeral cache_control marker to the last content block of
     * the final user-role message in {@code messages}. Anthropic caches the
     * prefix up to (and including) this block; subsequent requests with a
     * byte-identical prefix hit the cache.
     *
     * <p>Mutates {@code messages} in place by swapping the trailing
     * MessageParam for a rebuilt one with cache_control attached — the
     * SDK's builder is immutable, so we can't edit in place at the field
     * level.
     */
    private void markLastUserTailForCache(List<MessageParam> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            var msg = messages.get(i);
            if (msg.role() != MessageParam.Role.USER) continue;
            // The user message's content is either a string or block list.
            // We need block form to attach cache_control, so up-convert if
            // it's a string.
            var content = msg.content();
            List<ContentBlockParam> blocks;
            if (content.string().isPresent()) {
                blocks = List.of(ContentBlockParam.ofText(
                        TextBlockParam.builder()
                                .text(content.string().get())
                                .cacheControl(CacheControlEphemeral.builder().build())
                                .build()));
            } else if (content.blockParams().isPresent()) {
                var orig = content.blockParams().get();
                if (orig.isEmpty()) return;
                blocks = new ArrayList<>(orig);
                var last = blocks.getLast();
                ContentBlockParam rebuilt;
                if (last.text().isPresent()) {
                    var t = last.text().get();
                    rebuilt = ContentBlockParam.ofText(t.toBuilder()
                            .cacheControl(CacheControlEphemeral.builder().build())
                            .build());
                } else if (last.toolResult().isPresent()) {
                    var tr = last.toolResult().get();
                    rebuilt = ContentBlockParam.ofToolResult(tr.toBuilder()
                            .cacheControl(CacheControlEphemeral.builder().build())
                            .build());
                } else {
                    return; // unsupported block type at tail — silently skip
                }
                blocks.set(blocks.size() - 1, rebuilt);
            } else {
                return;
            }
            messages.set(i, MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(blocks)
                    .build());
            return;
        }
    }

    private Map<String, JsonValue> toJsonValueMap(Map<String, Object> map) {
        var result = new LinkedHashMap<String, JsonValue>();
        for (var entry : map.entrySet()) {
            if ("type".equals(entry.getKey())) continue;
            result.put(entry.getKey(), JsonValue.from(entry.getValue()));
        }
        return result;
    }

    private LlmException classifyError(Exception e) {
        if (e instanceof LlmException le) return le;
        if (e instanceof com.anthropic.errors.UnauthorizedException ue) {
            return new LlmException.AuthenticationException("Invalid API key: " + ue.getMessage());
        }
        if (e instanceof com.anthropic.errors.RateLimitException) {
            return new LlmException.RateLimitException("Rate limited. Please wait.", "");
        }
        if (e instanceof com.anthropic.errors.BadRequestException bre) {
            String msg = bre.getMessage() != null ? bre.getMessage().toLowerCase() : "";
            if (msg.contains("prompt is too long") || msg.contains("too many tokens")) {
                return new LlmException.ContextTooLongException("Context too long: " + bre.getMessage());
            }
            return new LlmException("Bad request: " + bre.getMessage(), bre);
        }
        if (e instanceof com.anthropic.errors.AnthropicServiceException se) {
            if (se.statusCode() == 413) {
                return new LlmException.ContextTooLongException("Context too long: " + se.getMessage());
            }
            return new LlmException("API error (" + se.statusCode() + "): " + se.getMessage(), se);
        }
        if (e instanceof com.anthropic.errors.AnthropicIoException) {
            return new LlmException.NetworkException("Network error: " + e.getMessage(), e);
        }
        return new LlmException("Unexpected error: " + e.getMessage(), e);
    }
}
