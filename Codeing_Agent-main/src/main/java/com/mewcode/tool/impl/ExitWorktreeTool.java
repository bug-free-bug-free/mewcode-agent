// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.tool.impl;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;
import com.mewcode.worktree.WorktreeChanges;
import com.mewcode.worktree.WorktreeManager;
import com.mewcode.worktree.WorktreeSessionStore;

import java.util.ArrayList;
import java.util.Map;

/**
 * Exits a worktree session and restores the original directory.
 */
public class ExitWorktreeTool implements Tool {

    private final WorktreeManager worktreeManager;

    public ExitWorktreeTool(WorktreeManager worktreeManager) {
        this.worktreeManager = worktreeManager;
    }

    @Override public String name() { return "ExitWorktree"; }
    @Override public ToolCategory category() { return ToolCategory.COMMAND; }
    @Override public boolean shouldDefer() { return true; }

    @Override
    public String description() {
        return "Exits a worktree session created by EnterWorktree and restores the original working directory";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "action", Map.of(
                                        "type", "string",
                                        "enum", new String[]{"keep", "remove"},
                                        "description", "\"keep\" preserves the worktree; \"remove\" deletes it."
                                ),
                                "discard_changes", Map.of(
                                        "type", "boolean",
                                        "description", "Required true when action is \"remove\" and the worktree has changes."
                                )
                        ),
                        "required", new String[]{"action"}
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        var session = WorktreeSessionStore.getCurrentSession();
        if (session == null) {
            return ToolResult.error(
                    "No-op: there is no active EnterWorktree session to exit. "
                            + "This tool only operates on worktrees created by EnterWorktree in the current session."
            );
        }

        String action = String.valueOf(args.getOrDefault("action", "keep"));
        boolean discard = Boolean.TRUE.equals(args.get("discard_changes"));

        String worktreePath = session.worktreePath();
        String originalCwd = session.originalCwd();

        if ("remove".equals(action) && !discard) {
            var summary = WorktreeChanges.countChanges(worktreePath, session.originalHeadCommit());
            if (summary == null) {
                return ToolResult.error(
                        "Could not verify worktree state. Refusing to remove without explicit confirmation. "
                                + "Re-invoke with discard_changes: true, or use action: \"keep\"."
                );
            }
            if (summary.changedFiles() > 0 || summary.commits() > 0) {
                var parts = new ArrayList<String>();
                if (summary.changedFiles() > 0) {
                    parts.add("%d uncommitted %s".formatted(summary.changedFiles(),
                            summary.changedFiles() == 1 ? "file" : "files"));
                }
                if (summary.commits() > 0) {
                    parts.add("%d %s".formatted(summary.commits(),
                            summary.commits() == 1 ? "commit" : "commits"));
                }
                return ToolResult.error(
                        "Worktree has %s. Removing will discard this work permanently. "
                                .formatted(String.join(" and ", parts))
                                + "Re-invoke with discard_changes: true, or use action: \"keep\"."
                );
            }
        }

        // Clear session
        WorktreeSessionStore.restoreSession(null);
        try {
            WorktreeSessionStore.save(worktreeManager.getProjectRoot(), null);
        } catch (Exception ignored) {}

        if ("remove".equals(action)) {
            try {
                worktreeManager.remove(session.worktreeName());
            } catch (Exception e) {
                return ToolResult.error("Error removing worktree: " + e.getMessage());
            }
            return ToolResult.success(
                    "Exited and removed worktree at %s. Session is now back in %s."
                            .formatted(worktreePath, originalCwd)
            );
        }

        return ToolResult.success(
                "Exited worktree. Your work is preserved at %s. Session is now back in %s."
                        .formatted(worktreePath, originalCwd)
        );
    }
}
