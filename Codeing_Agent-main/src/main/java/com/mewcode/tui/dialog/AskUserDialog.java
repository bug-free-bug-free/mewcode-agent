// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.tui.dialog;

import com.williamcallahan.tui4j.compat.bubbletea.lipgloss.Style;
import com.williamcallahan.tui4j.compat.bubbletea.lipgloss.color.ANSI256Color;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * TUI dialog for multi-question surveys (the "ask user" tool).
 * <p>
 * Supports single-select, multi-select (checkbox), and free-text "Other" input
 * per question.  For multi-question surveys a tab-style navigation bar lets the
 * user move between questions before reviewing and submitting all answers.
 * <p>
 * This is a plain-string renderer and key handler -- not a TUI4J component.
 * It is driven by {@code MewCodeModel}, which calls {@link #handleKey(String)}
 * on each key press and {@link #render(int)} each frame.
 *
 * @see com.mewcode.tui.MewCodeModel
 */
public class AskUserDialog {

    // ── Styles (matching Go renderQuestionNavBar / renderQuestionView) ───
    private static final ANSI256Color BRAND_PURPLE = new ANSI256Color(99);
    private static final ANSI256Color DIM_TEXT     = new ANSI256Color(242);
    private static final ANSI256Color BRIGHT_TEXT  = new ANSI256Color(255);
    private static final ANSI256Color TAB_FG       = new ANSI256Color(250);

    private static final Style HEADER_STYLE = Style.newStyle()
            .foreground(BRAND_PURPLE).bold(true);
    private static final Style CURSOR_STYLE = Style.newStyle()
            .foreground(BRAND_PURPLE);
    private static final Style BOLD_STYLE = Style.newStyle()
            .bold(true);

    private static final Style DIM_STYLE = Style.newStyle()
            .foreground(DIM_TEXT);
    private static final Style ACTIVE_TAB = Style.newStyle()
            .background(BRAND_PURPLE).foreground(BRIGHT_TEXT).bold(true).padding(0, 1);
    private static final Style INACTIVE_TAB = Style.newStyle()
            .foreground(TAB_FG).padding(0, 1);
    private static final Style BRIGHT_ARROW = Style.newStyle()
            .foreground(BRAND_PURPLE).bold(true);
    private static final Style DIM_ARROW = Style.newStyle()
            .foreground(DIM_TEXT);

    // ── State ───────────────────────────────────────────────────────────
    private boolean active;
    private List<Question> questions;

    /** Index of the question currently displayed. */
    private int questionIndex;

    /** Per-question cursor position (index into options + "Other"). */
    private int[] cursors;

    /** Per-question set of selected option indices (for multi-select). */
    private List<Set<Integer>> selected;

    /** Per-question free-text "Other" input. */
    private String[] otherText;

    /** Collected answers keyed by question index. */
    private Map<Integer, String> answers;

    /** Whether we are on the final Submit / Cancel view. */
    private boolean onSubmitTab;

    /** 0 = Submit, 1 = Cancel on the submit view. */
    private int submitCursor;

    // ── Data records ────────────────────────────────────────────────────

    /**
     * A single survey question.
     *
     * @param text        the question text shown as header
     * @param header      short label for the tab bar (falls back to "Q{n}")
     * @param options     the selectable options
     * @param multiSelect if {@code true}, checkboxes; otherwise radio-style
     */
    public record Question(String text, String header, List<Option> options, boolean multiSelect) {}

    /**
     * A single selectable option within a question.
     *
     * @param label       display label
     * @param description short description shown to the right of the label
     */
    public record Option(String label, String description) {}

    // ── Lifecycle ───────────────────────────────────────────────────────

    /**
     * Show the dialog, resetting all state for a new set of questions.
     *
     * @param questions the list of questions to present
     */
    public void activate(List<Question> questions) {
        this.active = true;
        this.questions = questions != null ? questions : List.of();
        this.questionIndex = 0;

        int n = this.questions.size();
        this.cursors = new int[n];
        this.otherText = new String[n];
        this.selected = new ArrayList<>(n);
        this.answers = new HashMap<>();
        this.onSubmitTab = false;
        this.submitCursor = 0;

        for (int i = 0; i < n; i++) {
            otherText[i] = "";
            selected.add(new TreeSet<>());
        }
    }

    /** @return {@code true} while the dialog is visible */
    public boolean isActive() {
        return active;
    }

    // ── Key handling ────────────────────────────────────────────────────

    /**
     * Process a single key press.
     *
     * @param key the key string from {@code KeyPressMessage}
     * @return a map of (question text -> answer string) when the user submits,
     *         a singleton map {@code {"_declined" -> "true"}} on cancel,
     *         or {@code null} if the dialog remains open
     */
    public Map<String, String> handleKey(String key) {
        if (questions == null || questions.isEmpty()) {
            return null;
        }

        boolean multiQuestion = questions.size() > 1;

        // ── Submit tab ──────────────────────────────────────────────────
        if (onSubmitTab) {
            return handleSubmitTabKey(key, multiQuestion);
        }

        // ── Question view ───────────────────────────────────────────────
        Question q = questions.get(questionIndex);
        int optCount = q.options().size() + 1; // options + "Other"
        int cursor = cursors[questionIndex];

        switch (key) {
            case "up", "k" -> {
                if (cursor > 0) cursors[questionIndex]--;
            }
            case "down", "j" -> {
                if (cursor < optCount - 1) cursors[questionIndex]++;
            }
            case "left", "shift+tab" -> {
                if (multiQuestion && questionIndex > 0) {
                    questionIndex--;
                }
            }
            case "right", "tab" -> {
                if (multiQuestion) {
                    if (questionIndex < questions.size() - 1) {
                        questionIndex++;
                    } else {
                        onSubmitTab = true;
                        submitCursor = 0;
                    }
                }
            }
            case " " -> {
                if (q.multiSelect() && cursor < q.options().size()) {
                    Set<Integer> sel = selected.get(questionIndex);
                    if (sel.contains(cursor)) {
                        sel.remove(cursor);
                    } else {
                        sel.add(cursor);
                    }
                }
            }
            case "enter" -> {
                saveCurrentAnswer();
                // Single question, single-select: submit immediately
                if (!multiQuestion && !q.multiSelect()) {
                    return submitAllAnswers();
                }
                // Advance to next question or submit tab
                if (questionIndex < questions.size() - 1) {
                    questionIndex++;
                } else {
                    onSubmitTab = true;
                    submitCursor = 0;
                }
            }
            case "backspace" -> {
                if (cursor == q.options().size() && !otherText[questionIndex].isEmpty()) {
                    otherText[questionIndex] = otherText[questionIndex]
                            .substring(0, otherText[questionIndex].length() - 1);
                }
            }
            case "escape" -> {
                return cancelDialog();
            }
            default -> {
                // Typing into "Other" field
                if (cursor == q.options().size()) {
                    if (key.length() == 1) {
                        char ch = key.charAt(0);
                        if (ch >= 32 && ch <= 126) {
                            otherText[questionIndex] += ch;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Handle keys when the submit/cancel tab is active.
     */
    private Map<String, String> handleSubmitTabKey(String key, boolean multiQuestion) {
        switch (key) {
            case "up", "k" -> {
                if (submitCursor > 0) submitCursor--;
            }
            case "down", "j" -> {
                if (submitCursor < 1) submitCursor++;
            }
            case "left", "shift+tab" -> {
                if (multiQuestion) {
                    onSubmitTab = false;
                    questionIndex = questions.size() - 1;
                }
            }
            case "enter" -> {
                if (submitCursor == 0) {
                    return submitAllAnswers();
                }
                return cancelDialog();
            }
            case "escape" -> {
                return cancelDialog();
            }
        }
        return null;
    }

    // ── Answer collection helpers ───────────────────────────────────────

    /**
     * Persist the current question's answer into the {@link #answers} map
     * based on cursor position and selection state.
     */
    private void saveCurrentAnswer() {
        Question q = questions.get(questionIndex);
        int cursor = cursors[questionIndex];

        if (cursor == q.options().size()) {
            // "Other" selected
            String other = otherText[questionIndex];
            answers.put(questionIndex, other.isEmpty() ? "Other" : other);
        } else if (q.multiSelect()) {
            Set<Integer> sel = selected.get(questionIndex);
            var labels = new ArrayList<String>();
            for (int i = 0; i < q.options().size(); i++) {
                if (sel.contains(i)) {
                    labels.add(q.options().get(i).label());
                }
            }
            if (labels.isEmpty()) {
                // Nothing toggled -- use the option under cursor
                labels.add(q.options().get(cursor).label());
            }
            answers.put(questionIndex, String.join(", ", labels));
        } else {
            answers.put(questionIndex, q.options().get(cursor).label());
        }
    }

    /**
     * Collect all answers into a map keyed by question text and close the dialog.
     */
    private Map<String, String> submitAllAnswers() {
        active = false;
        var result = new LinkedHashMap<String, String>();
        for (int i = 0; i < questions.size(); i++) {
            String answer = answers.get(i);
            if (answer != null) {
                result.put(questions.get(i).text(), answer);
            }
        }
        return result;
    }

    /**
     * Close the dialog with a cancellation marker.
     */
    private Map<String, String> cancelDialog() {
        active = false;
        return Map.of("_declined", "true");
    }

    // ── Rendering ───────────────────────────────────────────────────────

    /**
     * Render the dialog as a plain ANSI-styled string.
     *
     * @param width the terminal width (used for layout hints)
     * @return the rendered dialog string
     */
    public String render(int width) {
        if (!active || questions == null || questions.isEmpty()) {
            return "";
        }

        var sb = new StringBuilder();
        boolean multiQuestion = questions.size() > 1;

        // Navigation bar (multi-question only)
        if (multiQuestion) {
            sb.append(renderNavBar());
            sb.append("\n\n");
        }

        // Body: either submit view or question view
        if (onSubmitTab) {
            sb.append(renderSubmitView());
        } else {
            sb.append(renderQuestionView());
        }

        // Bottom hint (multi-question, question view only)
        if (multiQuestion && !onSubmitTab) {
            sb.append(DIM_STYLE.render(
                    "      ← → navigate questions · enter to confirm"));
            sb.append("\n\n");
        }

        return sb.toString();
    }

    // ── Navigation bar ──────────────────────────────────────────────────

    private String renderNavBar() {
        var sb = new StringBuilder();

        // Left arrow
        if (questionIndex == 0 && !onSubmitTab) {
            sb.append(DIM_ARROW.render(" ←"));
        } else {
            sb.append(BRIGHT_ARROW.render(" ←"));
        }

        // Question tabs
        for (int i = 0; i < questions.size(); i++) {
            String header = questions.get(i).header();
            if (header == null || header.isEmpty()) {
                header = "Q" + (i + 1);
            }
            String check = answers.containsKey(i) ? "☑" : "☐";
            String label = header + " " + check;

            if (!onSubmitTab && i == questionIndex) {
                sb.append(ACTIVE_TAB.render(label));
            } else {
                sb.append(INACTIVE_TAB.render(label));
            }
        }

        // Submit tab
        String submitLabel = "✓ Submit";
        if (onSubmitTab) {
            sb.append(ACTIVE_TAB.render(submitLabel));
        } else {
            sb.append(INACTIVE_TAB.render(submitLabel));
        }

        // Right arrow
        if (onSubmitTab) {
            sb.append(DIM_ARROW.render(" →"));
        } else {
            sb.append(BRIGHT_ARROW.render(" →"));
        }

        return sb.toString();
    }

    // ── Question view ───────────────────────────────────────────────────

    private String renderQuestionView() {
        var sb = new StringBuilder();
        Question q = questions.get(questionIndex);
        int cursor = cursors[questionIndex];
        int lines = 0;

        // Question header
        sb.append(HEADER_STYLE.render(" " + q.text()));
        sb.append("\n\n");
        lines += 2;

        // Options
        for (int i = 0; i < q.options().size(); i++) {
            Option opt = q.options().get(i);

            // Cursor prefix
            String prefix;
            if (i == cursor) {
                prefix = CURSOR_STYLE.render(" ❯ ");
            } else {
                prefix = "   ";
            }

            // Multi-select checkbox
            if (q.multiSelect()) {
                String check = selected.get(questionIndex).contains(i) ? "●" : "○";
                prefix += check + " ";
            }

            // Label
            String label;
            if (i == cursor) {
                label = BOLD_STYLE.render(opt.label());
            } else {
                label = opt.label();
            }

            // Description
            String desc = "";
            if (opt.description() != null && !opt.description().isEmpty()) {
                desc = DIM_STYLE.render(" — " + opt.description());
            }

            sb.append(prefix).append(label).append(desc).append('\n');
            lines++;
        }

        // "Other" option
        int otherIdx = q.options().size();
        String otherPrefix;
        if (cursor == otherIdx) {
            otherPrefix = CURSOR_STYLE.render(" ❯ ");
        } else {
            otherPrefix = "   ";
        }
        String otherLabel;
        if (cursor == otherIdx) {
            otherLabel = BOLD_STYLE.render("Other");
        } else {
            otherLabel = DIM_STYLE.render("Other");
        }
        sb.append(otherPrefix).append(otherLabel);
        if (cursor == otherIdx) {
            sb.append(": ").append(otherText[questionIndex]).append("█"); // block cursor
        }
        sb.append('\n');
        lines++;

        // Multi-select hint
        if (q.multiSelect()) {
            sb.append(DIM_STYLE.render("      space to toggle, enter to confirm"));
            sb.append('\n');
            lines++;
        }

        // Pad to fixed height so switching questions doesn't cause layout shift
        if (questions.size() > 1) {
            int target = maxLines();
            while (lines < target) {
                sb.append('\n');
                lines++;
            }
        }

        return sb.toString();
    }

    // ── Submit view ─────────────────────────────────────────────────────

    private String renderSubmitView() {
        var sb = new StringBuilder();
        int lines = 0;

        sb.append(HEADER_STYLE.render(" Review your answers:"));
        sb.append("\n\n");
        lines += 2;

        // Answer summary
        for (int i = 0; i < questions.size(); i++) {
            String label = questions.get(i).header();
            if (label == null || label.isEmpty()) {
                label = "Q" + (i + 1);
            }
            String answer = answers.get(i);
            if (answer != null) {
                sb.append(String.format("   %s: %s%n", label, answer));
            } else {
                sb.append(DIM_STYLE.render(String.format("   %s: (not answered)", label)));
                sb.append('\n');
            }
            lines++;
        }
        sb.append('\n');
        lines++;

        // Submit / Cancel
        String[] actions = {"Submit answers", "Cancel"};
        for (int i = 0; i < actions.length; i++) {
            if (i == submitCursor) {
                String prefix = CURSOR_STYLE.render(" ❯ ");
                String label = BOLD_STYLE.render(actions[i]);
                sb.append(prefix).append(label).append('\n');
            } else {
                sb.append("   ").append(actions[i]).append('\n');
            }
            lines++;
        }

        // Pad to match question view height
        int target = maxLines();
        while (lines < target) {
            sb.append('\n');
            lines++;
        }

        return sb.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Compute the maximum rendered line count across all questions,
     * so that views can be padded to a uniform height.
     */
    private int maxLines() {
        int max = 0;
        for (Question q : questions) {
            int lines = 2 + q.options().size() + 1; // header + blank + options + Other
            if (q.multiSelect()) {
                lines++; // "space to toggle" hint
            }
            if (lines > max) {
                max = lines;
            }
        }
        return max;
    }
}
