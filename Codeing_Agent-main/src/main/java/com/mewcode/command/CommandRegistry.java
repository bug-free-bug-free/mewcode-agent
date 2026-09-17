// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.command;

import com.mewcode.command.Command.CommandType;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Central registry for all slash commands.
 * Ported from Go: internal/commands/commands.go (Registry + CreateDefaultRegistry).
 */
public class CommandRegistry {

    private final List<Command> commands = new ArrayList<>();
    private final Map<String, Function<CommandContext, String>> handlers = new HashMap<>();

    /** Creates a registry pre-populated with the default MewCode commands. */
    public CommandRegistry() {
        registerDefaults();
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Registers a command with an optional handler.
     *
     * @param cmd     command definition
     * @param handler handler function (args -> output); may be {@code null} for UI-only commands
     */
    public void register(Command cmd, Function<CommandContext, String> handler) {
        commands.add(cmd);
        if (handler != null) {
            handlers.put(cmd.name(), handler);
            for (var alias : cmd.aliases()) {
                handlers.put(alias, handler);
            }
        }
    }

    /**
     * Returns all non-hidden commands whose name starts with {@code prefix}
     * (case-insensitive comparison).
     */
    public List<Command> search(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return commands.stream()
                .filter(c -> !c.hidden())
                .filter(c -> {
                    if (c.name().toLowerCase(Locale.ROOT).startsWith(lower)) {
                        return true;
                    }
                    for (var alias : c.aliases()) {
                        if (alias.toLowerCase(Locale.ROOT).startsWith(lower)) {
                            return true;
                        }
                    }
                    return false;
                })
                .sorted(Comparator.comparing(Command::name))
                .collect(Collectors.toList());
    }

    /** Finds a command by exact name or alias match. */
    public Optional<Command> find(String name) {
        return commands.stream()
                .filter(c -> c.matches(name))
                .findFirst();
    }

    /**
     * Executes a LOCAL command handler and returns its output.
     *
     * @param name command name or alias
     * @param args arguments passed after the command name
     * @return handler output, or an error message if not found / no handler
     */
    public String execute(String name, CommandContext ctx) {
        Function<CommandContext, String> handler = handlers.get(name);
        if (handler != null) {
            return handler.apply(ctx);
        }
        Optional<Command> cmd = find(name);
        if (cmd.isEmpty()) {
            return "Unknown command: " + name;
        }
        handler = handlers.get(cmd.get().name());
        if (handler != null) {
            return handler.apply(ctx);
        }
        return "No handler registered for /" + name;
    }

    /** Returns an unmodifiable view of all registered commands. */
    public List<Command> listAll() {
        return Collections.unmodifiableList(commands);
    }

    /** Returns all non-hidden commands, sorted by name. */
    public List<Command> listVisible() {
        return commands.stream()
                .filter(c -> !c.hidden())
                .sorted(Comparator.comparing(Command::name))
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------
    // Default command registration
    // ------------------------------------------------------------------

    private void registerDefaults() {
        // /help (LOCAL, aliases: h, ?)
        register(
                new Command("help", "Show available commands",
                        new String[]{"h", "?"}, CommandType.LOCAL, false),
                ctx -> {
                    String args = ctx.args();
                    if (args != null && !args.isBlank()) {
                        Optional<Command> target = find(args.strip());
                        if (target.isEmpty()) {
                            return "Unknown command: " + args.strip();
                        }
                        Command c = target.get();
                        var sb = new StringBuilder();
                        sb.append("/").append(c.name()).append(" — ").append(c.description()).append("\n");
                        if (c.aliases().length > 0) {
                            sb.append("  Aliases: ").append(String.join(", ", c.aliases())).append("\n");
                        }
                        return sb.toString();
                    }
                    var sb = new StringBuilder();
                    sb.append("Available commands:\n\n");
                    for (var cmd : listVisible()) {
                        String aliases = "";
                        if (cmd.aliases().length > 0) {
                            aliases = ", /" + String.join(", /", cmd.aliases());
                        }
                        sb.append("  /").append(cmd.name()).append(aliases).append("\n");
                        sb.append("    ").append(cmd.description()).append("\n");
                    }
                    sb.append("\nType /help <command> for details.");
                    return sb.toString();
                }
        );

        // /mcp (LOCAL)
        register(
                new Command("mcp", "Show MCP server status",
                        new String[]{}, CommandType.LOCAL, false),
                ctx -> {
                    if (ctx.mcpInfo() == null) return "No MCP servers configured";
                    String info = ctx.mcpInfo().get();
                    return info.isEmpty() ? "No MCP servers connected" : info;
                }
        );

        // /clear (LOCAL_UI)
        register(
                new Command("clear", "Clear conversation and start fresh",
                        new String[]{}, CommandType.LOCAL_UI, false),
                null
        );

        // /compact (LOCAL_UI, alias: c)
        register(
                new Command("compact", "Compress conversation context",
                        new String[]{"c"}, CommandType.LOCAL_UI, false),
                null
        );

        // /status (LOCAL, alias: s)
        register(
                new Command("status", "Show current status",
                        new String[]{"s"}, CommandType.LOCAL, false),
                ctx -> {
                    var sb = new StringBuilder();
                    sb.append("MewCode Status\n");
                    sb.append("──────────────\n");
                    sb.append("  Mode:      ").append(ctx.permissionMode().get()).append("\n");
                    int[] tokens = ctx.tokenCount().get();
                    sb.append("  Tokens:    ").append(tokens[0]).append(" in / ").append(tokens[1]).append(" out\n");
                    sb.append("  Tools:     ").append(ctx.toolCount().getAsInt()).append(" enabled\n");
                    var memories = ctx.memoryList().get();
                    sb.append("  Memories:  ").append(memories.size()).append(" entries\n");
                    sb.append("  Model:     ").append(ctx.model()).append("\n");
                    sb.append("  Directory: ").append(ctx.workDir()).append("\n");
                    return sb.toString();
                }
        );

        // /memory (LOCAL)
        register(
                new Command("memory", "Manage auto-memories",
                        new String[]{}, CommandType.LOCAL, false),
                ctx -> {
                    String args = ctx.args();
                    String sub = (args == null || args.isBlank()) ? "list" : args.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
                    return switch (sub) {
                        case "list" -> {
                            var memories = ctx.memoryList().get();
                            if (memories.isEmpty()) yield "No memories stored yet.";
                            var sb = new StringBuilder("Auto-memories (%d):\n".formatted(memories.size()));
                            for (var m : memories) sb.append("  • ").append(m).append("\n");
                            yield sb.toString();
                        }
                        case "clear" -> { ctx.memoryClear().run(); yield "All auto-memories cleared."; }
                        default -> "Usage: /memory [list|clear]";
                    };
                }
        );

        // /plan (LOCAL_UI, alias: p)
        register(
                new Command("plan", "Switch to plan mode (read-only)",
                        new String[]{"p"}, CommandType.LOCAL_UI, false),
                null
        );

        // /session (LOCAL)
        register(
                new Command("session", "Session management",
                        new String[]{}, CommandType.LOCAL, false),
                ctx -> {
                    String args = ctx.args();
                    String sub = (args == null || args.isBlank()) ? "info" : args.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
                    return switch (sub) {
                        case "info" -> ctx.sessionInfo().get();
                        case "list" -> ctx.sessionInfo().get();

                        default -> "Usage: /session [list|info]";
                    };
                }
        );

        // /permission (LOCAL, alias: perm)
        register(
                new Command("permission", "Permission management",
                        new String[]{"perm"}, CommandType.LOCAL, false),
                ctx -> {
                    String args = ctx.args();
                    String sub = (args == null || args.isBlank()) ? "info" : args.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
                    return switch (sub) {
                        case "info" -> "Current permission mode: " + ctx.permissionMode().get();
                        case "mode" -> "Usage: /permission mode <default|acceptEdits|plan|bypassPermissions>";

                        default -> "Usage: /permission [info|mode <mode>|rules]";
                    };
                }
        );

        // /resume (LOCAL_UI, alias: r)
        register(
                new Command("resume", "Resume a previous session",
                        new String[]{"r"}, CommandType.LOCAL_UI, false),
                null
        );

        // /rewind (LOCAL_UI)
        register(
                new Command("rewind", "Rewind to a previous checkpoint",
                        new String[]{}, CommandType.LOCAL_UI, false),
                null
        );

        // /skills (LOCAL)
        register(
                new Command("skills", "List available skills",
                        new String[]{}, CommandType.LOCAL, false),
                ctx -> {
                    var skills = ctx.skillList().get();
                    if (skills.isEmpty()) return "No skills installed.\n\nAdd skills to .mewcode/skills/<skill-name>/SKILL.md";
                    var sb = new StringBuilder("Installed skills (%d):\n".formatted(skills.size()));
                    for (var s : skills) sb.append("  • ").append(s).append("\n");
                    return sb.toString();
                }
        );

        // /review (PROMPT)
        register(
                new Command("review", "Review current code changes",
                        new String[]{}, CommandType.PROMPT, false),
                ctx -> {
                    String args = ctx.args();
                    String prompt = "Please review the current git diff for code changes. Focus on:\n"
                            + "1. Logic errors\n2. Security issues\n3. Performance problems\n4. Code style";
                    if (args != null && !args.isBlank()) {
                        prompt += "\n\nAdditional focus: " + args.strip();
                    }
                    return prompt;
                }
        );
    }
}
