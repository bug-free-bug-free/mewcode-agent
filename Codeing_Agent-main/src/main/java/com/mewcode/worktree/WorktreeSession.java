// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.worktree;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Tracks the state of an active worktree session.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorktreeSession(
        @JsonProperty("original_cwd") String originalCwd,
        @JsonProperty("worktree_path") String worktreePath,
        @JsonProperty("worktree_name") String worktreeName,
        @JsonProperty("worktree_branch") String worktreeBranch,
        @JsonProperty("original_branch") String originalBranch,
        @JsonProperty("original_head_commit") String originalHeadCommit,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("creation_duration_ms") long creationDurationMs
) {}

