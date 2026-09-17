// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.mcp;

import com.mewcode.config.McpServerConfig;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

public class McpManager {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-zA-Z0-9_]");

    private static final Pattern ENV_VAR = Pattern.compile("\\$\\{([^}]+)}");

    public record ServerInfo(String name, String instructions) {}
    public record ConnectResult(List<Tool> tools, List<ServerInfo> servers, List<String> errors) {}

    private final Map<String, McpServerConfig> configs = new LinkedHashMap<>();
    private final Map<String, McpSyncClient> clients = new LinkedHashMap<>();

    public McpManager(List<McpServerConfig> configs) {
        if (configs != null) {
            for (var cfg : configs) this.configs.put(cfg.getName(), cfg);
        }
    }

    public ConnectResult connectAll() {
        var tools = new ArrayList<Tool>();
        var servers = new ArrayList<ServerInfo>();
        var errors = new ArrayList<String>();

        for (var entry : configs.entrySet()) {
            String name = entry.getKey();
            var cfg = entry.getValue();

            try {
                var client = createClient(cfg);
                client.initialize();
                clients.put(name, client);

                String instructions = client.getServerInstructions();
                servers.add(new ServerInfo(name, instructions != null ? instructions : ""));

                var result = client.listTools();
                if (result != null && result.tools() != null) {
                    for (var sdkTool : result.tools()) {
                        tools.add(new McpToolWrapper(name, sdkTool, client));
                    }
                }
            } catch (Exception e) {
                errors.add("MCP server '" + name + "': " + e.getMessage());
            }
        }

        return new ConnectResult(List.copyOf(tools), List.copyOf(servers), List.copyOf(errors));
    }

    public List<String> registerAllTools(ToolRegistry registry) {
        var result = connectAll();
        for (var t : result.tools()) registry.register(t);
        return result.errors();
    }

    public void shutdown() {
        for (var client : clients.values()) {
            try { client.closeGracefully(); } catch (Exception ignored) {}
        }
        clients.clear();
    }

    private McpSyncClient createClient(McpServerConfig cfg) {
        io.modelcontextprotocol.spec.McpClientTransport transport;

        if (cfg.getCommand() != null && !cfg.getCommand().isBlank()) {
            var paramsBuilder = ServerParameters.builder(windowsSafe(cfg.getCommand()));
            if (cfg.getArgs() != null) {
                paramsBuilder.args(cfg.getArgs());
            }
            if (cfg.getEnv() != null) {
                var resolvedEnv = new HashMap<String, String>();
                for (var e : cfg.getEnv().entrySet()) {
                    resolvedEnv.put(e.getKey(), resolveEnvVars(e.getValue()));
                }
                paramsBuilder.env(resolvedEnv);
            }
            transport = new StdioClientTransport(paramsBuilder.build(), McpJsonDefaults.getMapper());
        } else if (cfg.getUrl() != null && !cfg.getUrl().isBlank()) {
            var httpBuilder = HttpClientStreamableHttpTransport.builder(cfg.getUrl());
            if (cfg.getHeaders() != null && !cfg.getHeaders().isEmpty()) {
                httpBuilder.customizeRequest(rb -> {
                    for (var e : cfg.getHeaders().entrySet()) {
                        rb.header(e.getKey(), resolveEnvVars(e.getValue()));
                    }
                });
            }
            transport = httpBuilder.build();
        } else {
            throw new IllegalArgumentException("Neither command nor url configured");
        }

        return McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("mewcode", "0.1.0"))
                .requestTimeout(Duration.ofSeconds(60))
                .build();
    }

    private static final Set<String> WIN_CMD_SUFFIXED = Set.of(
            "npx", "npm", "node", "uvx", "uv", "pnpm", "yarn", "bunx");

    static String windowsSafe(String command) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return command;
        String base = command.toLowerCase();
        if (WIN_CMD_SUFFIXED.contains(base)) return command + ".cmd";
        return command;
    }

    static String sanitizeName(String name) {
        return NON_ALNUM.matcher(name).replaceAll("_");
    }

    static String resolveEnvVars(String value) {
        if (value == null) return null;
        return ENV_VAR.matcher(value).replaceAll(m -> {
            String env = System.getenv(m.group(1));
            return env != null ? env : m.group(0);
        });
    }

    // ── MCP Tool Wrapper ────────────────────────────────────────────────

    private static class McpToolWrapper implements Tool {
        private final String serverName;
        private final McpSchema.Tool sdkTool;
        private final McpSyncClient client;

        McpToolWrapper(String serverName, McpSchema.Tool sdkTool, McpSyncClient client) {
            this.serverName = serverName;
            this.sdkTool = sdkTool;
            this.client = client;
        }

        @Override public String name() {
            return "mcp__" + sanitizeName(serverName) + "__" + sanitizeName(sdkTool.name());
        }

        @Override public String description() {
            return sdkTool.description() != null ? sdkTool.description() : "";
        }

        @Override public ToolCategory category() { return ToolCategory.COMMAND; }
        @Override public boolean shouldDefer() { return true; }

        @Override public Map<String, Object> schema() {
            var input = new LinkedHashMap<String, Object>();
            var jsonSchema = sdkTool.inputSchema();
            if (jsonSchema != null) {
                if (jsonSchema.type() != null) input.put("type", jsonSchema.type());
                if (jsonSchema.properties() != null) input.put("properties", jsonSchema.properties());
                if (jsonSchema.required() != null) input.put("required", jsonSchema.required());
            }
            if (input.isEmpty()) {
                input.put("type", "object");
                input.put("properties", Map.of());
            }
            return Map.of("name", name(), "description", description(), "input_schema", input);
        }

        @Override public ToolResult execute(Map<String, Object> args) {
            try {
                var request = new McpSchema.CallToolRequest(
                        sdkTool.name(), args != null ? args : Map.of());
                var result = client.callTool(request);
                String text = extractTextContent(result);
                boolean isError = result.isError() != null && result.isError();
                return isError ? ToolResult.error(text) : ToolResult.success(text);
            } catch (Exception e) {
                return ToolResult.error("MCP tool call failed: " + e.getMessage());
            }
        }
    }

    private static String extractTextContent(McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) return "(no output)";
        var sb = new StringBuilder();
        for (var content : result.content()) {
            if (content instanceof McpSchema.TextContent tc) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(tc.text());
            }
        }
        return sb.isEmpty() ? "(no output)" : sb.toString();
    }
}
