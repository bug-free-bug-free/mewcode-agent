// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.*;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class OpenAiClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final com.openai.client.OpenAIClient sdkClient;
    private final String model;
    private final boolean thinking;
    private final String systemPrompt;
    private volatile int maxOutputTokens;

    public OpenAiClient(ProviderConfig cfg, String systemPrompt) {
        String apiKey = cfg.resolvedApiKey();
        if (apiKey.isEmpty()) {
            throw new LlmException.AuthenticationException(
                    "OpenAI API key not found. Set it in .mewcode/config.yaml or via OPENAI_API_KEY env var.");
        }
        this.sdkClient = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(cfg.getBaseUrl())
                .build();
        this.model = cfg.getModel();
        this.thinking = cfg.isThinking();
        this.systemPrompt = systemPrompt;
        this.maxOutputTokens = cfg.resolvedMaxOutputTokens();
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

        var paramsBuilder = ResponseCreateParams.builder()
                .model(model)
                .instructions(systemPrompt)
                .inputOfResponse(buildInput(conv.getMessages()));

        if (thinking) {
            paramsBuilder.reasoning(Reasoning.builder()
                    .effort(ReasoningEffort.HIGH)
                    .summary(Reasoning.Summary.DETAILED)
                    .build());
        }

        if (tools != null && !tools.isEmpty()) {
            for (var schema : tools) {
                paramsBuilder.addTool(buildFunctionTool(schema));
            }
        }

        String currentToolName = "";
        String currentCallId = "";
        var jsonAccum = new StringBuilder();
        var reasoningText = new StringBuilder();
        String reasoningId = "";

        try (StreamResponse<ResponseStreamEvent> streamResponse =
                     sdkClient.responses().createStreaming(paramsBuilder.build())) {

            var iterator = streamResponse.stream().iterator();
            while (iterator.hasNext()) {
                var event = iterator.next();

                if (event.isOutputTextDelta()) {
                    queue.put(new StreamEvent.TextDelta(event.asOutputTextDelta().delta()));
                } else if (event.isOutputItemAdded()) {
                    var item = event.asOutputItemAdded().item();
                    if (item.isFunctionCall()) {
                        var fc = item.asFunctionCall();
                        currentToolName = fc.name();
                        currentCallId = fc.callId();
                        jsonAccum.setLength(0);
                        queue.put(new StreamEvent.ToolCallStart(currentCallId, currentToolName));
                    } else if (item.isReasoning()) {
                        reasoningId = item.asReasoning().id();
                        reasoningText.setLength(0);
                    }
                } else if (event.isFunctionCallArgumentsDelta()) {
                    String delta = event.asFunctionCallArgumentsDelta().delta();
                    jsonAccum.append(delta);
                    queue.put(new StreamEvent.ToolCallDelta(delta));
                } else if (event.isFunctionCallArgumentsDone()) {
                    Map<String, Object> args;
                    try {
                        @SuppressWarnings("unchecked")
                        var parsed = MAPPER.readValue(jsonAccum.toString(), Map.class);
                        args = parsed;
                    } catch (Exception ex) {
                        args = Map.of();
                    }
                    queue.put(new StreamEvent.ToolCallComplete(currentCallId, currentToolName, args));
                    currentToolName = "";
                    currentCallId = "";
                    jsonAccum.setLength(0);
                } else if (event.isReasoningSummaryTextDelta()) {
                    String delta = event.asReasoningSummaryTextDelta().delta();
                    reasoningText.append(delta);
                    queue.put(new StreamEvent.ThinkingDelta(delta));
                } else if (event.isReasoningSummaryTextDone()) {
                    queue.put(new StreamEvent.ThinkingComplete(reasoningText.toString(), reasoningId));
                } else if (event.isCompleted()) {
                    var resp = event.asCompleted().response();
                    int inputTokens = 0, outputTokens = 0, cacheReadTokens = 0;
                    if (resp.usage().isPresent()) {
                        var usage = resp.usage().get();
                        outputTokens = (int) usage.outputTokens();
                        cacheReadTokens = (int) usage.inputTokensDetails().cachedTokens();
                        // inputTokens already includes the cached portion; split
                        // it out so the anchor does not double-count the hit.
                        inputTokens = Math.max(0, (int) usage.inputTokens() - cacheReadTokens);
                    }
                    queue.put(new StreamEvent.StreamEnd(
                            "end_turn", inputTokens, outputTokens, cacheReadTokens, 0));
                }
            }
        }
    }

    private List<ResponseInputItem> buildInput(List<Message> messages) {
        var result = new ArrayList<ResponseInputItem>();
        for (var msg : messages) {
            if (msg.getToolUses() != null && !msg.getToolUses().isEmpty()) {
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    result.add(ResponseInputItem.ofEasyInputMessage(
                            EasyInputMessage.builder()
                                    .role(EasyInputMessage.Role.ASSISTANT)
                                    .content(msg.getContent())
                                    .build()));
                }
                for (var tu : msg.getToolUses()) {
                    String argsJson;
                    try {
                        argsJson = MAPPER.writeValueAsString(tu.arguments());
                    } catch (Exception e) {
                        argsJson = "{}";
                    }
                    result.add(ResponseInputItem.ofFunctionCall(
                            ResponseFunctionToolCall.builder()
                                    .callId(tu.toolUseId())
                                    .name(tu.toolName())
                                    .arguments(argsJson)
                                    .build()));
                }
            } else if (msg.getToolResults() != null && !msg.getToolResults().isEmpty()) {
                for (var tr : msg.getToolResults()) {
                    result.add(ResponseInputItem.ofFunctionCallOutput(
                            ResponseInputItem.FunctionCallOutput.builder()
                                    .callId(tr.toolUseId())
                                    .output(tr.content())
                                    .build()));
                }
            } else {
                var role = "assistant".equals(msg.getRole())
                        ? EasyInputMessage.Role.ASSISTANT
                        : EasyInputMessage.Role.USER;
                result.add(ResponseInputItem.ofEasyInputMessage(
                        EasyInputMessage.builder()
                                .role(role)
                                .content(msg.getContent())
                                .build()));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private FunctionTool buildFunctionTool(Map<String, Object> schema) {
        var builder = FunctionTool.builder()
                .name((String) schema.get("name"))
                .strict(false);
        if (schema.containsKey("description")) {
            builder.description(Objects.toString(schema.get("description"), ""));
        }
        var params = (Map<String, Object>) schema.getOrDefault("parameters",
                schema.getOrDefault("input_schema", Map.of()));
        builder.parameters(FunctionTool.Parameters.builder()
                .putAllAdditionalProperties(toJsonValueMap(params))
                .build());
        return builder.build();
    }

    private Map<String, JsonValue> toJsonValueMap(Map<String, Object> map) {
        var result = new LinkedHashMap<String, JsonValue>();
        for (var entry : map.entrySet()) {
            result.put(entry.getKey(), JsonValue.from(entry.getValue()));
        }
        return result;
    }

    private LlmException classifyError(Exception e) {
        if (e instanceof LlmException le) return le;
        if (e instanceof com.openai.errors.UnauthorizedException) {
            return new LlmException.AuthenticationException("Invalid API key: " + e.getMessage());
        }
        if (e instanceof com.openai.errors.RateLimitException) {
            return new LlmException.RateLimitException("Rate limited. Please wait.", "");
        }
        if (e instanceof com.openai.errors.BadRequestException bre) {
            String msg = bre.getMessage() != null ? bre.getMessage().toLowerCase() : "";
            if (msg.contains("context_length_exceeded") || msg.contains("prompt is too long")) {
                return new LlmException.ContextTooLongException("Context too long: " + bre.getMessage());
            }
            return new LlmException("Bad request: " + bre.getMessage(), bre);
        }
        if (e instanceof com.openai.errors.OpenAIServiceException se) {
            if (se.statusCode() == 413) {
                return new LlmException.ContextTooLongException("Context too long: " + se.getMessage());
            }
            return new LlmException("API error (" + se.statusCode() + "): " + se.getMessage(), se);
        }
        if (e instanceof com.openai.errors.OpenAIIoException) {
            return new LlmException.NetworkException("Network error: " + e.getMessage(), e);
        }
        return new LlmException("Unexpected error: " + e.getMessage(), e);
    }
}
