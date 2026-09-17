// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.memory;

/**
 * Human-readable memory age helpers. Models are poor at date arithmetic —
 * a raw ISO timestamp doesn't trigger staleness reasoning the way
 * "47 days ago" does.
 */
public final class MemoryAge {

    private MemoryAge() {}

    /**
     * Floor-rounded days since mtime. 0 for today, 1 for yesterday, etc.
     * Negative inputs (future mtime, clock skew) clamp to 0.
     */
    public static int ageDays(long mtimeMs) {
        long d = (System.currentTimeMillis() - mtimeMs) / 86_400_000L;
        return d < 0 ? 0 : (int) d;
    }

    /**
     * Human-readable age: "today", "yesterday", or "N days ago".
     */
    public static String age(long mtimeMs) {
        int d = ageDays(mtimeMs);
        if (d == 0) return "today";
        if (d == 1) return "yesterday";
        return d + " days ago";
    }

    /**
     * Staleness warning for memories older than 1 day. Returns "" for
     * fresh (today/yesterday) memories — warning there is noise.
     */
    public static String freshnessText(long mtimeMs) {
        int d = ageDays(mtimeMs);
        if (d <= 1) return "";
        return "This memory is " + d + " days old. "
                + "Memories are point-in-time observations, not live state — "
                + "claims about code behavior or file:line citations may be outdated. "
                + "Verify against current code before asserting as fact.";
    }
}
