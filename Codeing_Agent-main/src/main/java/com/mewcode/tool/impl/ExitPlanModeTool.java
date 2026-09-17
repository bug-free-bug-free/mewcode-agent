// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.tool.impl;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import java.util.Map;
import java.util.function.BooleanSupplier;

public class ExitPlanModeTool implements Tool {

    private static final String DESCRIPTION =
            "Exit plan mode and present the plan for user approval. "
                    + "Call this when your plan is complete and written to the plan file.";

    private BooleanSupplier isPlanMode;
    private BooleanSupplier planExists;

    public void setIsPlanMode(BooleanSupplier isPlanMode) {
        this.isPlanMode = isPlanMode;
    }

    public void setPlanExists(BooleanSupplier planExists) {
        this.planExists = planExists;
    }

    @Override
    public String name() {
        return "ExitPlanMode";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of()
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        if (isPlanMode != null && !isPlanMode.getAsBoolean()) {
            return new ToolResult(
                    "You are not in plan mode. This tool is only for exiting plan mode after writing a plan.",
                    true);
        }
        if (planExists != null && !planExists.getAsBoolean()) {
            return new ToolResult(
                    "No plan file found. Please write your plan to the plan file before calling ExitPlanMode.",
                    true);
        }
        return new ToolResult(
                "Plan mode will be exited after this turn. "
                        + "The user will be shown the plan approval dialog. "
                        + "Do not call any more tools — end your turn now.",
                false);
    }
}
