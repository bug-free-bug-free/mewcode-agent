// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.tool;

import java.util.Map;

public interface Tool {

    String name();

    String description();

    ToolCategory category();

    Map<String, Object> schema();

    ToolResult execute(Map<String, Object> args);

    default boolean shouldDefer() {
        return false;
    }
}

