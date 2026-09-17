# MewCode

MewCode 是一个 AI Coding Agent 的完整实现，帮助你理解 AI Agent 的核心原理与工程实践。

## 环境要求

- Java 21+
- Gradle（项目自带 Gradle Wrapper，无需单独安装）

## 快速开始

### 1. 配置

编辑 `.mewcode/config.yaml`，填入你的 LLM 提供商信息：

```yaml
providers:
  - name: anthropic-official
    protocol: anthropic                          # 支持 anthropic / openai 两种协议
    base_url: https://your-api-provider.com/api/anthropic
    api_key: "your-api-key-here"
    model: claude-sonnet-4-6
    thinking: true                               # 是否开启 extended thinking

mcp_servers:
  - name: context7
    command: npx
    args: ["-y", "@upstash/context7-mcp"]
```

**说明：**
- `protocol`：填 `anthropic` 或 `openai`，取决于你的提供商兼容哪种 API
- `base_url`：你的 API 地址
- `api_key`：你的 API Key
- `model`：模型名称
- `mcp_servers`：MCP Server 列表，每项需要 `name`、`command` 和 `args`

### 2. 构建 & 运行

```bash
./gradlew shadowJar
java -jar build/libs/mewcode.jar
```

### 3. 运行测试

```bash
./gradlew test
```
