// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.teams;

import java.util.Set;

/**
 * Coordinator mode restricts the Lead agent's tools to coordination-only.
 * When active, Lead can only use a limited set of tools.
 *
 * Four-phase workflow:
 * 1. Research: Lead explores the problem space
 * 2. Synthesis: Lead creates a plan and task decomposition
 * 3. Implementation: Lead spawns teammates to execute tasks
 * 4. Verification: Lead verifies results and resolves conflicts
 */
public final class Coordinator {

    private Coordinator() {}

    public static final Set<String> ALLOWED_TOOLS = Set.of(
            "Agent",
            "SendMessage",
            "TaskCreate",
            "TaskGet",
            "TaskList",
            "TaskUpdate",
            "TeamCreate",
            "TeamDelete",
            "ReadFile",
            "Glob",
            "Grep",
            "Bash"
    );

    public static boolean isCoordinatorTool(String name) {
        return ALLOWED_TOOLS.contains(name);
    }
}
