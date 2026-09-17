// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Query-time memory recall: scans both user- and project-level memory
 * directories, asks a selector LLM to pick up to 5 relevant filenames,
 * and returns the corresponding paths + mtimes so the caller can read
 * full contents and inject them as a system-reminder.
 */
public final class MemoryRecall {

    private MemoryRecall() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Selector system prompt ─────────────────────────────────────────

    public static final String SELECTOR_SYSTEM_PROMPT = """
            You are selecting memories that will be useful to MewCode as it processes a user's query. \
            You will be given the user's query and a list of available memory files with their filenames and descriptions.

            Return a list of filenames for the memories that will clearly be useful to MewCode as it \
            processes the user's query (up to 5). Only include memories that you are certain will be \
            helpful based on their name and description.
            - If you are unsure if a memory will be useful in processing the user's query, then do not \
            include it in your list. Be selective and discerning.
            - If there are no memories in the list that would clearly be useful, feel free to return an empty list.
            - If a list of recently-used tools is provided, do not select memories that are usage reference \
            or API documentation for those tools (MewCode is already exercising them). DO still select \
            memories containing warnings, gotchas, or known issues about those tools — active use is exactly \
            when those matter.

            Respond with valid JSON only, no markdown, in this exact shape: \
            {"selected_memories": ["filename1.md", "filename2.md"]}""";

    // ── Result record ──────────────────────────────────────────────────

    /**
     * One memory file selected for surfacing into the main conversation.
     * {@code mtimeMs} is threaded through so callers can render freshness
     * without a second stat.
     */
    public record RelevantMemory(String path, long mtimeMs) {}

    // ── Selector function interface ────────────────────────────────────

    /**
     * Abstraction for the side-query LLM call used by the recall selector.
     * Given a system prompt and a user message, the caller stands up a
     * dedicated side-query client and returns the raw assistant text.
     * Errors are treated as "selector failed -> no recall".
     */
    @FunctionalInterface
    public interface SelectorFn {
        String select(String systemPrompt, String userMessage) throws Exception;
    }

    // ── Main entry point ───────────────────────────────────────────────

    /**
     * Scans both directories, filters already-surfaced paths, asks the
     * selector to pick up to 5 relevant filenames, and returns the
     * corresponding absolute paths + mtimes.
     *
     * @param query            the user's query text
     * @param userMemDir       user-level memory dir (may be null)
     * @param projectMemDir    project-level memory dir (may be null)
     * @param recentTools      recently-used tool names (may be null)
     * @param alreadySurfaced  paths shown in prior turns (may be null)
     * @param selector         the side-query function
     * @return selected memories; empty list on any failure
     */
    public static List<RelevantMemory> findRelevantMemories(
            String query,
            Path userMemDir,
            Path projectMemDir,
            List<String> recentTools,
            Set<String> alreadySurfaced,
            SelectorFn selector) {

        if (selector == null) return List.of();

        List<MemoryScanner.MemoryHeader> all = new ArrayList<>();
        if (userMemDir != null) {
            all.addAll(MemoryScanner.scanMemoryFiles(userMemDir, "user"));
        }
        if (projectMemDir != null) {
            all.addAll(MemoryScanner.scanMemoryFiles(projectMemDir, "project"));
        }

        // Filter already-surfaced memories.
        Set<String> surfaced = alreadySurfaced != null ? alreadySurfaced : Set.of();
        List<MemoryScanner.MemoryHeader> candidates = new ArrayList<>();
        for (var m : all) {
            if (!surfaced.contains(m.filePath())) {
                candidates.add(m);
            }
        }
        if (candidates.isEmpty()) return List.of();

        List<String> selectedFilenames = selectRelevantMemories(
                query, candidates, recentTools, selector);

        // Build a lookup from both filePath and filename to header.
        Map<String, MemoryScanner.MemoryHeader> byKey = new HashMap<>();
        for (var m : candidates) {
            byKey.put(m.filePath(), m);
            byKey.putIfAbsent(m.filename(), m);
        }

        List<RelevantMemory> result = new ArrayList<>();
        for (String fn : selectedFilenames) {
            var m = byKey.get(fn);
            if (m != null) {
                result.add(new RelevantMemory(m.filePath(), m.mtimeMs()));
            }
        }
        return result;
    }

    // ── Selector logic ─────────────────────────────────────────────────

    private static List<String> selectRelevantMemories(
            String query,
            List<MemoryScanner.MemoryHeader> memories,
            List<String> recentTools,
            SelectorFn selector) {

        Set<String> validFilenames = new HashSet<>();
        for (var m : memories) {
            validFilenames.add(m.filename());
        }

        String manifest = MemoryScanner.formatMemoryManifest(memories);

        String toolsSection = "";
        if (recentTools != null && !recentTools.isEmpty()) {
            toolsSection = "\n\nRecently used tools: " + String.join(", ", recentTools);
        }

        String userMessage = "Query: " + query + "\n\nAvailable memories:\n" + manifest + toolsSection;

        String raw;
        try {
            raw = selector.select(SELECTOR_SYSTEM_PROMPT, userMessage);
        } catch (Exception e) {
            return List.of();
        }

        String clean = extractJsonObject(raw);
        if (clean.isEmpty()) return List.of();

        try {
            JsonNode root = MAPPER.readTree(clean);
            JsonNode arr = root.get("selected_memories");
            if (arr == null || !arr.isArray()) return List.of();

            List<String> out = new ArrayList<>();
            for (JsonNode node : arr) {
                String f = node.asText();
                if (validFilenames.contains(f)) {
                    out.add(f);
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Returns the first {@code {...}} substring found in raw, or the raw
     * text trimmed if it already starts with '{'. Tolerates markdown
     * fences or prose around the JSON despite the prompt.
     */
    static String extractJsonObject(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) return trimmed;
        int start = trimmed.indexOf('{');
        if (start < 0) return "";
        int end = trimmed.lastIndexOf('}');
        if (end < start) return "";
        return trimmed.substring(start, end + 1);
    }

    // ── Reminder rendering ─────────────────────────────────────────────

    /**
     * Reads each selected memory file's full content and formats a single
     * system-reminder body with freshness headers.
     *
     * @param memories the selected memories from {@link #findRelevantMemories}
     * @return rendered reminder text, or "" if none
     */
    public static String renderReminder(List<RelevantMemory> memories) {
        if (memories == null || memories.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("The following relevant memories from prior conversations may help:\n\n");
        for (var mem : memories) {
            String content;
            try {
                content = Files.readString(Path.of(mem.path()));
            } catch (IOException e) {
                continue; // skip unreadable files
            }
            String basename = Path.of(mem.path()).getFileName().toString();
            sb.append("## Memory: ").append(basename)
              .append(" (saved ").append(MemoryAge.age(mem.mtimeMs())).append(")\n\n");
            String note = MemoryAge.freshnessText(mem.mtimeMs());
            if (!note.isEmpty()) {
                sb.append(note).append("\n\n");
            }
            sb.append(content).append("\n\n---\n\n");
        }
        return sb.toString();
    }
}
