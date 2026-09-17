// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.tool;

import java.util.*;

public class ToolRegistry {

    public static final int MAX_OUTPUT_CHARS = 10_000;

    private final Map<String, Tool> tools = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> discoveredTools = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public void markDiscovered(String name) {
        discoveredTools.add(name);
    }

    public boolean isDiscovered(String name) {
        return discoveredTools.contains(name);
    }

    public List<String> getDeferredToolNames() {
        return tools.values().stream()
                .filter(t -> t.shouldDefer() && !discoveredTools.contains(t.name()))
                .map(Tool::name)
                .toList();
    }

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public List<Tool> listTools() {
        return List.copyOf(tools.values());
    }

    public List<Map<String, Object>> getAllSchemas(String protocol) {
        var schemas = new ArrayList<Map<String, Object>>();
        for (var tool : tools.values()) {
            if (tool.shouldDefer() && !discoveredTools.contains(tool.name())) continue;
            var base = tool.schema();
            if ("openai".equals(protocol)) {
                schemas.add(Map.of(
                        "type", "function",
                        "name", base.get("name"),
                        "description", base.get("description"),
                        "parameters", base.get("input_schema")
                ));
            } else {
                schemas.add(base);
            }
        }
        return schemas;
    }

    public List<Tool> getDeferredTools() {
        return tools.values().stream()
                .filter(Tool::shouldDefer)
                .toList();
    }

    public List<Map<String, Object>> searchDeferred(String query, int maxResults, String protocol) {
        String lower = query.toLowerCase();
        var matches = new ArrayList<Map<String, Object>>();
        for (var tool : tools.values()) {
            if (!tool.shouldDefer()) continue;
            if (tool.name().toLowerCase().contains(lower)
                    || tool.description().toLowerCase().contains(lower)) {
                var base = tool.schema();
                if ("openai".equals(protocol)) {
                    matches.add(Map.of(
                            "type", "function",
                            "name", base.get("name"),
                            "description", base.get("description"),
                            "parameters", base.get("input_schema")
                    ));
                } else {
                    matches.add(base);
                }
                if (matches.size() >= maxResults) break;
            }
        }
        return matches;
    }

    public List<Map<String, Object>> findDeferredByNames(List<String> names, String protocol) {
        var nameSet = new HashSet<String>();
        for (var n : names) nameSet.add(n.toLowerCase());

        var matches = new ArrayList<Map<String, Object>>();
        for (var tool : tools.values()) {
            if (nameSet.contains(tool.name().toLowerCase())) {
                var base = tool.schema();
                if ("openai".equals(protocol)) {
                    matches.add(Map.of(
                            "type", "function",
                            "name", base.get("name"),
                            "description", base.get("description"),
                            "parameters", base.get("input_schema")
                    ));
                } else {
                    matches.add(base);
                }
            }
        }
        return matches;
    }

    public static ToolRegistry createDefault() {
        var reg = new ToolRegistry();
        reg.register(new com.mewcode.tool.impl.ReadFileTool());
        reg.register(new com.mewcode.tool.impl.WriteFileTool());
        reg.register(new com.mewcode.tool.impl.EditFileTool());
        reg.register(new com.mewcode.tool.impl.BashTool());
        reg.register(new com.mewcode.tool.impl.GlobTool());
        reg.register(new com.mewcode.tool.impl.GrepTool());
        return reg;
    }
}
