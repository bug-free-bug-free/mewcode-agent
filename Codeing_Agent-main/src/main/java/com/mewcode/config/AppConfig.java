// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.config;

import java.util.List;

public class AppConfig {

    private List<ProviderConfig> providers;
    private String permissionMode;

    private List<McpServerConfig> mcpServers;
    private List<HookConfig> hooks;

    public List<ProviderConfig> getProviders() { return providers; }

    public void setProviders(List<ProviderConfig> providers) { this.providers = providers; }

    public String getPermissionMode() { return permissionMode; }

    public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }

    public List<McpServerConfig> getMcpServers() { return mcpServers; }
    public void setMcpServers(List<McpServerConfig> mcpServers) { this.mcpServers = mcpServers; }

    public List<HookConfig> getHooks() { return hooks; }
    public void setHooks(List<HookConfig> hooks) { this.hooks = hooks; }
}
