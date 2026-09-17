// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.conversation;

import java.util.Map;

public record ToolUseBlock(String toolUseId, String toolName, Map<String, Object> arguments) {}
