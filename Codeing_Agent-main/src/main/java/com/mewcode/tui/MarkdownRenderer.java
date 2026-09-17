// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.tui;

import java.util.regex.Pattern;

public final class MarkdownRenderer {

    private MarkdownRenderer() {}

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String ITALIC = "\033[3m";

    private static final String UNDERLINE = "\033[4m";
    private static final String PURPLE = "\033[38;5;99m";
    private static final String CYAN = "\033[38;5;80m";
    private static final String GREEN = "\033[38;5;78m";
    private static final String YELLOW = "\033[38;5;214m";

    private static final String GRAY = "\033[38;5;242m";

    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*");

    private static final Pattern ITALIC_PATTERN = Pattern.compile("(?<![*])\\*(.+?)\\*(?![*])");
    private static final Pattern CODE_PATTERN = Pattern.compile("`([^`]+)`");
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^]]+)]\\(([^)]+)\\)");

    public static String render(String markdown, int width) {
        if (markdown == null || markdown.isEmpty()) return "";

        var sb = new StringBuilder();
        var lines = markdown.split("\n", -1);
        boolean inCodeBlock = false;
        String codeBlockLang = "";

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (line.startsWith("```")) {
                if (!inCodeBlock) {
                    inCodeBlock = true;
                    codeBlockLang = line.length() > 3 ? line.substring(3).trim() : "";
                    sb.append(GRAY);
                    if (!codeBlockLang.isEmpty()) {
                        sb.append("  ┌─ ").append(codeBlockLang).append(" ");
                    }
                    String border = "─".repeat(Math.max(width - 10 - codeBlockLang.length(), 10));
                    sb.append(border).append(RESET).append("\n");
                } else {
                    inCodeBlock = false;
                    sb.append(GRAY).append("  └").append("─".repeat(Math.max(width - 6, 10)));
                    sb.append(RESET).append("\n");
                }
                continue;
            }

            if (inCodeBlock) {
                sb.append(GRAY).append("  │ ").append(RESET);
                sb.append(DIM).append(line).append(RESET).append("\n");
                continue;
            }

            if (line.startsWith("### ")) {
                sb.append(BOLD).append(PURPLE).append("  ").append(line.substring(4)).append(RESET).append("\n");
            } else if (line.startsWith("## ")) {
                sb.append(BOLD).append(PURPLE).append("  ").append(line.substring(3)).append(RESET).append("\n");
            } else if (line.startsWith("# ")) {
                sb.append(BOLD).append(PURPLE).append("  ").append(line.substring(2)).append(RESET).append("\n");
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                sb.append("  ").append(GREEN).append("•").append(RESET).append(" ");
                sb.append(renderInline(line.substring(2))).append("\n");
            } else if (line.matches("^\\d+\\. .*")) {
                int dot = line.indexOf(". ");
                sb.append("  ").append(CYAN).append(line.substring(0, dot + 1)).append(RESET).append(" ");
                sb.append(renderInline(line.substring(dot + 2))).append("\n");
            } else if (line.startsWith("> ")) {
                sb.append(GRAY).append("  │ ").append(ITALIC).append(line.substring(2)).append(RESET).append("\n");
            } else if (line.startsWith("---") || line.startsWith("***")) {
                sb.append(GRAY).append("  ").append("─".repeat(Math.max(width - 6, 10))).append(RESET).append("\n");
            } else if (line.isBlank()) {
                sb.append("\n");
            } else {
                sb.append("  ").append(renderInline(line)).append("\n");
            }
        }

        return sb.toString();
    }

    private static String renderInline(String text) {
        text = BOLD_PATTERN.matcher(text).replaceAll(BOLD + "$1" + RESET);
        text = ITALIC_PATTERN.matcher(text).replaceAll(ITALIC + "$1" + RESET);
        text = CODE_PATTERN.matcher(text).replaceAll(CYAN + "`$1`" + RESET);
        text = LINK_PATTERN.matcher(text).replaceAll(UNDERLINE + CYAN + "$1" + RESET);
        return text;
    }
}
