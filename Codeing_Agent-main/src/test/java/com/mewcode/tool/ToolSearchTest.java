// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.tool.impl.ToolSearchTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolSearchTest {

    private ToolRegistry registry;

    private ToolSearchTool toolSearch;

    /** A deferred tool with a realistic-sized schema (~500 chars JSON per tool). */
    private static Tool heavyDeferredTool(String name, int propCount) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "A realistic deferred tool " + name + " with detailed description for search matching"; }
            @Override public ToolCategory category() { return ToolCategory.READ; }
            @Override public Map<String, Object> schema() {
                var props = new HashMap<String, Object>();
                for (int i = 0; i < propCount; i++) {
                    props.put("param_" + i, Map.of(
                            "type", "string",
                            "description", "Parameter " + i + " for " + name + " - accepts a string value for configuration"
                    ));
                }
                return Map.of(
                        "name", name,
                        "description", description(),
                        "input_schema", Map.of(
                                "type", "object",
                                "properties", props,
                                "required", List.of("param_0")
                        )
                );
            }
            @Override public ToolResult execute(Map<String, Object> args) {
                return ToolResult.success("ok");
            }
            @Override public boolean shouldDefer() { return true; }
        };
    }

    /** A minimal deferred tool for testing. */
    private static Tool deferredTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "Deferred tool " + name; }
            @Override public ToolCategory category() { return ToolCategory.READ; }
            @Override public Map<String, Object> schema() {
                return Map.of(
                        "name", name,
                        "description", description(),
                        "input_schema", Map.of("type", "object", "properties", Map.of())
                );
            }
            @Override public ToolResult execute(Map<String, Object> args) {
                return ToolResult.success("ok");
            }
            @Override public boolean shouldDefer() { return true; }
        };
    }

    /** A minimal non-deferred tool for testing. */
    private static Tool normalTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "Normal tool " + name; }
            @Override public ToolCategory category() { return ToolCategory.READ; }
            @Override public Map<String, Object> schema() {
                return Map.of(
                        "name", name,
                        "description", description(),
                        "input_schema", Map.of("type", "object", "properties", Map.of())
                );
            }
            @Override public ToolResult execute(Map<String, Object> args) {
                return ToolResult.success("ok");
            }
        };
    }

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        registry.register(normalTool("Read"));
        registry.register(normalTool("Write"));
        registry.register(deferredTool("WebFetch"));
        registry.register(deferredTool("Monitor"));
        toolSearch = new ToolSearchTool(registry, "anthropic");
        registry.register(toolSearch);
    }

    @Test
    void testDeferredNotInSchemas() {
        var schemas = registry.getAllSchemas("anthropic");
        var names = schemas.stream()
                .map(s -> (String) s.get("name"))
                .toList();

        assertTrue(names.contains("Read"), "Normal tool Read should be in schemas");
        assertTrue(names.contains("Write"), "Normal tool Write should be in schemas");
        assertFalse(names.contains("WebFetch"), "Deferred tool WebFetch should NOT be in schemas");
        assertFalse(names.contains("Monitor"), "Deferred tool Monitor should NOT be in schemas");
    }

    @Test
    void testToolSearchMarksDiscovered() {
        // Before search, WebFetch should not be discovered
        assertFalse(registry.isDiscovered("WebFetch"));

        // Execute ToolSearch with select query
        var result = toolSearch.execute(Map.of("query", "select:WebFetch"));
        assertFalse(result.isError(), "ToolSearch should succeed");
        assertTrue(result.output().contains("1 tool(s)"));
        assertTrue(result.output().contains("full schemas are now loaded"));

        // After search, WebFetch should be marked discovered
        assertTrue(registry.isDiscovered("WebFetch"));
        // Monitor should still not be discovered
        assertFalse(registry.isDiscovered("Monitor"));
    }

    @Test
    void testDiscoveredInSchemas() {
        // Mark WebFetch as discovered
        registry.markDiscovered("WebFetch");

        var schemas = registry.getAllSchemas("anthropic");
        var names = schemas.stream()
                .map(s -> (String) s.get("name"))
                .toList();

        assertTrue(names.contains("WebFetch"), "Discovered deferred tool should appear in schemas");
        assertFalse(names.contains("Monitor"), "Undiscovered deferred tool should NOT appear in schemas");
    }

    @Test
    void testGetDeferredToolNames() {
        // Initially both deferred tools should be in the deferred list
        // (ToolSearch itself is NOT deferred — it must always be available)
        var deferred = registry.getDeferredToolNames();
        assertTrue(deferred.contains("WebFetch"));
        assertTrue(deferred.contains("Monitor"));
        assertFalse(deferred.contains("ToolSearch"), "ToolSearch should not be deferred");

        // Discover WebFetch
        registry.markDiscovered("WebFetch");

        deferred = registry.getDeferredToolNames();
        assertFalse(deferred.contains("WebFetch"), "Discovered tool should no longer be in deferred names");
        assertTrue(deferred.contains("Monitor"), "Undiscovered tool should still be in deferred names");
    }

    @Test
    void testDeferredTokenSavings() throws Exception {
        var reg = new ToolRegistry();

        // 2 normal tools with small schemas
        reg.register(normalTool("Read"));
        reg.register(normalTool("Write"));

        // 50 deferred tools with realistic schemas (5-10 properties each)
        for (int i = 0; i < 50; i++) {
            int propCount = 5 + (i % 6); // varies between 5 and 10
            reg.register(heavyDeferredTool("DeferredTool_" + i, propCount));
        }

        var mapper = new ObjectMapper();

        // With deferred tools hidden
        var schemasDeferred = reg.getAllSchemas("anthropic");
        int sizeDeferred = mapper.writeValueAsString(schemasDeferred).length();

        // Discover all 50 deferred tools
        for (int i = 0; i < 50; i++) {
            reg.markDiscovered("DeferredTool_" + i);
        }

        // With all tools visible
        var schemasAll = reg.getAllSchemas("anthropic");
        int sizeAll = mapper.writeValueAsString(schemasAll).length();

        double savings = 1.0 - (double) sizeDeferred / sizeAll;
        System.out.printf("Deferred token savings: %.1f%% (deferred=%d chars, all=%d chars)%n",
                savings * 100, sizeDeferred, sizeAll);

        assertTrue(savings >= 0.90,
                String.format("Expected >= 90%% savings but got %.1f%%", savings * 100));
    }

    @Test
    void testDeferredEndToEndDiscovery() {
        var reg = new ToolRegistry();

        reg.register(normalTool("Bash"));
        reg.register(deferredTool("WebFetch"));
        reg.register(deferredTool("Monitor"));

        // Deferred tools should NOT appear in getAllSchemas
        var schemas = reg.getAllSchemas("anthropic");
        var names = schemas.stream().map(s -> (String) s.get("name")).toList();
        assertTrue(names.contains("Bash"), "Normal tool should be in schemas");
        assertFalse(names.contains("WebFetch"), "Deferred WebFetch should NOT be in schemas");
        assertFalse(names.contains("Monitor"), "Deferred Monitor should NOT be in schemas");

        // getDeferredToolNames should return both deferred tools
        var deferred = reg.getDeferredToolNames();
        assertTrue(deferred.contains("WebFetch"));
        assertTrue(deferred.contains("Monitor"));

        // Discover one tool
        reg.markDiscovered("WebFetch");

        // WebFetch should now appear in schemas
        schemas = reg.getAllSchemas("anthropic");
        names = schemas.stream().map(s -> (String) s.get("name")).toList();
        assertTrue(names.contains("WebFetch"), "Discovered WebFetch should now appear in schemas");
        assertFalse(names.contains("Monitor"), "Undiscovered Monitor should still NOT appear");

        // getDeferredToolNames should only return Monitor
        deferred = reg.getDeferredToolNames();
        assertFalse(deferred.contains("WebFetch"), "Discovered WebFetch should not be in deferred names");
        assertTrue(deferred.contains("Monitor"), "Monitor should still be in deferred names");
    }
}
