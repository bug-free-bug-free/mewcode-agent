// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.memory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Scans memory directories for .md files with YAML-ish frontmatter,
 * returning header metadata sorted newest-first. Shared by
 * {@link MemoryRecall} (query-time recall) and any future extraction agent.
 */
public final class MemoryScanner {

    private MemoryScanner() {}

    /** Maximum memory files surfaced to the selector model. */
    public static final int MAX_MEMORY_FILES = 200;

    /** How many lines to read for frontmatter parsing. */
    private static final int FRONTMATTER_MAX_LINES = 30;

    /** MEMORY.md is the entrypoint index — not a memory file itself. */
    private static final String ENTRYPOINT_NAME = "MEMORY.md";

    /** YAML-ish frontmatter block: starts with `---`, ends with `---`. */
    private static final Pattern FRONTMATTER_RE =
            Pattern.compile("\\A---\\s*\\n(.*?)\\n---\\s*\\n", Pattern.DOTALL);

    // ── Header record ──────────────────────────────────────────────────

    /**
     * One scanned memory file's metadata.
     */
    public record MemoryHeader(
            String filename,    // path relative to memoryDir
            String filePath,    // absolute path
            String scope,       // "user" or "project"
            long mtimeMs,       // modification time, ms since epoch
            String description, // frontmatter description; "" if absent
            String type         // frontmatter type; "" if unrecognized
    ) {}

    // ── Scan ───────────────────────────────────────────────────────────

    /**
     * Walk {@code memoryDir} for .md files (excluding MEMORY.md), read
     * frontmatter from each, and return a header list sorted newest-first,
     * capped at {@link #MAX_MEMORY_FILES}.
     *
     * @param memoryDir the directory to scan
     * @param scope     "user" or "project" — threaded into each header
     * @return headers sorted by mtime descending; empty list if dir missing
     */
    public static List<MemoryHeader> scanMemoryFiles(Path memoryDir, String scope) {
        if (memoryDir == null || !Files.isDirectory(memoryDir)) {
            return List.of();
        }
        List<Path> mdFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(memoryDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith(".md") && !name.equals(ENTRYPOINT_NAME);
                })
                .forEach(mdFiles::add);
        } catch (IOException e) {
            return List.of();
        }

        List<MemoryHeader> results = new ArrayList<>();
        for (Path fp : mdFiles) {
            MemoryHeader hdr = readMemoryHeader(fp, memoryDir, scope);
            if (hdr != null) {
                results.add(hdr);
            }
        }

        // Sort newest-first.
        results.sort(Comparator.comparingLong(MemoryHeader::mtimeMs).reversed());
        if (results.size() > MAX_MEMORY_FILES) {
            results = new ArrayList<>(results.subList(0, MAX_MEMORY_FILES));
        }
        return results;
    }

    // ── Header parsing ─────────────────────────────────────────────────

    private static MemoryHeader readMemoryHeader(Path filePath, Path memoryDir, String scope) {
        long mtimeMs;
        try {
            mtimeMs = Files.getLastModifiedTime(filePath).toMillis();
        } catch (IOException e) {
            return null;
        }

        // Read first FRONTMATTER_MAX_LINES for frontmatter parsing.
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            for (int i = 0; i < FRONTMATTER_MAX_LINES; i++) {
                String line = reader.readLine();
                if (line == null) break;
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            return null;
        }

        Frontmatter fm = parseFrontmatter(sb.toString());
        String rel;
        try {
            rel = memoryDir.relativize(filePath).toString();
        } catch (IllegalArgumentException e) {
            rel = filePath.getFileName().toString();
        }

        return new MemoryHeader(rel, filePath.toAbsolutePath().toString(), scope,
                mtimeMs, fm.description(), fm.type());
    }

    // ── Frontmatter ────────────────────────────────────────────────────

    record Frontmatter(String name, String description, String type) {}

    /**
     * Extracts name/description/type from YAML-ish frontmatter. Only the
     * three known fields are read; everything else is ignored. Files without
     * frontmatter return empty fields.
     */
    static Frontmatter parseFrontmatter(String content) {
        Matcher m = FRONTMATTER_RE.matcher(content);
        if (!m.find()) {
            return new Frontmatter("", "", "");
        }
        String block = m.group(1);
        String name = "";
        String description = "";
        String type = "";
        for (String line : block.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim();
            String val = line.substring(colon + 1).trim();
            // Strip quotes.
            if ((val.startsWith("\"") && val.endsWith("\""))
                    || (val.startsWith("'") && val.endsWith("'"))) {
                val = val.substring(1, val.length() - 1);
            }
            switch (key) {
                case "name" -> name = val;
                case "description" -> description = val;
                case "type" -> {
                    if (isValidType(val)) type = val;
                }
            }
        }
        return new Frontmatter(name, description, type);
    }

    private static final Set<String> VALID_TYPES =
            Set.of("user", "feedback", "project", "reference");

    private static boolean isValidType(String raw) {
        return VALID_TYPES.contains(raw);
    }

    // ── Manifest formatting ────────────────────────────────────────────

    private static final DateTimeFormatter ISO_MS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /**
     * Formats memory headers as a text manifest: one line per file with
     * {@code [scope] [type] path (timestamp): description}. Used by the
     * recall selector prompt.
     */
    public static String formatMemoryManifest(List<MemoryHeader> memories) {
        if (memories.isEmpty()) return "";
        var sb = new StringBuilder();
        for (int i = 0; i < memories.size(); i++) {
            if (i > 0) sb.append('\n');
            var m = memories.get(i);
            String scope = (m.scope() != null && !m.scope().isEmpty())
                    ? "[" + m.scope() + "-scope] " : "";
            String tag = (m.type() != null && !m.type().isEmpty())
                    ? "[" + m.type() + "] " : "";
            String ts = ISO_MS.format(Instant.ofEpochMilli(m.mtimeMs()));
            String path = (m.filePath() != null && !m.filePath().isEmpty())
                    ? m.filePath() : m.filename();
            if (m.description() != null && !m.description().isEmpty()) {
                sb.append("- ").append(scope).append(tag).append(path)
                  .append(" (").append(ts).append("): ").append(m.description());
            } else {
                sb.append("- ").append(scope).append(tag).append(path)
                  .append(" (").append(ts).append(")");
            }
        }
        return sb.toString();
    }
}
