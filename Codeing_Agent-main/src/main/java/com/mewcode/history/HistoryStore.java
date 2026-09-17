// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Persists prompt history as a JSONL file (one JSON object per line).
 *
 * <p>Each line has the shape {@code {"text":"user input","ts":1234567890}}
 * where {@code ts} is a Unix epoch second. Consecutive duplicate entries are
 * suppressed, and the store acts as a circular buffer capped at
 * {@value #MAX_ENTRIES} entries.
 */
public class HistoryStore {

    private static final int MAX_ENTRIES = 200;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path filePath;
    private final List<String> entries = new ArrayList<>();

    /** Default constructor &mdash; stores history in {@code ~/.mewcode/prompt_history.jsonl}. */
    public HistoryStore() {
        this(Path.of(System.getProperty("user.home"), ".mewcode", "prompt_history.jsonl"));
    }

    /** Testable constructor that accepts an explicit file path. */
    public HistoryStore(Path filePath) {
        this.filePath = filePath;
    }

    // ------------------------------------------------------------------
    // Load
    // ------------------------------------------------------------------

    /**
     * Reads the JSONL file and populates the in-memory entry list.
     * Malformed lines and entries with an empty {@code text} field are silently
     * skipped, matching the Go behaviour.
     */
    public void load() {
        entries.clear();
        if (!Files.exists(filePath)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    var node = MAPPER.readTree(line);
                    var textNode = node.get("text");
                    if (textNode != null && textNode.isTextual()) {
                        String text = textNode.asText();
                        if (!text.isEmpty()) {
                            entries.add(text);
                        }
                    }
                } catch (Exception ignored) {
                    // skip malformed lines
                }
            }
        } catch (IOException ignored) {
            // file unreadable — start with empty history
        }
    }

    // ------------------------------------------------------------------
    // Append
    // ------------------------------------------------------------------

    /**
     * Appends a new entry to the history.
     *
     * <ul>
     *   <li>If {@code text} equals the last entry, the call is a no-op (dedup).</li>
     *   <li>When the list exceeds {@value #MAX_ENTRIES}, the oldest entries are
     *       trimmed from the front.</li>
     *   <li>The entire file is rewritten with fresh timestamps on each append.</li>
     * </ul>
     */
    public void append(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        // Deduplicate consecutive identical entries.
        if (!entries.isEmpty() && entries.getLast().equals(text)) {
            return;
        }

        entries.add(text);

        // Circular-buffer trim.
        if (entries.size() > MAX_ENTRIES) {
            int excess = entries.size() - MAX_ENTRIES;
            entries.subList(0, excess).clear();
        }

        writeToDisk();
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    /** Returns an unmodifiable snapshot of the current entries. */
    public List<String> getEntries() {
        return List.copyOf(entries);
    }

    /** Number of entries currently held. */
    public int size() {
        return entries.size();
    }

    /** Returns the entry at the given index. */
    public String get(int index) {
        return entries.get(index);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Rewrites the full JSONL file from the in-memory list. */
    private void writeToDisk() {
        try {
            Files.createDirectories(filePath.getParent());
        } catch (IOException e) {
            return;
        }

        try (BufferedWriter writer = Files.newBufferedWriter(filePath,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            long now = Instant.now().getEpochSecond();
            for (String t : entries) {
                var node = MAPPER.createObjectNode();
                node.put("text", t);
                node.put("ts", now);
                writer.write(MAPPER.writeValueAsString(node));
                writer.newLine();
            }
        } catch (IOException ignored) {
            // best-effort persistence
        }
    }
}
