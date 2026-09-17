// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.toolresult;

/**
 * One transcript line: a single replacement decision made by
 * {@link ToolResultBudget#apply}, suitable for jsonl persistence so
 * {@link ContentReplacementLifecycle#reconstruct} can rebuild state on
 * resume.
 */
public record ContentReplacementRecord(String kind, String toolUseId, String replacement) {

    public static final String KIND_TOOL_RESULT = "tool-result";

    public static ContentReplacementRecord toolResult(String toolUseId, String replacement) {
        return new ContentReplacementRecord(KIND_TOOL_RESULT, toolUseId, replacement);
    }
}

