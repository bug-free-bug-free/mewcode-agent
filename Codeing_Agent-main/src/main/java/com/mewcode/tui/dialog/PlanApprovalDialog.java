// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.tui.dialog;

import com.mewcode.tui.Styles;
import com.williamcallahan.tui4j.compat.bubbletea.lipgloss.Style;
import com.williamcallahan.tui4j.compat.bubbletea.lipgloss.color.ANSI256Color;

/**
 * TUI dialog shown when plan mode completes. Offers three choices:
 * <ol>
 *   <li>Execute plan in YOLO mode (bypass all permissions)</li>
 *   <li>Execute plan with manual approval for each edit</li>
 *   <li>Send feedback text back to the agent</li>
 * </ol>
 * <p>
 * This is a plain-string renderer and key handler, not a TUI4J component.
 * It is driven by {@code MewCodeModel}, which calls {@link #handleKey(String)}
 * on every key press and {@link #render()} each frame.
 *
 * @see com.mewcode.tui.MewCodeModel
 */
public class PlanApprovalDialog {

    /** Which of the three options the cursor is on (0-2). */
    private int cursor;

    /** Text input buffer for option 2 ("Send feedback"). */
    private final StringBuilder feedbackInput = new StringBuilder();

    /** Whether the dialog is currently visible. */
    private boolean active;

    // ── Styles (inline, matching Go renderPlanApprovalDialog) ────────────
    private static final Style HEADER_STYLE = Style.newStyle()
            .foreground(new ANSI256Color(99))
            .bold(true);

    private static final Style CURSOR_STYLE = Style.newStyle()
            .foreground(new ANSI256Color(99));

    private static final Style DIM_STYLE = Style.newStyle()
            .foreground(new ANSI256Color(242));

    private static final Style BOLD_STYLE = Style.newStyle()
            .bold(true);

    // ── Option labels (matching Go) ─────────────────────────────────────
    private static final String[] OPTIONS = {
            "Yes, enter YOLO mode (auto-approve all)",
            "Yes, manually approve edits",
            "Tell MewCode what to change",
    };

    // ── Result types ────────────────────────────────────────────────────

    /** The kind of action the user chose. */
    public enum Result {
        /** Bypass all permissions (YOLO). */
        YOLO,
        /** Approve each edit manually. */
        MANUAL,
        /** Send feedback text back to the agent. */
        FEEDBACK,
        /** User pressed escape; cancel the dialog. */
        CANCEL
    }

    /**
     * Immutable result returned when the user makes a choice.
     *
     * @param type     which action was selected
     * @param feedback the feedback string (only meaningful when {@code type == FEEDBACK})
     */
    public record DialogResult(Result type, String feedback) {}

    // ── Lifecycle ───────────────────────────────────────────────────────

    /**
     * Show the dialog, resetting cursor and feedback input.
     */
    public void activate() {
        active = true;
        cursor = 0;
        feedbackInput.setLength(0);
    }

    /** @return {@code true} while the dialog is visible */
    public boolean isActive() {
        return active;
    }

    // ── Key handling ────────────────────────────────────────────────────

    /**
     * Process a single key press.
     *
     * @param key the key string from {@code KeyPressMessage} (e.g. "up", "enter", "a")
     * @return a {@link DialogResult} if the user made a final choice, or {@code null}
     *         if the dialog remains open (cursor moved, text typed, etc.)
     */
    public DialogResult handleKey(String key) {
        switch (key) {
            case "up", "k" -> {
                if (cursor > 0) cursor--;
            }
            case "down", "j" -> {
                if (cursor < 2) cursor++;
            }
            case "enter" -> {
                if (cursor == 2 && feedbackInput.isEmpty()) {
                    // No feedback typed yet -- stay on the field
                    return null;
                }
                active = false;
                return switch (cursor) {
                    case 0 -> new DialogResult(Result.YOLO, "");
                    case 1 -> new DialogResult(Result.MANUAL, "");
                    case 2 -> new DialogResult(Result.FEEDBACK, feedbackInput.toString());

                    default -> null;
                };
            }
            case "shift+tab" -> {
                // Approve with feedback (Go: shift+tab on option 2 sends feedback AND exits plan mode)
                if (cursor == 2 && !feedbackInput.isEmpty()) {
                    active = false;
                    return new DialogResult(Result.FEEDBACK, feedbackInput.toString());
                }
            }
            case "escape" -> {
                active = false;
                return new DialogResult(Result.CANCEL, "");
            }
            case "backspace" -> {
                if (cursor == 2 && !feedbackInput.isEmpty()) {
                    feedbackInput.deleteCharAt(feedbackInput.length() - 1);
                }
            }
            default -> {
                if (cursor == 2 && key.length() == 1) {
                    char ch = key.charAt(0);
                    if (ch >= 32 && ch <= 126) {
                        feedbackInput.append(ch);
                    }
                } else if (cursor == 2 && " ".equals(key)) {
                    feedbackInput.append(' ');
                }
            }
        }
        return null;
    }

    // ── Rendering ───────────────────────────────────────────────────────

    /**
     * Render the dialog as a plain String (ANSI-styled).
     * Layout matches the Go {@code renderPlanApprovalDialog}.
     */
    public String render() {
        var sb = new StringBuilder();

        // Header
        sb.append(HEADER_STYLE.render(
                " MewCode has written up a plan and is ready to execute. Would you like to proceed?"));
        sb.append("\n\n");

        // Options list
        for (int i = 0; i < OPTIONS.length; i++) {
            // Cursor prefix
            String prefix;
            if (i == cursor) {
                prefix = CURSOR_STYLE.render(" ❯ ");
            } else {
                prefix = "   ";
            }

            // Label
            String label;
            if (i == cursor) {
                label = BOLD_STYLE.render(OPTIONS[i]);
            } else {
                label = DIM_STYLE.render(OPTIONS[i]);
            }

            sb.append(prefix);
            sb.append(String.format("%d. %s", i + 1, label));
            sb.append('\n');

            // Feedback input field (only on option 2)
            if (i == 2) {
                String inputLine = feedbackInput.toString();
                if (cursor == 2) {
                    inputLine += "█"; // block cursor
                }
                if ((cursor == 2 && inputLine.equals("█")) || inputLine.isEmpty()) {
                    // Show placeholder when empty
                    if (cursor == 2) {
                        String placeholder = DIM_STYLE.render("Type feedback here...");
                        sb.append("      ").append(placeholder).append('\n');
                    }
                } else {
                    sb.append("      ").append(inputLine).append('\n');
                }
                // Hint
                String hint = DIM_STYLE.render("      shift+tab to approve with this feedback");
                sb.append(hint);
                sb.append('\n');
            }
        }

        sb.append('\n');
        return sb.toString();
    }
}
