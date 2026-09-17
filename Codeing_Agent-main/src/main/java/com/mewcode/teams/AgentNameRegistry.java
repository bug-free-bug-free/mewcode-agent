// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.teams;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global singleton maintaining name → agent_id mappings.
 */
public final class AgentNameRegistry {

    private static final AgentNameRegistry INSTANCE = new AgentNameRegistry();

    private final Map<String, String> nameToId = new LinkedHashMap<>();

    private AgentNameRegistry() {}

    public static AgentNameRegistry getInstance() { return INSTANCE; }

    public synchronized void register(String name, String agentId) {
        nameToId.put(name, agentId);
    }

    public synchronized String resolve(String nameOrId) {
        if (nameToId.containsKey(nameOrId)) return nameToId.get(nameOrId);
        if (nameToId.containsValue(nameOrId)) return nameOrId;
        return null;
    }

    public synchronized void unregister(String name) {
        nameToId.remove(name);
    }

    public synchronized Map<String, String> listAll() {
        return new LinkedHashMap<>(nameToId);
    }
}
