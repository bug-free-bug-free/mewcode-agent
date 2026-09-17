// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.llm;

import java.util.Map;

public class ModelResolver {

    private static final Map<String, String> ALIASES = Map.of(
            "haiku", "claude-haiku-4-5-20251001",
            "sonnet", "claude-sonnet-4-6-20250514",
            "opus", "claude-opus-4-6-20250514"
    );

    public static String resolve(String model) {
        return ALIASES.getOrDefault(model, model);
    }

    public static boolean supportsAdaptiveThinking(String model) {
        String resolved = resolve(model);
        return resolved.contains("opus-4-6") || resolved.contains("sonnet-4-6");
    }

    public static boolean supportsThinking(String model) {
        String resolved = resolve(model);
        return resolved.contains("claude");
    }
}
