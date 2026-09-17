// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.subagent;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Loads sub-agent definitions from three sources (in priority order):
 * <ol>
 *   <li>Built-in specs ({@link SubAgentSpec#GENERAL_PURPOSE}, etc.)</li>
 *   <li>User-level definitions from {@code ~/.mewcode/agents/*.md}</li>
 *   <li>Project-level definitions from {@code <projectRoot>/.mewcode/agents/*.md}</li>
 * </ol>
 * Later sources override earlier ones with the same agent name.
 *
 * <p>Each {@code .md} file uses optional YAML frontmatter delimited by {@code ---}
 * followed by a Markdown body that becomes the system prompt override. The
 * frontmatter fields are: {@code name}, {@code description}, {@code disallowedTools},
 * {@code model}, and {@code maxTurns}.
 */
public final class AgentLoader {

    private static final Set<String> VALID_MODELS = Set.of("", "inherit", "haiku", "sonnet", "opus");

    private final Map<String, SubAgentSpec> agents = new LinkedHashMap<>();

    private AgentLoader() {}

    /**
     * Loads all agent definitions: built-in specs, then user-level, then project-level.
     *
     * @param projectRoot the project root directory (may be {@code null} to skip project-level)
     * @return a map of agent name to spec
     */
    public static Map<String, SubAgentSpec> loadAll(Path projectRoot) {
        var loader = new AgentLoader();
        loader.loadBuiltins();

        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            loader.loadDir(Path.of(home, ".mewcode", "agents"));
        }

        if (projectRoot != null) {
            loader.loadDir(projectRoot.resolve(".mewcode").resolve("agents"));
        }

        return Collections.unmodifiableMap(loader.agents);
    }

    /**
     * Returns a sorted list of all loaded agent names.
     */
    public static List<String> listNames(Map<String, SubAgentSpec> agents) {
        var names = new ArrayList<>(agents.keySet());
        Collections.sort(names);
        return names;
    }

    private void loadBuiltins() {
        agents.put(SubAgentSpec.GENERAL_PURPOSE.name(), SubAgentSpec.GENERAL_PURPOSE);
        agents.put(SubAgentSpec.PLAN.name(), SubAgentSpec.PLAN);
        agents.put(SubAgentSpec.EXPLORE.name(), SubAgentSpec.EXPLORE);
    }

    private void loadDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.md")) {
            for (Path path : stream) {
                if (Files.isDirectory(path)) {
                    continue;
                }
                try {
                    SubAgentSpec spec = parseAgentFile(path);
                    agents.put(spec.name(), spec);
                } catch (Exception e) {
                    // Skip invalid files silently, matching Go behaviour
                }
            }
        } catch (IOException e) {
            // Directory unreadable -- skip
        }
    }

    /**
     * Parses a single agent definition file. The file may optionally begin
     * with YAML frontmatter between {@code ---} delimiters.
     */
    static SubAgentSpec parseAgentFile(Path path) throws IOException {
        String content = Files.readString(path);
        String trimmed = content.strip();

        String yamlBlock = null;
        String body = trimmed;

        if (trimmed.startsWith("---")) {
            // Split on the second "---" delimiter
            int firstEnd = trimmed.indexOf("---", 3);
            if (firstEnd >= 0) {
                yamlBlock = trimmed.substring(3, firstEnd).strip();
                body = trimmed.substring(firstEnd + 3).strip();
            }
        }

        String name = null;
        String description = null;
        List<String> tools = List.of();
        List<String> disallowedTools = List.of();
        String model = null;
        int maxTurns = 0;

        if (yamlBlock != null && !yamlBlock.isEmpty()) {
            Yaml yaml = new Yaml();
            Map<String, Object> frontmatter = yaml.load(yamlBlock);
            if (frontmatter != null) {
                name = getString(frontmatter, "name");
                description = getString(frontmatter, "description");
                tools = getStringList(frontmatter, "tools");
                disallowedTools = getStringList(frontmatter, "disallowedTools");
                model = getString(frontmatter, "model");
                Object maxTurnsObj = frontmatter.get("maxTurns");
                if (maxTurnsObj instanceof Number n) {
                    maxTurns = n.intValue();
                }
            }
        }

        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException(
                    "Agent definition %s: missing required field 'name'".formatted(path));
        }
        if (description == null || description.isEmpty()) {
            throw new IllegalArgumentException(
                    "Agent definition %s: missing required field 'description'".formatted(path));
        }
        if (model != null && !VALID_MODELS.contains(model)) {
            throw new IllegalArgumentException(
                    "Agent definition %s: invalid model '%s'".formatted(path, model));
        }

        String systemPrompt = body.isEmpty() ? null : body;

        return new SubAgentSpec(name, description, tools, disallowedTools, systemPrompt, maxTurns, model);
    }

    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> getStringList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof List<?> list) {
            var result = new ArrayList<String>();
            for (Object item : list) {
                if (item instanceof String s) {
                    result.add(s);
                }
            }
            return List.copyOf(result);
        }
        return List.of();
    }
}
