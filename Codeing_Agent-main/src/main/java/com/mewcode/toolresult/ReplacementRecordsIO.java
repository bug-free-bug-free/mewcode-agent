// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.toolresult;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JSONL persistence for {@link ContentReplacementRecord}. One file per
 * session at {@code <sessionDir>/replacement_records.jsonl}; append-only,
 * one record per line. Loading a missing file returns an empty list so
 * resume and cold-start can share a code path.
 */
public final class ReplacementRecordsIO {

    public static final String RECORDS_FILENAME = "replacement_records.jsonl";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReplacementRecordsIO() {}

    public static void append(Path sessionDir, List<ContentReplacementRecord> records) throws IOException {
        if (records.isEmpty()) return;
        Files.createDirectories(sessionDir);
        Path file = sessionDir.resolve(RECORDS_FILENAME);
        try (BufferedWriter w = Files.newBufferedWriter(
                file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND
        )) {
            for (ContentReplacementRecord r : records) {
                String kind = r.kind() == null || r.kind().isEmpty()
                        ? ContentReplacementRecord.KIND_TOOL_RESULT
                        : r.kind();
                ContentReplacementRecord normalized = new ContentReplacementRecord(
                        kind, r.toolUseId(), r.replacement());
                try {
                    w.write(MAPPER.writeValueAsString(normalized));
                } catch (JsonProcessingException e) {
                    throw new IOException(e);
                }
                w.write('\n');
            }
        }
    }

    public static List<ContentReplacementRecord> load(Path sessionDir) throws IOException {
        Path file = sessionDir.resolve(RECORDS_FILENAME);
        if (!Files.exists(file)) return Collections.emptyList();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<ContentReplacementRecord> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line.isEmpty()) continue;
            out.add(MAPPER.readValue(line, ContentReplacementRecord.class));
        }
        return out;
    }
}
