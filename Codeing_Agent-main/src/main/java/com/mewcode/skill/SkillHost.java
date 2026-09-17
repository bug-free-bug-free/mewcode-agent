// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.skill;

import com.mewcode.tool.ToolRegistry;

import java.util.function.Predicate;

/**
 * Slice of Agent state that the executor needs to drive inline-mode skills.
 * Declared as an interface so the skills package doesn't import the agent
 * package (avoids circular dependency).
 */
public interface SkillHost {

    void activateSkill(String name, String body);

    void setToolFilter(Predicate<String> filter);

    ToolRegistry toolRegistry();

    /**
     * Record that this skill ran, so its SOP body can be re-attached after
     * a Layer 2 compaction wipes the transcript. Default no-op for hosts
     * that don't track recovery state.
     */
    default void recordSkillInvocation(String name, String body) {}
}

