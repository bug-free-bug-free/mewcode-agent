// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.toolresult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-conversation-thread decision log for tool-result budgeting.
 *
 * <ul>
 *   <li>{@code seenIds} — every {@code tool_use_id} that has passed through
 *       {@link ToolResultBudget#apply} at least once. Once present, the
 *       decision (replaced or not) is frozen forever for that id.</li>
 *   <li>{@code replacements} — the byte-exact preview string for every id
 *       that was decided "replace". Subsequent turns re-apply this string
 *       verbatim — no filesystem I/O, no chance of drift.</li>
 * </ul>
 *
 * <p>Invariant: {@code keys(replacements) ⊆ seenIds}.
 */
public final class ContentReplacementState {

    private final Set<String> seenIds = new HashSet<>();

    private final Map<String, String> replacements = new HashMap<>();

    public Set<String> seenIds() {
        return seenIds;
    }

    public Map<String, String> replacements() {
        return replacements;
    }

    /**
     * Produce an independent copy. Used at fork time so the child agent
     * inherits the parent's frozen decisions but does not write back into
     * the parent's collections.
     */
    public ContentReplacementState copy() {
        ContentReplacementState out = new ContentReplacementState();
        out.seenIds.addAll(this.seenIds);
        out.replacements.putAll(this.replacements);
        return out;
    }
}
