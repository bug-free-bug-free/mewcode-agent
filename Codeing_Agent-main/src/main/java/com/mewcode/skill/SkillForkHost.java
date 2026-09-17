// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.skill;

import com.mewcode.conversation.Message;

import java.util.List;

/**
 * Extends {@link SkillHost} with the ability to run an isolated sub-agent.
 * Implemented by the TUI layer (which owns the LLM client + agent
 * constructor) and passed into {@link SkillExecutor#executeFork}.
 */
public interface SkillForkHost extends SkillHost {

    String runSubAgent(String body, List<Message> seed, List<String> allowedTools, String model);

    List<Message> snapshotParentMessages();
}

