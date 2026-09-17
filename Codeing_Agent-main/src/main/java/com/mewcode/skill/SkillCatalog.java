// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.skill;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Manages skill discovery, loading, and context generation.
 * <p>
 * Phase-1 loading reads only frontmatter (fast startup); {@link #getFull}
 * triggers a phase-2 re-read of the body on each call (hot reload).
 * <p>
 * Three-tier loading via {@link #loadCatalog}: builtins → user global
 * ({@code ~/.mewcode/skills/}) → project ({@code .mewcode/skills/}),
 * with later tiers overriding earlier ones by name.
 */
public class SkillCatalog {

    // ── Data types ──────────────────────────────────────────────────────

    public record SkillMeta(
            String name,
            String description,
            String whenToUse,
            List<String> tags,
            List<String> allowedTools,
            String mode,
            String model,
            String forkContext
    ) {}

    public record Skill(SkillMeta meta, String promptBody, Path sourceDir, boolean bodyLoaded) {
        public Skill withBody(String newBody) {
            return new Skill(meta, newBody, sourceDir, true);
        }
    }

    // ── State ───────────────────────────────────────────────────────────

    private final Map<String, Skill> skills = new LinkedHashMap<>();
    private final Map<String, String> sources = new LinkedHashMap<>();

    private String workDir;

    // ── Public API ──────────────────────────────────────────────────────

    public void register(Skill skill, String source) {
        skills.put(skill.meta().name(), skill);
        sources.put(skill.meta().name(), source);
    }

    public void register(Skill skill) {
        register(skill, "");
    }

    public Map<String, Skill> getSkills() {
        return Collections.unmodifiableMap(skills);
    }

    public Optional<Skill> get(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    /**
     * Returns the skill with its body loaded. For disk-backed skills the
     * body is re-read on every call (hot reload). On read failure the
     * previously-cached body is preserved.
     */
    public Optional<Skill> getFull(String name) {
        Skill skill = skills.get(name);
        if (skill == null) {
            return Optional.empty();
        }
        if (skill.sourceDir() == null) {
            return Optional.of(skill);
        }
        try {
            Skill reloaded = loadSkill(skill.sourceDir());
            if (reloaded != null) {
                skills.put(name, reloaded);
                return Optional.of(reloaded);
            }
        } catch (IOException ignored) {
            // Keep the previously-cached body
        }
        return Optional.of(skill);
    }

    public List<SkillMeta> list() {
        return skills.values().stream().map(Skill::meta).toList();
    }

    public String source(String name) {
        return sources.getOrDefault(name, "");
    }

    // ── Three-tier catalog loading ─────────────────────────────────────

    /**
     * Builds a catalog by merging three tiers, with later sources
     * overriding earlier ones by name (project wins over user wins over
     * builtin). Phase-1: only frontmatter is read; bodies stay empty
     * until {@link #getFull} is called.
     */
    public static SkillCatalog loadCatalog(String workDir) {
        SkillCatalog c = new SkillCatalog();
        c.workDir = workDir;

        // Tier 1: builtins (placeholder — loaded by Agent layer)

        // Tier 2: user global
        String home = System.getProperty("user.home");
        if (home != null) {
            c.loadTier(Path.of(home, ".mewcode", "skills"), "user");
        }

        // Tier 3: project
        c.loadTier(Path.of(workDir, ".mewcode", "skills"), "project");

        return c;
    }

    public void reload(String workDir) {
        SkillCatalog fresh = loadCatalog(workDir);
        this.skills.clear();
        this.skills.putAll(fresh.skills);
        this.sources.clear();
        this.sources.putAll(fresh.sources);
        this.workDir = fresh.workDir;
    }

    /**
     * Walk {@code dir}; each immediate subdirectory is treated as a skill.
     * Missing or inaccessible directories are silently ignored.
     */
    public void loadFromDirectory(Path dir) {
        loadTier(dir, dir.toString());
    }

    private void loadTier(Path dir, String source) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory).forEach(skillDir -> {
                try {
                    Skill skill = loadSkill(skillDir);
                    if (skill != null) {
                        register(skill, source);
                    }
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    // ── Context building ───────────────────────────────────────────────

    /**
     * Build a context block suitable for system-prompt injection that
     * contains the prompt bodies of the given active skill names.
     */
    public String buildActiveContext(Set<String> activeSkillNames) {
        if (activeSkillNames == null || activeSkillNames.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder();
        sb.append("## Active Skills\n\n");
        for (var name : activeSkillNames) {
            var skill = skills.get(name);
            if (skill != null) {
                sb.append("### ").append(name).append("\n");
                sb.append(skill.promptBody()).append("\n\n");
            }
        }
        return sb.toString();
    }

    // ── Loading internals ───────────────────────────────────────────────

    private static Skill loadSkill(Path dir) throws IOException {
        // Strategy 1: skill.yaml + prompt.md
        Path metaPath = dir.resolve("skill.yaml");
        if (Files.isRegularFile(metaPath)) {
            return loadFromYamlAndPrompt(dir, metaPath);
        }

        // Strategy 2: SKILL.md with optional YAML front-matter
        Path mdPath = dir.resolve("SKILL.md");
        if (Files.isRegularFile(mdPath)) {
            String content = Files.readString(mdPath);
            return parseSkillMD(dir, content);
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static Skill loadFromYamlAndPrompt(Path dir, Path metaPath) throws IOException {
        String yamlText = Files.readString(metaPath);
        Yaml yaml = new Yaml();
        Map<String, Object> map = yaml.load(yamlText);
        if (map == null) {
            map = Map.of();
        }

        SkillMeta meta = metaFromMap(map, dir);

        String promptBody = "";
        Path promptPath = dir.resolve("prompt.md");
        if (Files.isRegularFile(promptPath)) {
            promptBody = Files.readString(promptPath);
        }

        return new Skill(meta, promptBody, dir, true);
    }

    @SuppressWarnings("unchecked")
    private static Skill parseSkillMD(Path dir, String content) {
        String body = content;
        Map<String, Object> frontMatter = Map.of();

        String trimmed = content.stripLeading();
        if (trimmed.startsWith("---")) {
            int firstSep = content.indexOf("---");
            int secondSep = content.indexOf("---", firstSep + 3);
            if (secondSep >= 0) {
                String yamlBlock = content.substring(firstSep + 3, secondSep);
                body = content.substring(secondSep + 3).strip();
                try {
                    Yaml yaml = new Yaml();
                    Map<String, Object> parsed = yaml.load(yamlBlock);
                    if (parsed != null) {
                        frontMatter = parsed;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        SkillMeta meta = metaFromMap(frontMatter, dir);

        // Auto-generate description from first non-empty, non-heading line if absent
        String description = meta.description();
        if (description == null || description.isBlank()) {
            for (String line : body.split("\n")) {
                String stripped = line.strip();
                if (!stripped.isEmpty() && !stripped.startsWith("#")) {
                    description = stripped;
                    break;
                }
            }
            meta = new SkillMeta(meta.name(), description != null ? description : "",
                    meta.whenToUse(), meta.tags(), meta.allowedTools(),
                    meta.mode(), meta.model(), meta.forkContext());
        }

        return new Skill(meta, body, dir, true);
    }

    @SuppressWarnings("unchecked")
    private static SkillMeta metaFromMap(Map<String, Object> map, Path dir) {
        String name = stringVal(map, "name");
        if (name == null || name.isBlank()) {
            name = dir.getFileName().toString().toLowerCase().replace(' ', '-');
        }
        String description = stringVal(map, "description");
        String whenToUse = stringVal(map, "when_to_use");

        List<String> tags = List.of();
        Object rawTags = map.get("tags");
        if (rawTags instanceof List<?> list) {
            tags = list.stream().map(Object::toString).toList();
        }

        List<String> allowedTools = List.of();
        Object rawAllowed = map.get("allowed_tools");
        if (rawAllowed instanceof List<?> list) {
            allowedTools = list.stream().map(Object::toString).toList();
        }

        String mode = stringVal(map, "mode");
        if (mode == null || mode.isBlank()) {
            // Backward compat: context: "fork" treated same as mode: "fork"
            String ctx = stringVal(map, "context");
            if ("fork".equals(ctx)) {
                mode = "fork";
            } else {
                mode = "inline";
            }
        }

        String model = stringVal(map, "model");

        String forkContext = stringVal(map, "fork_context");
        if (forkContext == null || forkContext.isBlank()) {
            forkContext = "none";
        }

        return new SkillMeta(
                name,
                description != null ? description : "",
                whenToUse != null ? whenToUse : "",
                tags,
                allowedTools,
                mode,
                model != null ? model : "",
                forkContext
        );
    }

    private static String stringVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
