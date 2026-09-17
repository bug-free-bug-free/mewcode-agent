// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.StreamEvent;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.BlockingQueue;

public class MemoryManager {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MemoryEntry(String content, String timestamp, String type) {
        public MemoryEntry(String content, String timestamp) {
            this(content, timestamp, null);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int EXTRACTION_INTERVAL = 5;
    private static final String MEMORY_DIR = ".mewcode/memory";
    private static final String MEMORY_FILE = "auto_memory.json";

    // user/feedback follow the human; project/reference live with the repo.
    private static final Set<String> USER_TYPES = Set.of("user", "feedback");
    private static final Set<String> PROJECT_TYPES = Set.of("project", "reference");

    private final Path userFilePath;
    private final Path projectFilePath;
    private List<MemoryEntry> entries = new ArrayList<>();
    private int turnCount;

    public MemoryManager(String workDir) {
        this.projectFilePath = Path.of(workDir, MEMORY_DIR, MEMORY_FILE);
        this.userFilePath = Path.of(System.getProperty("user.home"), MEMORY_DIR, MEMORY_FILE);
        load();
    }

    // ---- Directory accessors (for memory recall) ----

    /**
     * Returns the user-level memory directory (~/.mewcode/memory/).
     * This is where .md memory files of type user/feedback live.
     */
    public Path userMemDir() {
        return userFilePath.getParent();
    }

    /**
     * Returns the project-level memory directory (.mewcode/memory/).
     * This is where .md memory files of type project/reference live.
     */
    public Path projectMemDir() {
        return projectFilePath.getParent();
    }

    // ---- Persistence ----

    private void load() {
        entries = new ArrayList<>();
        loadFile(userFilePath);
        loadFile(projectFilePath);
    }

    private void loadFile(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try {
            byte[] data = Files.readAllBytes(path);
            List<MemoryEntry> loaded = MAPPER.readValue(data, new TypeReference<List<MemoryEntry>>() {});
            entries.addAll(loaded);
        } catch (IOException ignored) {
            // best-effort: corrupt file shouldn't kill the manager
        }
    }

    private void save() {
        List<MemoryEntry> userScoped = new ArrayList<>();
        List<MemoryEntry> projectScoped = new ArrayList<>();
        for (MemoryEntry e : entries) {
            if (e.type() != null && USER_TYPES.contains(e.type())) {
                userScoped.add(e);
            } else if (e.type() != null && PROJECT_TYPES.contains(e.type())) {
                projectScoped.add(e);
            } else {
                // Legacy entries without a type stay project-level (matches old behavior).
                projectScoped.add(e);
            }
        }
        writeJson(userFilePath, userScoped);
        writeJson(projectFilePath, projectScoped);
    }

    private void writeJson(Path path, List<MemoryEntry> filtered) {
        try {
            Files.createDirectories(path.getParent());
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(filtered);
            Files.writeString(path, json);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    // ---- Accessors ----

    public List<String> getMemories() {
        return entries.stream().map(MemoryEntry::content).toList();
    }

    public boolean shouldExtract() {
        turnCount++;
        return turnCount % EXTRACTION_INTERVAL == 0;
    }

    public void clear() {
        entries = new ArrayList<>();
        writeJson(userFilePath, List.of());
        writeJson(projectFilePath, List.of());
    }

    // ---- Extraction via LLM ----

    public void extract(LlmClient client, ConversationManager conv) {
        List<Message> messages = conv.getMessages();
        if (messages.size() < 4) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            sb.append('[').append(msg.getRole()).append("]: ")
              .append(msg.getContent()).append('\n');
        }

        ConversationManager extractConv = new ConversationManager();
        extractConv.addUserMessage(
                "Extract key facts from this conversation worth remembering across future conversations. "
                        + "Classify each item into one of four types — the type decides which storage scope the item lives in:\n"
                        + "- `user` (user-level scope): the user's preferences, role, or background that applies across all projects\n"
                        + "- `feedback` (user-level scope): corrections the user gave or approaches the user validated\n"
                        + "- `project` (project-level scope): facts specific to the current project (tech stack, conventions, deadlines)\n"
                        + "- `reference` (project-level scope): external resources tied to this project (docs, dashboards)\n\n"
                        + "Format your output with these exact headers — skip a category if there is nothing worth saving for it:\n\n"
                        + "### user\n- item 1\n- item 2\n\n### feedback\n- item 3\n\n### project\n- item 4\n\n### reference\n- item 5\n\n"
                        + "Output nothing else (no preamble, no explanation). If nothing is worth remembering, output the four empty headers only.\n\n"
                        + "Conversation:\n"
                        + sb
        );

        BlockingQueue<StreamEvent> events = client.stream(extractConv, null);
        StringBuilder result = new StringBuilder();
        try {
            while (true) {
                StreamEvent event = events.take();
                if (event instanceof StreamEvent.TextDelta td) {
                    result.append(td.text());
                } else if (event instanceof StreamEvent.StreamEnd || event instanceof StreamEvent.Error) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (result.isEmpty()) {
            return;
        }

        Map<String, String> bySection = parseTypedSections(result.toString());
        if (bySection.isEmpty()) {
            return;
        }
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        boolean changed = false;
        for (Map.Entry<String, String> section : bySection.entrySet()) {
            String type = section.getKey();
            String content = section.getValue().trim();
            if (content.isEmpty()) {
                continue;
            }
            if (!USER_TYPES.contains(type) && !PROJECT_TYPES.contains(type)) {
                // Unknown type → drop. Avoid silently filing it under "project" because the
                // LLM might be hallucinating a category we don't support.
                continue;
            }
            entries.add(new MemoryEntry(content, now, type));
            changed = true;
        }
        if (changed) {
            save();
        }
    }

    // parseTypedSections groups lines under `### <type>` headers into a type → body map.
    // Only contiguous lines that follow a known header end up in that section; anything
    // before the first header is ignored. Case-insensitive on the type name; trims to a
    // single canonical lowercase form so the caller can compare against USER_TYPES /
    // PROJECT_TYPES.
    static Map<String, String> parseTypedSections(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        String currentType = null;
        StringBuilder buf = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("### ")) {
                if (currentType != null) {
                    String body = buf.toString().trim();
                    if (!body.isEmpty()) {
                        out.merge(currentType, body, (a, b) -> a + "\n" + b);
                    }
                }
                currentType = trimmed.substring(4).trim().toLowerCase(Locale.ROOT);
                buf.setLength(0);
            } else if (currentType != null) {
                buf.append(line).append('\n');
            }
        }
        if (currentType != null) {
            String body = buf.toString().trim();
            if (!body.isEmpty()) {
                out.merge(currentType, body, (a, b) -> a + "\n" + b);
            }
        }
        return out;
    }

    // ---- Injection ----

    public void injectMemories(ConversationManager conv) {
        List<String> memories = getMemories();
        if (memories.isEmpty()) {
            return;
        }

        StringBuilder sb = new StringBuilder("## Auto Memory\n\n");
        for (String mem : memories) {
            sb.append(mem).append("\n\n");
        }

        if (conv.getMessages().isEmpty()) {
            conv.addUserMessage(sb.toString());
            conv.addAssistantMessage("Understood, I'll keep this context in mind.");
        }
    }

    // ---- Custom instructions ----

    public static String loadInstructions(String workDir) {
        List<Path> candidates = List.of(
                Path.of(workDir, "MEWCODE.md"),
                Path.of(workDir, ".mewcode", "INSTRUCTIONS.md")
        );
        for (Path p : candidates) {
            try {
                return Files.readString(p);
            } catch (IOException ignored) {
                // try next
            }
        }
        return "";
    }
}
