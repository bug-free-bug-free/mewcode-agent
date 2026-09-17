// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.command;

import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Runtime context passed to command handlers, providing access to the current
 * application state (permission mode, token counts, model, etc.).
 */
public record CommandContext(
        String args,
        String workDir,
        String model,
        Supplier<String> permissionMode,
        IntSupplier toolCount,
        Supplier<int[]> tokenCount,
        Supplier<List<String>> memoryList,
        Runnable memoryClear,
        Supplier<String> sessionInfo,
        Supplier<List<String>> skillList,
        Supplier<String> mcpInfo
) {}

