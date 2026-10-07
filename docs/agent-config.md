# Agent config 结构（agent.config / agent_version.config）

前端编辑、后端存储、执行器消费都用这一份结构。所有字段可选，缺省值见注释。

```jsonc
{
  "prompt": "# 人设与回复逻辑（Markdown，作为 system prompt）",
  "model": {
    "primary": "providerKey/modelId",          // 主模型
    "fallbacks": ["providerKey/modelId"],       // 备用容灾模型，按顺序尝试
    "retry": { "enabled": true, "times": 3, "baseDelay": 5, "maxDelay": 60 }, // 秒
    "showThinking": false
  },
  "runtime": {
    "kind": "claudecode",          // claudecode | cloudai （运行时类型，目前都由 Python 执行器实现）
    "env": "cloudai-cli-runtime",  // 默认运行环境（runtime_env.name）
    "maxSessions": 10,             // 单实例最大会话数
    "sandboxIsolation": false,     // 用户沙箱隔离：同一用户的会话共享工作目录
    "workspace": "",               // 默认工作区（持久化存储）；空 = 不使用工作区
    "maxTurns": 40,                // 单次运行最大迭代次数
    "timeoutSeconds": 1800,
    "temperature": null
  },
  "hooks": [1, 2],                 // hook.id 列表
  "builtinTools": ["Bash", "Read", "Edit", "Write", "LS", "Glob", "Grep", "artifact_save"],
  "mcpServers": [ { "pluginId": 3, "name": "galaxy_mcp_tcc" } ],          // tool_plugin(type=mcp/openapi/cloudlog)
  "cliPlugins": [ { "type": "gitlab|lark|cloudai", "credentialId": 5, "identity": "app|user", "label": "报错运维Agent助手" } ],
  "skills": [ { "skillId": "uuid", "name": "get-flink-detail", "version": "1.0.21" } ],   // version 为空 = 最新
  "memoryStores": [ { "storeId": "uuid", "access": "rw|ro", "instruction": "使用说明" } ],
  "memoryEnabled": false,          // 启用会话记忆（自动把对话摘要写入 Agent 私有记忆）
  "subAgents": [ { "agentId": "uuid", "name": "xx", "description": "xx" } ],  // 仅限同一空间
  "envVars": [
    { "type": "plain",  "name": "LOG_LEVEL", "value": "info" },
    { "type": "secret", "credentialId": 5, "field": "api_key", "name": "OPENAI_API_KEY" },
    { "type": "secret", "credentialId": 5, "scope": "all" }               // 凭据全部字段，用各字段默认环境变量名
  ],
  "metadata": [ { "key": "team", "value": "risk" } ]
}
```

# 执行器事件（executor → backend，SSE `data: {json}`）

| type | 字段 | 落库 |
|---|---|---|
| `model` | name, input_tokens, output_tokens, cache_read, cache_write, duration_ms | session_event(type=model) |
| `thinking` | content | session_event(type=thinking) |
| `agent` | content, duration_ms | session_event(type=agent) |
| `tool_start` | call_id, name, args(json) | session_event(type=tool,status=running) |
| `tool_end` | call_id, name, result, status(ok/error), duration_ms | 更新同一行 |
| `artifact` | name, path(绝对路径), mime | file_info(category=artifact) |
| `skill_used` | name | agent_run.skills_used / skill.call_count |
| `error` | content | session_event(type=error) |
| `done` | status(success/failed/stopped), final_text, turns, error | agent_run / chat_session |

# 后端 → 前端

- REST：`/api/**`，响应 `{code:0, message, data}`，鉴权 `Authorization: Bearer <token>`，空间 `X-Space-Id`。
- 会话实时事件：`GET /api/sessions/{id}/stream?token=&spaceId=&after=<seq>`（SSE，事件名 `event`，data 为 session_event JSON；会话状态变化事件名 `session`）。
