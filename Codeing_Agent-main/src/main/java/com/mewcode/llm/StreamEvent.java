// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.llm;

import java.util.Map;

public sealed interface StreamEvent {

    record TextDelta(String text) implements StreamEvent {}

    record ThinkingDelta(String text) implements StreamEvent {}

    record ThinkingComplete(String thinking, String signature) implements StreamEvent {}

    record ToolCallStart(String toolId, String toolName) implements StreamEvent {}

    record ToolCallDelta(String text) implements StreamEvent {}

    record ToolCallComplete(String toolId, String toolName, Map<String, Object> arguments) implements StreamEvent {}

    record StreamEnd(String stopReason, int inputTokens, int outputTokens,
                     int cacheReadTokens, int cacheCreationTokens) implements StreamEvent {

        /** Cold-start / non-cache providers: usage carries no cache breakdown. */
        public StreamEnd(String stopReason, int inputTokens, int outputTokens) {
            this(stopReason, inputTokens, outputTokens, 0, 0);
        }
    }

    record Error(String message) implements StreamEvent {}
}
