// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.teams;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JSON-backed shared task list with dependency tracking.
 */
public class SharedTaskStore {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SharedTask(
            int id, String title, String description, String status,
            String assignee, List<Integer> blocks, List<Integer> blockedBy,
            String createdBy
    ) {
        public SharedTask withStatus(String s) {
            return new SharedTask(id, title, description, s, assignee, blocks, blockedBy, createdBy);
        }

        public SharedTask withAssignee(String a) {
            return new SharedTask(id, title, description, status, a, blocks, blockedBy, createdBy);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path filePath;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final List<SharedTask> tasks = new ArrayList<>();

    public SharedTaskStore(Path teamDir) {
        this.filePath = teamDir.resolve("tasks.json");
        load();
    }

    public synchronized SharedTask create(String title, String description, String createdBy) {
        int id = nextId.getAndIncrement();
        var task = new SharedTask(id, title, description, "todo", "", List.of(), List.of(), createdBy);
        tasks.add(task);
        save();
        return task;
    }

    public synchronized SharedTask get(int id) {
        return tasks.stream().filter(t -> t.id() == id).findFirst().orElse(null);
    }

    public synchronized List<SharedTask> listTasks(String status, String assignee) {
        return tasks.stream()
                .filter(t -> status == null || status.isEmpty() || t.status().equals(status))
                .filter(t -> assignee == null || assignee.isEmpty() || t.assignee().equals(assignee))
                .toList();
    }

    public synchronized SharedTask update(int id, String status, String assignee,
                                          List<Integer> addBlocks, List<Integer> addBlockedBy) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id() == id) {
                var old = tasks.get(i);
                String newStatus = (status != null && !status.isEmpty()) ? status : old.status();
                String newAssignee = (assignee != null) ? assignee : old.assignee();

                var newBlocks = new ArrayList<>(old.blocks());
                if (addBlocks != null) newBlocks.addAll(addBlocks);

                var newBlockedBy = new ArrayList<>(old.blockedBy());
                if (addBlockedBy != null) newBlockedBy.addAll(addBlockedBy);

                var updated = new SharedTask(id, old.title(), old.description(), newStatus,
                        newAssignee, newBlocks, newBlockedBy, old.createdBy());
                tasks.set(i, updated);
                save();
                return updated;
            }
        }
        return null;
    }

    private void load() {
        if (!Files.exists(filePath)) return;
        try {
            List<SharedTask> loaded = MAPPER.readValue(filePath.toFile(), new TypeReference<>() {});
            tasks.addAll(loaded);
            int maxId = tasks.stream().mapToInt(SharedTask::id).max().orElse(0);
            nextId.set(maxId + 1);
        } catch (IOException ignored) {}
    }

    private void save() {
        try {
            Files.createDirectories(filePath.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), tasks);
        } catch (IOException ignored) {}
    }
}
