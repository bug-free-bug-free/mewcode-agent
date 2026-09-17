// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.teams;

import com.mewcode.llm.LlmClient;
import com.mewcode.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.List;

/**
 * Unified dispatcher for teammate spawning across all backends.
 */
public final class SpawnDispatcher {

    public record SpawnConfig(
            TeamManager.Team team,
            String memberName,
            String task,
            String addendum,
            LlmClient client,
            ToolRegistry registry,
            String protocol,
            com.mewcode.config.ProviderConfig providerConfig,
            String workdir
    ) {}

    public record SpawnResult(
            TeamManager.TeamMode mode,
            String paneId
    ) {}

    private SpawnDispatcher() {}

    public static SpawnResult spawnTeammate(SpawnConfig config) throws Exception {
        var team = config.team();
        var mode = team.getMode();

        switch (mode) {
            case IN_PROCESS -> {
                var member = team.addMember(config.memberName(), config.client(),
                        config.registry(), config.protocol(), config.providerConfig());
                if (config.workdir() != null) {
                    member.agent.setWorkDir(config.workdir());
                }
                member.active = true;
                member.thread = Thread.startVirtualThread(() ->
                        TeammateRunner.runInProcessTeammate(team, member, config.task(), config.addendum()));
                return new SpawnResult(mode, null);
            }
            case TMUX -> {
                // Write task to mailbox so first poll picks it up
                if (config.task() != null && !config.task().isEmpty()) {
                    team.sendMessage(TeammateRunner.LEAD_NAME, config.memberName(), config.task());
                }
                String cliCommand = buildTeammateCLI(team.getName(), config.memberName(), config.workdir());
                String paneId = TmuxBackend.spawnTmuxTeammate(team.getName(), config.memberName(), cliCommand);
                recordExternalMember(team, config.memberName(), paneId);
                return new SpawnResult(mode, paneId);
            }
            default -> throw new IllegalStateException("Unsupported team mode: " + mode);
        }
    }

    /**
     * Builds the shell command for a worker process.
     * Format: cd '<workdir>' && '<mewcode>' --teammate --team-name <t> --agent-name <n>
     */
    public static String buildTeammateCLI(String teamName, String memberName, String workdir) {
        String wd = workdir != null ? workdir : System.getProperty("user.dir");
        // Find mewcode executable (assume it's the current JAR or on PATH)
        String mewcode = ProcessHandle.current().info().command().orElse("mewcode");
        return "cd %s && %s --teammate --team-name %s --agent-name %s".formatted(
                shellQuote(wd), shellQuote(mewcode), shellQuote(teamName), shellQuote(memberName));
    }

    static String shellQuote(String s) {
        if (s.matches("[a-zA-Z0-9_./-]+")) return s;
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void recordExternalMember(TeamManager.Team team, String name, String paneId) {
        // For external backends, create a placeholder member
        var member = new TeamManager.Member(name, null, null);
        member.active = true;
        // Store paneId via field access (simple approach)
        synchronized (team) {
            team.members.put(name, member);
        }
    }
}
