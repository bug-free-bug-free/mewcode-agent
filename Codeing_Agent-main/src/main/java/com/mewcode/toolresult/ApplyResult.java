// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.toolresult;

import com.mewcode.conversation.ConversationManager;

import java.util.List;

/**
 * Result of {@link ToolResultBudget#apply}: a freshly-built
 * {@link ConversationManager} with replacements applied (the input conv is
 * never mutated — that's the whole point of Design B) plus the list of
 * decisions newly made on this call (subset of {@code state.replacements}
 * additions), which the caller should append to the session transcript so
 * resume can rebuild state.
 */
public record ApplyResult(ConversationManager apiConv, List<ContentReplacementRecord> newRecords) {}

