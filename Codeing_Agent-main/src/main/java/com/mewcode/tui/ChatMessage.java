// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Display-oriented message for the TUI chat view.
 * Roles: user, assistant, system, error, tool, tool_collapsed, tool_group, sub_agent, thinking.
 */
public class ChatMessage {

    public String role;
    public String content;
    public List<ToolBlockInfo> toolGroup;
    public SubAgentBlockState subAgentBlock;
    public boolean expanded;

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public ChatMessage(String role, String content, List<ToolBlockInfo> toolGroup) {
        this.role = role;
        this.content = content;
        this.toolGroup = toolGroup;
    }

    /**
     * Information about a single tool invocation for rendering in the TUI.
     */
    public record ToolBlockInfo(
            String toolName,
            Map<String, Object> args,
            String output,
            boolean isError,
            double elapsed,
            boolean collapsed,
            boolean loading
    ) {}

    /**
     * Tracks a sub-agent's execution for display in the TUI.
     */
    public static class SubAgentBlockState {
        public String desc;

        public String agentType;
        public List<ToolBlockInfo> toolUses = new ArrayList<>();
        public boolean done;
        public int toolCount;
        public double totalTime;
    }
}
