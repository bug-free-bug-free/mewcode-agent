// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.conversation;

import java.util.List;

public class Message {

    private String role;
    private String content;

    private List<ThinkingBlock> thinkingBlocks;
    private List<ToolUseBlock> toolUses;

    private List<ToolResultBlock> toolResults;

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public List<ThinkingBlock> getThinkingBlocks() { return thinkingBlocks; }

    public void setThinkingBlocks(List<ThinkingBlock> thinkingBlocks) { this.thinkingBlocks = thinkingBlocks; }

    public List<ToolUseBlock> getToolUses() { return toolUses; }

    public void setToolUses(List<ToolUseBlock> toolUses) { this.toolUses = toolUses; }

    public List<ToolResultBlock> getToolResults() { return toolResults; }
    public void setToolResults(List<ToolResultBlock> toolResults) { this.toolResults = toolResults; }
}
