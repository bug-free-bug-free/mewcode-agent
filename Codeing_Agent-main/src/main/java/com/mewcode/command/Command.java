// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.command;

/**
 * A slash command definition.
 * Ported from Go: internal/commands/commands.go (Command struct).
 *
 * @param name        canonical name without the leading slash (e.g. "help")
 * @param description one-line description shown in /help output
 * @param aliases     alternative names (e.g. {"h", "?"} for help)
 * @param type        how the command is dispatched
 * @param hidden      if true, omitted from /help listings
 */
public record Command(
        String name,
        String description,
        String[] aliases,
        CommandType type,
        boolean hidden
) {

    /** Dispatch style for a command. */
    public enum CommandType {
        /** Synchronous handler that returns text output. */
        LOCAL,
        /** TUI action (clear screen, mode switch) -- no text output. */
        LOCAL_UI,
        /** Generates a prompt string sent to the LLM agent. */
        PROMPT
    }

    /**
     * Returns {@code true} when {@code input} matches the canonical name
     * or any alias (exact, case-sensitive comparison).
     */
    public boolean matches(String input) {
        if (name.equals(input)) {
            return true;
        }
        for (var alias : aliases) {
            if (alias.equals(input)) {
                return true;
            }
        }
        return false;
    }
}

