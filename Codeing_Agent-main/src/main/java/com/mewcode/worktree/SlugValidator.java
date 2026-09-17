// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.worktree;

import java.util.regex.Pattern;

/**
 * Validates and transforms worktree slugs.
 */
public final class SlugValidator {

    public static final int MAX_LENGTH = 64;

    private static final Pattern VALID_SEGMENT = Pattern.compile("^[a-zA-Z0-9._-]+$");

    private SlugValidator() {}

    public static void validate(String slug) {
        if (slug == null || slug.isEmpty()) {
            throw new IllegalArgumentException("Invalid worktree name: cannot be empty");
        }
        if (slug.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Invalid worktree name: must be %d characters or fewer (got %d)"
                            .formatted(MAX_LENGTH, slug.length()));
        }
        for (String segment : slug.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(
                        "Invalid worktree name \"%s\": must not contain \".\" or \"..\" path segments"
                                .formatted(slug));
            }
            if (!VALID_SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException(
                        "Invalid worktree name \"%s\": each \"/\"-separated segment must be non-empty and contain only letters, digits, dots, underscores, and dashes"
                                .formatted(slug));
            }
        }
    }

    public static String flatten(String slug) {
        return slug.replace('/', '+');
    }

    public static String branchName(String slug) {
        return "worktree-" + flatten(slug);
    }
}
