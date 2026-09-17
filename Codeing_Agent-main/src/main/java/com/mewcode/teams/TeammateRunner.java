// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.teams;

import com.mewcode.agent.AgentEvent;
import com.mewcode.tui.SpinnerVerbs;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Main loop for in-process teammates.
 */
public final class TeammateRunner {

    public static final String LEAD_NAME = "lead";
    public static final String SHUTDOWN_PREFIX = "[shutdown]";

    public static final long IDLE_POLL_MS = 500;

    private TeammateRunner() {}

    /**
     * Runs a teammate agent loop in the current thread. Blocks until shutdown
     * or context cancellation (thread interrupt).
     */
    public static void runInProcessTeammate(
            TeamManager.Team team,
            TeamManager.Member member,
            String initialPrompt,
            String addendum
    ) {
        BlockingQueue<AgentEvent> eventOut = new LinkedBlockingQueue<>(32);

        // Create progress tracker and attach to member
        var progress = new TeammateProgress(member.getName(), team.getName(), SpinnerVerbs.random());
        member.progress = progress;

        if (addendum != null && !addendum.isEmpty()) {
            member.conv.addSystemReminder(addendum);
        }

        // Inject any pending mailbox messages
        injectPendingMessages(team, member.getName(), member.conv);

        // First turn: use initial prompt
        member.conv.addUserMessage(initialPrompt);

        // Run agent
        var agentQueue = member.agent.run(member.conv);
        drainAgentEvents(agentQueue, eventOut, progress);

        // Send idle notification
        team.sendMessage(member.getName(), LEAD_NAME,
                createIdleNotification(member.getName(), "completed initial task"));

        // Subsequent turns: wait for mailbox messages
        while (!Thread.currentThread().isInterrupted()) {
            var result = waitForNextPromptOrShutdown(team, member.getName());
            if (result.shutdown || result.prompt == null) break;

            member.conv.addUserMessage(result.prompt);
            agentQueue = member.agent.run(member.conv);
            drainAgentEvents(agentQueue, eventOut, progress);

            team.sendMessage(member.getName(), LEAD_NAME,
                    createIdleNotification(member.getName(), "completed follow-up"));
        }

        member.active = false;
        progress.setStatus("completed");
    }

    /**
     * Drains lead's mailbox across all teams, returning formatted notification strings.
     * Called by the Lead's NotificationFn each iteration.
     */
    public static List<String> drainLeadMailbox(TeamManager teamMgr) {
        if (teamMgr == null) return List.of();
        var result = new java.util.ArrayList<String>();
        for (String teamName : teamMgr.listTeams()) {
            var team = teamMgr.getTeam(teamName);
            if (team == null) continue;
            var messages = team.getMailBox().readUnread(LEAD_NAME);
            if (messages.isEmpty()) continue;

            var sb = new StringBuilder();
            sb.append("<team-notification team=\"").append(teamName).append("\">\n");
            for (var msg : messages) {
                sb.append("from=").append(msg.from()).append(": ").append(msg.text()).append("\n");
            }
            sb.append("</team-notification>");
            result.add(sb.toString());

            team.getMailBox().markAllRead(LEAD_NAME);
        }
        return result;
    }

    /**
     * Builds the system reminder addendum for a teammate.
     */
    public static String buildTeammateAddendum(String teamName, String memberName, List<String> otherMembers) {
        var sb = new StringBuilder();
        sb.append("You are a member of team \"").append(teamName).append("\". ");
        sb.append("Your name is \"").append(memberName).append("\".\n\n");
        if (otherMembers != null && !otherMembers.isEmpty()) {
            sb.append("Other team members: ").append(String.join(", ", otherMembers)).append("\n\n");
        }
        sb.append("You can communicate with teammates using the SendMessage tool.\n");
        sb.append("Messages from teammates arrive as system reminders at the start of each turn.\n");
        sb.append("When you finish your current task, simply stop calling tools — ");
        sb.append("an idle notification will be sent to the lead automatically.");
        return sb.toString();
    }

    /**
     * Injects unread mailbox messages as a system reminder.
     */
    public static void injectPendingMessages(
            TeamManager.Team team, String memberName,
            com.mewcode.conversation.ConversationManager conv
    ) {
        var messages = team.getMailBox().readUnread(memberName);
        if (messages.isEmpty()) return;

        var sb = new StringBuilder("You have new messages:\n\n");
        for (var msg : messages) {
            sb.append("From ").append(msg.from()).append(": ").append(msg.text()).append("\n\n");
        }
        conv.addSystemReminder(sb.toString());
        team.getMailBox().markAllRead(memberName);
    }

    public static boolean isShutdownRequest(String message) {
        return message != null && message.strip().startsWith(SHUTDOWN_PREFIX);
    }

    public static String createIdleNotification(String memberName, String reason) {
        return "[idle] %s: %s (at %s)".formatted(memberName, reason,
                java.time.Instant.now().toString());
    }

    // ── Internal helpers ──────────────────────────────────────────────

    private record WaitResult(String prompt, boolean shutdown) {}

    private static WaitResult waitForNextPromptOrShutdown(TeamManager.Team team, String memberName) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(IDLE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new WaitResult(null, true);
            }

            var messages = team.getMailBox().readUnread(memberName);
            if (messages.isEmpty()) continue;

            for (var msg : messages) {
                if (isShutdownRequest(msg.text())) {
                    team.getMailBox().markAllRead(memberName);
                    return new WaitResult(null, true);
                }
            }

            // Format as prompt
            var sb = new StringBuilder("You have new messages from your team:\n\n");
            for (var msg : messages) {
                sb.append("From ").append(msg.from()).append(": ").append(msg.text()).append("\n\n");
            }
            team.getMailBox().markAllRead(memberName);
            return new WaitResult(sb.toString(), false);
        }
        return new WaitResult(null, true);
    }

    private static void drainAgentEvents(BlockingQueue<AgentEvent> source, BlockingQueue<AgentEvent> sink,
                                         TeammateProgress progress) {
        while (true) {
            AgentEvent event;
            try {
                event = source.poll(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                progress.setStatus("failed");
                return;
            }
            if (event == null) return;
            sink.offer(event);

            // Record progress from agent events
            if (event instanceof AgentEvent.ToolUseEvent tue) {
                progress.recordToolUse(tue.toolName(), tue.args());
            } else if (event instanceof AgentEvent.UsageEvent ue) {
                progress.recordTokens(ue.inputTokens(), ue.outputTokens());
            } else if (event instanceof AgentEvent.ErrorEvent) {
                progress.setStatus("failed");
                return;
            } else if (event instanceof AgentEvent.LoopComplete) {
                return;
            }
        }
    }
}
