// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.tui;

import com.williamcallahan.tui4j.compat.bubbletea.lipgloss.Style;
import com.williamcallahan.tui4j.compat.bubbletea.lipgloss.color.ANSI256Color;

/**
 * Terminal styles using TUI4J's lipgloss port.
 * Color palette matches the Go MewCode TUI.
 */
public final class Styles {

    private Styles() {}

    // ── Color constants (ANSI 256) ──────────────────────────────────────
    private static final ANSI256Color BRAND_PURPLE = new ANSI256Color(99);

    private static final ANSI256Color DIM_TEXT     = new ANSI256Color(242);
    private static final ANSI256Color MUTED_TEXT   = new ANSI256Color(245);
    private static final ANSI256Color NORMAL_TEXT  = new ANSI256Color(252);
    private static final ANSI256Color BRIGHT_TEXT  = new ANSI256Color(255);
    private static final ANSI256Color GREEN_TEXT   = new ANSI256Color(78);
    private static final ANSI256Color RED_TEXT     = new ANSI256Color(203);
    private static final ANSI256Color YELLOW_TEXT  = new ANSI256Color(214);
    private static final ANSI256Color CYAN_TEXT    = new ANSI256Color(80);
    private static final ANSI256Color SEPARATOR_COLOR = new ANSI256Color(236);

    // ── Banner / chrome ─────────────────────────────────────────────────
    public static final Style banner = Style.newStyle()
            .foreground(BRAND_PURPLE)
            .bold(true);

    public static final Style bannerDim = Style.newStyle()
            .foreground(DIM_TEXT);

    public static final Style separator = Style.newStyle()
            .foreground(SEPARATOR_COLOR);

    // ── Prompt / input ──────────────────────────────────────────────────
    public static final Style prompt = Style.newStyle()
            .foreground(CYAN_TEXT)
            .bold(true);

    // ── AI output ───────────────────────────────────────────────────────
    public static final Style aiMarker = Style.newStyle()
            .foreground(BRAND_PURPLE)
            .bold(true);

    public static final Style aiText = Style.newStyle()
            .foreground(NORMAL_TEXT);

    public static final Style streamingText = Style.newStyle()
            .foreground(NORMAL_TEXT);

    // ── Tool status ─────────────────────────────────────────────────────
    public static final Style toolRunning = Style.newStyle()
            .foreground(DIM_TEXT);

    public static final Style toolDone = Style.newStyle()
            .foreground(GREEN_TEXT);

    public static final Style toolError = Style.newStyle()
            .foreground(RED_TEXT);

    public static final Style toolDetail = Style.newStyle()
            .foreground(DIM_TEXT);

    // ── Errors ──────────────────────────────────────────────────────────
    public static final Style error = Style.newStyle()
            .foreground(RED_TEXT);

    // ── Permission dialog ───────────────────────────────────────────────
    public static final Style permBorder = Style.newStyle()
            .foreground(YELLOW_TEXT)
            .bold(true);

    public static final Style permDim = Style.newStyle()
            .foreground(DIM_TEXT);

    // ── System messages ────────────────────────────────────────────────
    public static final Style system = Style.newStyle()
            .foreground(DIM_TEXT);

    // ── Status bar ──────────────────────────────────────────────────────
    public static final Style statusBar = Style.newStyle()
            .foreground(DIM_TEXT);

    public static final Style statusItem = Style.newStyle()
            .foreground(MUTED_TEXT);

    // ── Permission mode indicators ──────────────────────────────────────
    public static final Style modeDefault = Style.newStyle()
            .foreground(DIM_TEXT);

    public static final Style modeAcceptEdits = Style.newStyle()
            .foreground(GREEN_TEXT);

    public static final Style modePlan = Style.newStyle()
            .foreground(YELLOW_TEXT);

    public static final Style modeBypass = Style.newStyle()
            .foreground(RED_TEXT)
            .bold(true);

    // ── Provider selection ──────────────────────────────────────────────
    public static final Style selectLabel = Style.newStyle()
            .foreground(BRAND_PURPLE)
            .bold(true);

    public static final Style selectedItem = Style.newStyle()
            .foreground(CYAN_TEXT)
            .bold(true);

    public static final Style normalItem = Style.newStyle()
            .foreground(MUTED_TEXT);

    // ── User input text ────────────────────────────────────────────
    public static final Style userText = Style.newStyle()
            .foreground(BRIGHT_TEXT)
            .bold(true);

    // ── Thinking indicator ─────────────────────────────────────────
    public static final Style thinking = Style.newStyle()
            .foreground(BRAND_PURPLE);

    // ── Input placeholder ──────────────────────────────────────────
    private static final ANSI256Color PLACEHOLDER_COLOR = new ANSI256Color(240);
    public static final Style placeholder = Style.newStyle()
            .foreground(PLACEHOLDER_COLOR);

    // ── Inline color helpers (for team spinner tree and other inline use) ──
    private static final Style inlineCyan = Style.newStyle().foreground(CYAN_TEXT);
    private static final Style inlineDim = Style.newStyle().foreground(DIM_TEXT);
    private static final Style inlineGreen = Style.newStyle().foreground(GREEN_TEXT);
    private static final Style inlineRed = Style.newStyle().foreground(RED_TEXT);
    private static final Style inlineYellow = Style.newStyle().foreground(YELLOW_TEXT);

    public static String cyan(String s) { return inlineCyan.render(s); }
    public static String dim(String s) { return inlineDim.render(s); }
    public static String green(String s) { return inlineGreen.render(s); }
    public static String red(String s) { return inlineRed.render(s); }
    public static String yellow(String s) { return inlineYellow.render(s); }
}
