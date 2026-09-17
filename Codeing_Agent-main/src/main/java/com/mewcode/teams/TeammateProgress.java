// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.teams;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

public class TeammateProgress {
    public static class ToolActivity {
        public final String toolName;
        public final String description;
        public ToolActivity(String toolName, Map<String, Object> input) {
            this.toolName = toolName;
            this.description = describeActivity(toolName, input);
        }
        private static String describeActivity(String toolName, Map<String, Object> input) {
            return switch (toolName) {
                case "ReadFile" -> "Reading " + input.getOrDefault("file_path", "");
                case "EditFile" -> "Editing " + input.getOrDefault("file_path", "");
                case "WriteFile" -> "Writing " + input.getOrDefault("file_path", "");
                case "Bash" -> {
                    String cmd = String.valueOf(input.getOrDefault("command", ""));
                    yield "Running " + (cmd.length() > 40 ? cmd.substring(0, 40) + "…" : cmd);
                }
                case "Glob" -> "Searching " + input.getOrDefault("pattern", "");
                case "Grep" -> "Grepping " + input.getOrDefault("pattern", "");
                default -> toolName;
            };
        }
    }

    private int toolUseCount = 0;
    private long tokenCount = 0;
    private ToolActivity lastActivity;
    private final Deque<ToolActivity> recentActivities = new ArrayDeque<>();
    private String status = "running"; // running|idle|completed|failed|stopped
    private final String name;
    private final String teamName;
    private final long startTime = System.currentTimeMillis();
    private final String spinnerVerb;

    public TeammateProgress(String name, String teamName, String spinnerVerb) {
        this.name = name;
        this.teamName = teamName;
        this.spinnerVerb = spinnerVerb;
    }

    public synchronized void recordToolUse(String toolName, Map<String, Object> input) {
        toolUseCount++;
        ToolActivity act = new ToolActivity(toolName, input);
        lastActivity = act;
        recentActivities.addLast(act);
        if (recentActivities.size() > 5) recentActivities.removeFirst();
    }

    public synchronized void recordTokens(long input, long output) {
        tokenCount = input + output;
    }

    public synchronized void setStatus(String s) { status = s; }

    // Getters
    public String getName() { return name; }
    public String getTeamName() { return teamName; }
    public int getToolUseCount() { return toolUseCount; }
    public long getTokenCount() { return tokenCount; }
    public String getStatus() { return status; }
    public String getSpinnerVerb() { return spinnerVerb; }
    public ToolActivity getLastActivity() { return lastActivity; }

    public synchronized String getActivitySummary() {
        if (lastActivity != null) return lastActivity.description;
        return spinnerVerb;
    }

    public static String formatTokens(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fk", n / 1_000.0);
        return String.valueOf(n);
    }
}
