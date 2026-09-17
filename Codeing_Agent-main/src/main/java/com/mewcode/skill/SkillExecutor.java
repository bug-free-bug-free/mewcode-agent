// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.skill;

import com.mewcode.conversation.Message;
import com.mewcode.skill.SkillCatalog.Skill;
import com.mewcode.skill.SkillCatalog.SkillMeta;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolRegistry;

import java.util.*;

/**
 * Executes skills in inline or fork mode.
 */
public final class SkillExecutor {

    private static final int FORK_RECENT_COUNT = 5;

    private SkillExecutor() {}

    /**
     * Activates the skill's SOP on the host agent, applies the
     * allowed_tools whitelist, and returns the rendered prompt body.
     */
    public static String executeInline(Skill skill, String args, SkillHost host) {
        assertAllowedToolsExist(skill, host.toolRegistry());
        String body = substituteArguments(skill.promptBody(), args);
        host.activateSkill(skill.meta().name(), body);
        host.recordSkillInvocation(skill.meta().name(), body);

        if (skill.meta().allowedTools() != null && !skill.meta().allowedTools().isEmpty()) {
            Set<String> allowed = new HashSet<>(skill.meta().allowedTools());
            host.setToolFilter(allowed::contains);
        } else {
            host.setToolFilter(null);
        }
        return body;
    }

    /**
     * Executes the skill in an isolated sub-agent and returns the
     * final assistant text.
     */
    public static String executeFork(Skill skill, String args, SkillForkHost host) {
        assertAllowedToolsExist(skill, host.toolRegistry());
        String body = substituteArguments(skill.promptBody(), args);
        host.recordSkillInvocation(skill.meta().name(), skill.promptBody());
        List<Message> seed = buildForkSeed(skill.meta().forkContext(), host.snapshotParentMessages());
        return host.runSubAgent(body, seed, skill.meta().allowedTools(), skill.meta().model());
    }

    static String substituteArguments(String body, String args) {
        if (args == null || args.isBlank()) {
            return body;
        }
        if (body.contains("$ARGUMENTS")) {
            return body.replace("$ARGUMENTS", args);
        }
        return body + "\n\n## User Request\n\n" + args;
    }

    static List<Message> buildForkSeed(String mode, List<Message> parent) {
        if (parent == null || parent.isEmpty()) {
            return List.of();
        }
        return switch (mode != null ? mode : "none") {
            case "full" -> new ArrayList<>(parent);
            case "recent" -> {
                if (parent.size() <= FORK_RECENT_COUNT) {
                    yield new ArrayList<>(parent);
                }
                yield new ArrayList<>(parent.subList(parent.size() - FORK_RECENT_COUNT, parent.size()));
            }
            default -> List.of();
        };
    }

    private static void assertAllowedToolsExist(Skill skill, ToolRegistry registry) {
        List<String> allowed = skill.meta().allowedTools();
        if (allowed == null || allowed.isEmpty()) {
            return;
        }
        for (String name : allowed) {
            if (registry.get(name) == null) {
                throw new IllegalStateException(
                        "Skill '" + skill.meta().name() + "' declares allowed tool '"
                                + name + "' which is not registered");
            }
        }
    }
}
