// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.filehistory;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class FileHistory {

    public record Backup(String backupPath, int version, Instant time) {}

    public record Snapshot(
            int messageIndex,
            String userText,
            Map<String, Backup> backups,
            Instant timestamp
    ) {}

    private static final int MAX_SNAPSHOTS = 100;

    private final Path sessionDir;
    private final Map<String, Integer> trackedFiles = new LinkedHashMap<>();
    private final List<Snapshot> snapshots = new CopyOnWriteArrayList<>();

    public FileHistory(String baseDir, String sessionId) {
        this.sessionDir = Path.of(baseDir, ".mewcode", "file-history", sessionId);
        try {
            Files.createDirectories(sessionDir);
        } catch (IOException ignored) {}
    }

    private String backupName(String filePath, int version) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(filePath.getBytes());
            return "%02x%02x%02x%02x%02x%02x%02x%02x@v%d".formatted(
                    hash[0], hash[1], hash[2], hash[3],
                    hash[4], hash[5], hash[6], hash[7], version);
        } catch (Exception e) {
            return "backup-" + filePath.hashCode() + "@v" + version;
        }
    }

    public synchronized void trackEdit(String path) {
        Path absPath;
        try {
            absPath = Path.of(path).toAbsolutePath();
        } catch (Exception e) {
            absPath = Path.of(path);
        }
        String key = absPath.toString();

        int ver = trackedFiles.getOrDefault(key, 0);
        int newVer = ver + 1;

        try {
            byte[] data = Files.readAllBytes(absPath);
            Path bp = sessionDir.resolve(backupName(key, newVer));
            Files.write(bp, data);
        } catch (IOException ignored) {
            // File doesn't exist yet (new file) — no backup, but still track
        }

        trackedFiles.put(key, newVer);
    }

    public synchronized void makeSnapshot(int msgIndex, String userText) {
        var backups = new LinkedHashMap<String, Backup>();
        for (var entry : trackedFiles.entrySet()) {
            String path = entry.getKey();
            int ver = entry.getValue();
            String bpName = backupName(path, ver);
            Path bp = sessionDir.resolve(bpName);

            // If backup file missing (new file created in this turn), backup current state
            if (!Files.exists(bp)) {
                try {
                    byte[] data = Files.readAllBytes(Path.of(path));
                    Files.write(bp, data);
                } catch (IOException ignored) {}
            }

            backups.put(path, new Backup(bp.toString(), ver, Instant.now()));
        }

        snapshots.add(new Snapshot(msgIndex, userText, backups, Instant.now()));

        while (snapshots.size() > MAX_SNAPSHOTS) {
            snapshots.removeFirst();
        }
    }

    public List<Snapshot> getSnapshots() {
        return List.copyOf(snapshots);
    }

    public boolean hasSnapshots() {
        return !snapshots.isEmpty();
    }

    public synchronized List<String> rewind(int snapshotIndex) {
        if (snapshotIndex < 0 || snapshotIndex >= snapshots.size()) {
            return List.of();
        }

        Snapshot target = snapshots.get(snapshotIndex);
        var changed = new ArrayList<String>();

        for (var entry : target.backups().entrySet()) {
            String filePath = entry.getKey();
            Backup backup = entry.getValue();

            try {
                byte[] backupData = Files.readAllBytes(Path.of(backup.backupPath()));
                byte[] currentData = new byte[0];
                try {
                    currentData = Files.readAllBytes(Path.of(filePath));
                } catch (IOException ignored) {}

                if (!Arrays.equals(currentData, backupData)) {
                    Files.createDirectories(Path.of(filePath).getParent());
                    Files.write(Path.of(filePath), backupData);
                    changed.add(filePath);
                }
            } catch (IOException e) {
                // Backup missing → file didn't exist at that point
                try {
                    if (Files.exists(Path.of(filePath))) {
                        Files.delete(Path.of(filePath));
                        changed.add(filePath);
                    }
                } catch (IOException ignored) {}
            }
        }

        // Truncate snapshots after target
        while (snapshots.size() > snapshotIndex + 1) {
            snapshots.removeLast();
        }

        // Reset tracked versions
        for (var entry : target.backups().entrySet()) {
            trackedFiles.put(entry.getKey(), entry.getValue().version());
        }

        return changed;
    }
}
