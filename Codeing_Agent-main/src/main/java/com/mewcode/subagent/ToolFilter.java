// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.subagent;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolRegistry;

import java.util.HashSet;
import java.util.Set;

/**
 * Filters a {@link ToolRegistry} to produce a restricted registry suitable
 * for a sub-agent. The filtering layers (applied in order) are:
 * <ul>
 *   <li>Layer 1: MCP tools (prefixed with "mcp__") always pass through.</li>
 *   <li>Layer 2: {@code ALWAYS_DISALLOWED} — globally blocked tools
 *       (TaskOutput, ExitPlanMode, EnterPlanMode, Agent, AskUserQuestion,
 *       TaskStop, Workflow).</li>
 *   <li>Layer 3: If the agent is a custom agent, also block
 *       {@code CUSTOM_AGENT_DISALLOWED}.</li>
 *   <li>Layer 4: In async mode, only permit {@code ASYNC_ALLOWED} tools.
 *       However, if the agent is an in-process teammate, also allow "Agent"
 *       and the {@code IN_PROCESS_TEAMMATE_ALLOWED} tools.</li>
 *   <li>Layer 5: Per-spec {@code disallowedTools} exclusion.</li>
 *   <li>Layer 6: Per-spec {@code tools} whitelist intersection
 *       (skipped if null/empty or contains only "*").</li>
 * </ul>
 */
public final class ToolFilter {

    /** Tools that are never available to any sub-agent. */
    private static final Set<String> ALWAYS_DISALLOWED = Set.of(
            "TaskOutput", "ExitPlanMode", "EnterPlanMode",
            "Agent", "AskUserQuestion", "TaskStop", "Workflow"
    );

    /** Additional tools blocked for custom agents (same set as ALWAYS_DISALLOWED in Go). */
    private static final Set<String> CUSTOM_AGENT_DISALLOWED = Set.of(
            "TaskOutput", "ExitPlanMode", "EnterPlanMode",
            "Agent", "AskUserQuestion", "TaskStop", "Workflow"
    );

    /** Tools permitted for async (background) sub-agents. */
    private static final Set<String> ASYNC_ALLOWED = Set.of(
            "ReadFile", "WebSearch", "TodoWrite", "Grep", "WebFetch", "Glob",
            "Bash", "EditFile", "WriteFile", "NotebookEdit", "Skill", "LoadSkill",
            "SyntheticOutput", "ToolSearch", "EnterWorktree", "ExitWorktree"
    );

    /** Extra tools allowed when the agent is an in-process teammate. */
    private static final Set<String> IN_PROCESS_TEAMMATE_ALLOWED = Set.of(
            "TaskCreate", "TaskGet", "TaskList", "TaskUpdate", "SendMessage",
            "CronCreate", "CronDelete", "CronList"
    );

    private ToolFilter() {}

    /**
     * Convenience overload that delegates to the full method with
     * {@code isAsync=false}, {@code isCustom=false}, {@code isInProcessTeammate=false}.
     */
    public static ToolRegistry filterForAgent(ToolRegistry source, SubAgentSpec spec) {
        return filterForAgent(source, spec, false, false, false);
    }

    /**
     * Creates a new {@link ToolRegistry} containing only the tools that
     * the given sub-agent spec is allowed to use, matching the Go reference
     * implementation's {@code FilterToolsForAgentEx}.
     *
     * @param source              the parent registry to filter from
     * @param spec                the sub-agent specification whose disallowed/allowed tools to honour
     * @param isAsync             if {@code true}, restrict to the async allow-list
     * @param isCustom            if {@code true}, also block {@code CUSTOM_AGENT_DISALLOWED} tools
     * @param isInProcessTeammate if {@code true} (and async), additionally allow "Agent"
     *                            and {@code IN_PROCESS_TEAMMATE_ALLOWED} tools
     * @return a new filtered registry
     */
    public static ToolRegistry filterForAgent(ToolRegistry source, SubAgentSpec spec,
                                              boolean isAsync, boolean isCustom,
                                              boolean isInProcessTeammate) {
        Set<String> disallowed = new HashSet<>(spec.disallowedTools());

        boolean hasWhitelist = spec.tools() != null && !spec.tools().isEmpty()
                && !(spec.tools().size() == 1 && "*".equals(spec.tools().get(0)));
        Set<String> allowed = hasWhitelist ? new HashSet<>(spec.tools()) : Set.of();

        ToolRegistry filtered = new ToolRegistry();
        for (Tool tool : source.listTools()) {
            String name = tool.name();

            // Layer 1: MCP tools always pass through
            if (isMcpTool(name)) {
                filtered.register(tool);
                continue;
            }

            // Layer 2: Globally blocked tools
            if (ALWAYS_DISALLOWED.contains(name)) {
                continue;
            }

            // Layer 3: Custom-agent specific blocks
            if (isCustom && CUSTOM_AGENT_DISALLOWED.contains(name)) {
                continue;
            }

            // Layer 4: In async mode, only permit the allow-listed tools
            if (isAsync) {
                boolean asyncAllowed = ASYNC_ALLOWED.contains(name);
                if (!asyncAllowed) {
                    // In-process teammates get extra tools even in async mode
                    if (isInProcessTeammate
                            && ("Agent".equals(name) || IN_PROCESS_TEAMMATE_ALLOWED.contains(name))) {
                        // fall through — permitted
                    } else {
                        continue;
                    }
                }
            }

            // Layer 5: Per-spec disallowed tools
            if (disallowed.contains(name)) {
                continue;
            }

            // Layer 6: Per-spec whitelist intersection
            if (hasWhitelist && !allowed.contains(name)) {
                continue;
            }

            filtered.register(tool);
        }
        return filtered;
    }

    private static boolean isMcpTool(String name) {
        return name.startsWith("mcp__");
    }
}
