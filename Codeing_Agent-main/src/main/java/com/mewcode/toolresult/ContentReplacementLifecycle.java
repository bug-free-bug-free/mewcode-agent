// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.toolresult;

import com.mewcode.conversation.Message;
import com.mewcode.conversation.ToolResultBlock;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reconstructs {@link ContentReplacementState} from a transcript so that
 * resume sessions make the same decisions the original session made.
 *
 * <ol>
 *   <li>Seed {@code seenIds} with every candidate tool_use_id present in
 *       {@code messages} — anything visible at this point has been sent to
 *       the model, so its decision is implicitly frozen.</li>
 *   <li>Overlay {@code replacements} from on-disk records.</li>
 *   <li>Optionally gap-fill from {@code inheritedReplacements} (parent's
 *       live state for fork-resume cases).</li>
 * </ol>
 */
public final class ContentReplacementLifecycle {

    private ContentReplacementLifecycle() {}

    public static ContentReplacementState reconstruct(
            List<Message> messages,
            List<ContentReplacementRecord> records,
            Map<String, String> inheritedReplacements
    ) {
        ContentReplacementState state = new ContentReplacementState();
        Set<String> candidateIds = new HashSet<>();
        for (Message m : messages) {
            if (m.getToolResults() == null) continue;
            for (ToolResultBlock tr : m.getToolResults()) {
                candidateIds.add(tr.toolUseId());
            }
        }
        state.seenIds().addAll(candidateIds);
        for (ContentReplacementRecord r : records) {
            if (!ContentReplacementRecord.KIND_TOOL_RESULT.equals(r.kind())) continue;
            if (candidateIds.contains(r.toolUseId())) {
                state.replacements().put(r.toolUseId(), r.replacement());
            }
        }
        if (inheritedReplacements != null) {
            for (Map.Entry<String, String> e : inheritedReplacements.entrySet()) {
                if (!candidateIds.contains(e.getKey())) continue;
                state.replacements().putIfAbsent(e.getKey(), e.getValue());
            }
        }
        return state;
    }
}

