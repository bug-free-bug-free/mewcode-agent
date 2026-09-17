// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.compact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-agent snapshots that need to survive Layer 2 compaction.
 *
 * <p>Compact wipes the working transcript; without these records the
 * model would forget which files it had just read and which skill SOPs it
 * was operating under. {@link ContextCompactor#buildRecoveryAttachment}
 * renders the recorded data into a single attachment block that gets
 * appended to the post-compact summary message.
 *
 * <p>Thread-safe: tool callbacks may fire from multiple virtual threads in
 * the streaming executor.
 */
public final class RecoveryState {

    /** Snapshot of what a file-reading tool last returned. */
    public record FileReadRecord(String path, String content, Instant timestamp) {}

    /** Snapshot of the SOP body delivered to the model when a skill ran. */
    public record SkillInvocationRecord(String name, String body, Instant timestamp) {}

    private final Object lock = new Object();
    private final Map<String, FileReadRecord> files = new HashMap<>();

    private final Map<String, SkillInvocationRecord> skills = new HashMap<>();

    /** Overwrites any prior record for the same path so the latest snapshot wins. */
    public void recordFileRead(String path, String content) {
        if (path == null || path.isEmpty()) return;

        synchronized (lock) {
            files.put(path, new FileReadRecord(path, content, Instant.now()));
        }
    }

    /** Overwrites any prior record for the same skill name. */
    public void recordSkillInvocation(String name, String body) {
        if (name == null || name.isEmpty()) return;
        synchronized (lock) {
            skills.put(name, new SkillInvocationRecord(name, body, Instant.now()));
        }
    }

    /** Returns up to {@code limit} file records, newest first. */
    public List<FileReadRecord> snapshotFiles(int limit) {
        List<FileReadRecord> out;
        synchronized (lock) {
            out = new ArrayList<>(files.values());
        }
        out.sort(Comparator.comparing(FileReadRecord::timestamp).reversed());
        if (limit > 0 && out.size() > limit) {
            return out.subList(0, limit);
        }
        return out;
    }

    /** Returns every recorded skill, newest first. */
    public List<SkillInvocationRecord> snapshotSkills() {
        List<SkillInvocationRecord> out;
        synchronized (lock) {
            out = new ArrayList<>(skills.values());
        }
        out.sort(Comparator.comparing(SkillInvocationRecord::timestamp).reversed());
        return out;
    }
}
