// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.teams;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class FileMailBox {

    public record MailMessage(String from, String text, String timestamp,
                              boolean read, String color, String summary) {
        public MailMessage(String from, String text) {
            this(from, text, DateTimeFormatter.ISO_INSTANT.format(Instant.now()), false, "", "");
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RETRIES = 10;
    private static final int MIN_SLEEP_MS = 5;
    private static final int MAX_SLEEP_MS = 100;

    private final Path baseDir;

    public FileMailBox(Path baseDir) {
        this.baseDir = baseDir;
        try {
            Files.createDirectories(baseDir);
        } catch (IOException ignored) {}
    }

    private Path inboxPath(String agentId) {
        return baseDir.resolve(agentId + ".json");
    }

    private Path lockPath(String agentId) {
        return baseDir.resolve(agentId + ".json.lock");
    }

    public void send(String recipient, MailMessage msg) {
        withLock(recipient, messages -> {
            var m = new MailMessage(msg.from(), msg.text(), msg.timestamp(), false, msg.color(), msg.summary());
            messages.add(m);
            return messages;
        });
    }

    public List<MailMessage> readUnread(String agentId) {
        List<MailMessage> messages = readInbox(agentId);
        List<MailMessage> unread = new ArrayList<>();
        for (var m : messages) {
            if (!m.read()) unread.add(m);
        }
        return unread;
    }

    public void markAllRead(String agentId) {
        withLock(agentId, messages -> {
            List<MailMessage> updated = new ArrayList<>();
            for (var m : messages) {
                updated.add(new MailMessage(m.from(), m.text(), m.timestamp(), true, m.color(), m.summary()));
            }
            return updated;
        });
    }

    private interface MutationFn {
        List<MailMessage> apply(List<MailMessage> messages);
    }

    private void withLock(String agentId, MutationFn fn) {
        Path lock = lockPath(agentId);
        boolean acquired = false;

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                Files.createFile(lock);
                acquired = true;
                break;
            } catch (FileAlreadyExistsException e) {
                // Check for stale lock (>10s old)
                try {
                    var modTime = Files.getLastModifiedTime(lock).toInstant();
                    if (Instant.now().minusSeconds(10).isAfter(modTime)) {
                        Files.deleteIfExists(lock);
                    }
                } catch (IOException ignored) {}
                int sleepMs = MIN_SLEEP_MS + ThreadLocalRandom.current().nextInt(MAX_SLEEP_MS - MIN_SLEEP_MS);
                try { Thread.sleep(sleepMs); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (IOException e) {
                return;
            }
        }

        if (!acquired) return;

        try {
            List<MailMessage> messages = readInbox(agentId);
            messages = fn.apply(messages);
            writeInbox(agentId, messages);
        } finally {
            try { Files.deleteIfExists(lock); } catch (IOException ignored) {}
        }
    }

    private List<MailMessage> readInbox(String agentId) {
        Path path = inboxPath(agentId);
        if (!Files.exists(path)) return new ArrayList<>();
        try {
            byte[] data = Files.readAllBytes(path);
            return MAPPER.readValue(data, new TypeReference<List<MailMessage>>() {});
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private void writeInbox(String agentId, List<MailMessage> messages) {
        Path path = inboxPath(agentId);
        try {
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(messages);
            Files.writeString(path, json);
        } catch (IOException ignored) {}
    }
}
