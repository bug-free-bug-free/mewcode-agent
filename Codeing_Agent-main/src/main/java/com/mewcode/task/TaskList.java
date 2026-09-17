// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manages a named list of tasks persisted as JSON under
 * {@code .mewcode/tasks/<listId>.json}.
 * <p>
 * Each mutation reloads the file, applies the change, and writes it back so
 * that concurrent processes sharing the same store see consistent data.
 * All public methods are {@code synchronized} to guard the in-process path.
 */
public class TaskList {

    public enum Status {
        PENDING("pending"),
        IN_PROGRESS("in_progress"),
        COMPLETED("completed");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Status fromString(String s) {
            for (Status st : values()) {
                if (st.value.equals(s)) {
                    return st;
                }
            }
            throw new IllegalArgumentException("unknown status: " + s);
        }
    }

    public static class Task {
        private String id;
        private String subject;
        private String description;
        private String activeForm;

        private String status = Status.PENDING.value();
        private String owner;
        private List<String> blocks = new ArrayList<>();
        private List<String> blockedBy = new ArrayList<>();
        private Map<String, Object> metadata = new LinkedHashMap<>();

        // Jackson needs a no-arg constructor
        public Task() {}

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getSubject() { return subject; }
        public void setSubject(String subject) { this.subject = subject; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getActiveForm() { return activeForm; }
        public void setActiveForm(String activeForm) { this.activeForm = activeForm; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public String getOwner() { return owner; }
        public void setOwner(String owner) { this.owner = owner; }

        public List<String> getBlocks() { return blocks; }
        public void setBlocks(List<String> blocks) { this.blocks = blocks; }

        public List<String> getBlockedBy() { return blockedBy; }
        public void setBlockedBy(List<String> blockedBy) { this.blockedBy = blockedBy; }

        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final SecureRandom RNG = new SecureRandom();

    private final String listId;
    private final Path storePath;

    public TaskList(String listId, String workDir) {
        this.listId = listId;
        this.storePath = Path.of(workDir, ".mewcode", "tasks", listId + ".json");
    }

    public String getListId() {
        return listId;
    }

    // ---- CRUD ----

    /**
     * Creates a new task and persists the list.
     */
    public synchronized Task create(String subject, String description, String activeForm,
                                    Map<String, Object> metadata) {
        List<Task> tasks = load();

        Task task = new Task();
        task.id = generateId();
        task.subject = subject;
        task.description = description;
        task.activeForm = activeForm;
        task.status = Status.PENDING.value();
        task.blocks = new ArrayList<>();
        task.blockedBy = new ArrayList<>();
        task.metadata = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();

        tasks.add(task);
        save(tasks);
        return task;
    }

    /**
     * Returns a task by its ID, or empty if not found.
     */
    public synchronized Optional<Task> get(String id) {
        return load().stream()
                .filter(t -> t.id.equals(id))
                .findFirst();
    }

    /**
     * Lists all visible (non-internal) tasks.
     */
    public synchronized List<Task> list() {
        List<Task> visible = new ArrayList<>();
        for (Task t : load()) {
            if (t.metadata != null && t.metadata.containsKey("_internal")) {
                continue;
            }
            visible.add(t);
        }
        return visible;
    }

    /**
     * Updates fields on an existing task. Returns the updated task and the list
     * of changed field names, or empty if the task was not found.
     *
     * @param id      the task ID to update
     * @param updates a map of field names to new values (same keys as the Go
     *                implementation: subject, description, activeForm, status,
     *                owner, addBlocks, addBlockedBy, metadata)
     * @return a {@link UpdateResult} containing the task and changed fields,
     *         or {@code Optional.empty()} when the task is not found
     */
    @SuppressWarnings("unchecked")
    public synchronized Optional<UpdateResult> update(String id, Map<String, Object> updates) {
        List<Task> tasks = load();

        Task target = null;
        for (Task t : tasks) {
            if (t.id.equals(id)) {
                target = t;
                break;
            }
        }
        if (target == null) {
            return Optional.empty();
        }

        // Special case: status == "deleted" means remove the task entirely
        Object statusVal = updates.get("status");
        if (statusVal instanceof String s && "deleted".equals(s)) {
            tasks.removeIf(t -> t.id.equals(id));
            save(tasks);
            return Optional.of(new UpdateResult(target, List.of("deleted")));
        }

        List<String> changed = new ArrayList<>();

        if (updates.containsKey("subject")) {
            String v = asString(updates.get("subject"));
            if (v != null && !v.equals(target.subject)) {
                target.subject = v;
                changed.add("subject");
            }
        }
        if (updates.containsKey("description")) {
            String v = asString(updates.get("description"));
            if (v != null && !v.equals(target.description)) {
                target.description = v;
                changed.add("description");
            }
        }
        if (updates.containsKey("activeForm")) {
            String v = asString(updates.get("activeForm"));
            if (v != null && !v.equals(target.activeForm)) {
                target.activeForm = v;
                changed.add("activeForm");
            }
        }
        if (updates.containsKey("status")) {
            String v = asString(updates.get("status"));
            if (v != null && !v.equals(target.status)) {
                target.status = v;
                changed.add("status");
            }
        }
        if (updates.containsKey("owner")) {
            String v = asString(updates.get("owner"));
            if (v != null && !v.equals(target.owner)) {
                target.owner = v;
                changed.add("owner");
            }
        }
        if (updates.containsKey("addBlocks")) {
            List<String> ids = toStringList(updates.get("addBlocks"));
            if (!ids.isEmpty()) {
                var existing = new java.util.LinkedHashSet<>(target.blocks);
                for (String b : ids) {
                    existing.add(b);
                }
                target.blocks = new ArrayList<>(existing);
                changed.add("blocks");
            }
        }
        if (updates.containsKey("addBlockedBy")) {
            List<String> ids = toStringList(updates.get("addBlockedBy"));
            if (!ids.isEmpty()) {
                var existing = new java.util.LinkedHashSet<>(target.blockedBy);
                for (String b : ids) {
                    existing.add(b);
                }
                target.blockedBy = new ArrayList<>(existing);
                changed.add("blockedBy");
            }
        }
        if (updates.containsKey("metadata")) {
            Object raw = updates.get("metadata");
            if (raw instanceof Map<?, ?> m) {
                if (target.metadata == null) {
                    target.metadata = new LinkedHashMap<>();
                }
                for (var entry : m.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    if (entry.getValue() == null) {
                        target.metadata.remove(key);
                    } else {
                        target.metadata.put(key, entry.getValue());
                    }
                }
                changed.add("metadata");
            }
        }

        if (!changed.isEmpty()) {
            save(tasks);
        }

        return Optional.of(new UpdateResult(target, changed));
    }

    /**
     * Result of an {@link #update} call.
     */
    public record UpdateResult(Task task, List<String> changed) {}

    // ---- persistence ----

    private List<Task> load() {
        try {
            if (!Files.exists(storePath)) {
                return new ArrayList<>();
            }
            byte[] data = Files.readAllBytes(storePath);
            if (data.length == 0) {
                return new ArrayList<>();
            }
            return MAPPER.readValue(data, new TypeReference<List<Task>>() {});
        } catch (IOException e) {
            // Corrupted or unreadable file — start fresh
            return new ArrayList<>();
        }
    }

    private void save(List<Task> tasks) {
        try {
            Files.createDirectories(storePath.getParent());
            MAPPER.writeValue(storePath.toFile(), tasks);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save task list to " + storePath, e);
        }
    }

    // ---- helpers ----

    private static String generateId() {
        byte[] bytes = new byte[4];
        RNG.nextBytes(bytes);
        var sb = new StringBuilder("t");
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static String asString(Object v) {
        return v instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object v) {
        if (v instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String s) {
                    result.add(s);
                }
            }
            return result;
        }
        return List.of();
    }
}
