// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.agent;

import com.mewcode.permission.PermissionResponse;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public sealed interface AgentEvent {

    record StreamText(String text) implements AgentEvent {}

    record ThinkingText(String text) implements AgentEvent {}

    record ThinkingComplete(String thinking, String signature) implements AgentEvent {}

    record ToolUseEvent(String toolId, String toolName, Map<String, Object> args) implements AgentEvent {}

    record ToolResultEvent(String toolId, String toolName, String output,
                           boolean isError, double elapsed) implements AgentEvent {}

    record TurnComplete(int turn) implements AgentEvent {}

    record LoopComplete(int totalTurns) implements AgentEvent {}

    record UsageEvent(int inputTokens, int outputTokens) implements AgentEvent {}

    record ErrorEvent(String message) implements AgentEvent {}

    record CompactEvent(String message) implements AgentEvent {}

    record RetryEvent(String reason, long waitMs) implements AgentEvent {}

    record PermissionRequestEvent(String toolName, String description,
                                  CompletableFuture<PermissionResponse> future) implements AgentEvent {}

    record AskUserRequestEvent(
            java.util.List<com.mewcode.tui.dialog.AskUserDialog.Question> questions,
            CompletableFuture<Map<String, String>> future) implements AgentEvent {}
}
