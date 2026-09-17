// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.prompt;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Assembles a system prompt from prioritized sections.
 */
public class PromptBuilder {

    // ── Inner types ─────────────────────────────────────────────────────

    public record Section(String name, int priority, String content) {}

    public record EnvironmentContext(
            String workDir,
            String os,
            String arch,
            String shell,
            boolean isGitRepo,
            String gitBranch,
            String model,
            String date) {}

    public record BuildOptions(
            String skillSection) {}

    // ── Builder state ───────────────────────────────────────────────────

    private final List<Section> sections = new ArrayList<>();

    public PromptBuilder add(Section section) {
        sections.add(section);
        return this;
    }

    public String build() {
        sections.sort(Comparator.comparingInt(Section::priority));

        var parts = new ArrayList<String>();
        for (Section s : sections) {
            String content = s.content() == null ? "" : s.content().strip();
            if (!content.isEmpty()) {
                parts.add(content);
            }
        }
        return String.join("\n\n", parts);
    }

    // ── Static convenience methods ──────────────────────────────────────

    /** Detect the current runtime environment. */
    public static EnvironmentContext detectEnvironment(String model) {
        String workDir = System.getProperty("user.dir");
        String osName = System.getProperty("os.name", "unknown").toLowerCase();
        String arch = System.getProperty("os.arch", "unknown");
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isEmpty()) {
            shell = "bash";
        }

        boolean isGitRepo = false;
        String gitBranch = "";

        try {
            Process p = new ProcessBuilder("git", "-C", workDir, "rev-parse", "--is-inside-work-tree")
                    .redirectErrorStream(true)
                    .start();
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                if ("true".equals(line != null ? line.strip() : "")) {
                    isGitRepo = true;
                }
            }
            p.waitFor();
        } catch (Exception ignored) {
            // not a git repo or git not available
        }

        if (isGitRepo) {
            try {
                Process p = new ProcessBuilder("git", "-C", workDir, "rev-parse", "--abbrev-ref", "HEAD")
                        .redirectErrorStream(true)
                        .start();
                try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null) {
                        gitBranch = line.strip();
                    }
                }
                p.waitFor();
            } catch (Exception ignored) {
                // branch detection failed
            }
        }

        String date = LocalDate.now().toString();
        return new EnvironmentContext(workDir, osName, arch, shell, isGitRepo, gitBranch, model, date);
    }

    /** Build a complete system prompt from the environment and options. */
    public static String buildSystemPrompt(EnvironmentContext env, BuildOptions options) {
        var builder = new PromptBuilder();

        builder.add(PromptSections.identitySection());
        builder.add(PromptSections.systemSection());
        builder.add(PromptSections.doingTasksSection());
        builder.add(PromptSections.executingActionsSection());
        builder.add(PromptSections.usingToolsSection());
        builder.add(PromptSections.toneStyleSection());
        builder.add(PromptSections.outputEfficiencySection());
        builder.add(PromptSections.environmentSection(env));

        if (options.skillSection() != null && !options.skillSection().isEmpty()) {
            builder.add(new Section("Skills", 90, options.skillSection()));
        }

        return builder.build();
    }
}
