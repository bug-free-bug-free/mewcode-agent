// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.teams;

import com.mewcode.agent.Agent;
import com.mewcode.agent.AgentEvent;
import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.llm.LlmClient;
import com.mewcode.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * Manages multi-agent teams with mailbox-based communication.
 */
public class TeamManager {

    public enum TeamMode { IN_PROCESS, TMUX }

    private final Map<String, Team> teams = new LinkedHashMap<>();

    public synchronized Team createTeam(String name, TeamMode mode) {
        Team team = new Team(name, mode);
        teams.put(name, team);
        return team;
    }

    public synchronized Team getTeam(String name) {
        return teams.get(name);
    }

    public synchronized void deleteTeam(String name) {
        Team team = teams.remove(name);
        if (team != null) {
            team.stopAll();
        }
    }

    public synchronized List<String> listTeams() {
        return new ArrayList<>(teams.keySet());
    }

    public synchronized void closeAll() {
        for (Team team : teams.values()) {
            team.stopAll();
        }
        teams.clear();
    }

    public synchronized List<TeammateProgress> getAllTeammateProgress() {
        return teams.values().stream()
                .flatMap(t -> t.getTeammateProgressList().stream())
                .toList();
    }

    public static TeamMode detectBackend() {
        return TeamMode.IN_PROCESS;
    }

    public static TeamMode detectPaneBackend() {
        if (System.getenv("TMUX") != null && !System.getenv("TMUX").isEmpty()) {
            return TeamMode.TMUX;
        }
        try {
            Process p = new ProcessBuilder("which", "tmux").start();
            if (p.waitFor() == 0) return TeamMode.TMUX;
        } catch (Exception ignored) {}
        return TeamMode.IN_PROCESS;
    }

    // ── Inner classes ──────────────────────────────────────────────────

    private static Path teamsBaseDir() {
        return Path.of(System.getProperty("user.dir"), ".mewcode", "teams");
    }

    public static class Team {
        final String name;
        final TeamMode mode;
        final Map<String, Member> members = new LinkedHashMap<>();
        private final FileMailBox mailBox;

        public Team(String name, TeamMode mode) {
            this.name = name;
            this.mode = mode;
            this.mailBox = new FileMailBox(teamsBaseDir().resolve(name).resolve("inboxes"));
        }

        public String getName() { return name; }
        public TeamMode getMode() { return mode; }

        public FileMailBox getMailBox() { return mailBox; }

        public synchronized Member addMember(String name, LlmClient client, ToolRegistry registry,
                                             String protocol, ProviderConfig cfg) {
            Agent ag = new Agent(client, registry, protocol, cfg);
            Member member = new Member(name, ag, new ConversationManager());
            members.put(name, member);
            return member;
        }

        public synchronized BlockingQueue<AgentEvent> startMember(String name, String task) {
            Member member = members.get(name);
            if (member == null) return null;
            member.conv.addUserMessage(task);
            BlockingQueue<AgentEvent> queue = member.agent.run(member.conv);
            member.active = true;
            return queue;
        }

        public synchronized void stopMember(String name) {
            Member member = members.get(name);
            if (member != null) {
                member.active = false;
                if (member.thread != null) {
                    member.thread.interrupt();
                }
            }
        }

        public synchronized void stopAll() {
            for (Member m : members.values()) {
                m.active = false;
                if (m.thread != null) m.thread.interrupt();
            }
        }

        public synchronized Member getMember(String name) {
            return members.get(name);
        }

        public synchronized boolean hasMember(String name) {
            return members.containsKey(name);
        }

        public synchronized List<String> memberNames() {
            return new ArrayList<>(members.keySet());
        }

        public void sendMessage(String from, String to, String content) {
            mailBox.send(to, new FileMailBox.MailMessage(from, content));
        }

        public List<TeammateProgress> getTeammateProgressList() {
            return members.values().stream()
                    .filter(m -> m.progress != null)
                    .map(m -> m.progress)
                    .toList();
        }
    }

    public static class Member {
        public final String name;
        public final Agent agent;
        public final ConversationManager conv;
        public volatile boolean active;
        public volatile Thread thread;
        public TeammateProgress progress;

        public Member(String name, Agent agent, ConversationManager conv) {
            this.name = name;
            this.agent = agent;
            this.conv = conv;
        }

        public String getName() { return name; }
        public boolean isActive() { return active; }
    }

}
