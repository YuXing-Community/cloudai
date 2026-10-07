# CloudAI 开发约定（所有模块必读）

项目根目录：`/Users/admin/Desktop/cloudeai/cloudai`
截图与需求：`/Users/admin/Desktop/cloudeai/开发前须知/`（界面必须与截图一致，可以更美观）

## 端口
- 前端 Vite：5573（代理 `/api` → 8580）
- 后端 Spring Boot：8580
- Python 执行器：8590
- Milvus（CloudAI 独立实例）：19540

## 后端（Java 17 / Spring Boot 3.2 / MyBatis-Plus 3.5.5 / Lombok）
- 包结构：`com.cloudai.{common,auth,config,entity,mapper,service,web}`。
- **实体和 Mapper 已全部写好**，直接用，不要新建重复实体：
  - `entity/`：`User, Space, SpaceRole, SpaceMember, Agent, AgentVersion, AgentLinks.{AgentFavorite,AgentGrant}, ChatSession, SessionEvent, AgentRun, ModelEntities.{ModelProvider,ModelDef}, PluginEntities.{Skill,SkillVersion,ToolPlugin,ToolItem,Credential,Hook}, OpsEntities.{ScheduleTask,ScheduleRun,MessageChannel,FileInfo,MemoryStore,MemoryDoc}, RuntimeEntities.{RuntimeEnv,Sandbox,AgentTeam,TeamSession,TeamTask,TeamRun,TeamMessage}`
  - `mapper/CoreMappers.{UserMapper,SpaceMapper,SpaceRoleMapper,SpaceMemberMapper}`，`mapper/Mappers.*Mapper`（每个实体一个，如 `Mappers.AgentMapper`）
  - 表结构：`backend/src/main/resources/db/schema.sql`（如需加字段，用 `ALTER TABLE` 追加到 `db/schema.sql` 末尾并保证可重复执行是做不到的——**不要改表结构**，用已有字段或 JSON 字段承载）。
- JSON 列在实体里是 `JsonNode`（`JsonNodeTypeHandler`），构造用 `Json.obj()/Json.arr()`，工具类 `com.cloudai.common.Json`。
- 响应统一 `R.ok(data)`；分页 `PageResult.of(page)`；错误 `throw new BizException("中文信息")` / `BizException.notFound("Agent")` / `BizException.check(cond, msg)`。
- 当前用户/空间：`UserContext.userId()`、`UserContext.spaceId()`、`UserContext.username()`、`UserContext.has(Perm.X)`。
- 权限：在 Controller 方法上加 `@RequirePerm(Perm.AGENT_EDIT)`；查询类接口用 `*_VIEW`。权限点见 `auth/Perm.java`。
- **空间隔离**：所有查询必须带 `space_id = UserContext.spaceId()`；按 id 取单条后必须校验 `spaceId` 一致，否则 `BizException.notFound`。
- 已有服务（直接注入使用）：
  - `SpaceService.userBrief(userId)` → `{id, username, displayName, email, avatar}`（列表里展示负责人/创建人）
  - `Storage`：data 目录（`skillDir/filesDir/memoryDir/agentDir/workspaceDir/rel/abs/within/safe/deleteRecursively/guessMime`）
  - `ModelService.resolve(spaceId, "providerKey/modelId")`、`defaultRef(spaceId)`、`cost(...)`
  - `CredentialService.get/fields/values/setFields`（凭据 AES 加密，前端永不回显原值）
  - `Crypto.encrypt/decrypt/mask/randomToken`
  - `RunService.createSession(agent, new RunService.SessionOptions(title, source, sourceRef, creatorId, modelRef, runtimeEnv, agentVersion))`、`RunService.send(session, text, fileIds, trigger, userId)`（异步，返回 `CompletableFuture<RunResult>`）、`RunService.sendAndWait(session, text, trigger, userId, Duration)`、`RunService.stop(sessionId)`、`RunService.isRunning(sessionId)`
  - `SessionHub.subscribe(sessionId)` → `SseEmitter`
  - `AgentResolver.config(agent, version)`、`AgentResolver.DEFAULT_TOOLS`
  - `ExecutorClient.call("POST", "/path", body, timeoutSec)` → `JsonNode`（调用 Python 执行器）
- Controller 放在 `com.cloudai.web` 下，**每个模块用自己的 Controller 文件**，类名不要和别人冲突。
- 接口前缀 `/api/...`；时间字段 `LocalDateTime`（Jackson 输出 `yyyy-MM-dd HH:mm:ss`）。
- 执行器回调后端的内部接口前缀 `/api/internal/**`（拦截器用 `X-Internal-Token` 校验，`X-Space-Id` 头传空间）。

## 跨模块接口（谁负责实现见括号，其他模块直接调用）
| 接口 | 返回 | 负责 |
|---|---|---|
| `GET /api/agents/options` | `[{id,name,identifier,avatar,version,description}]` | 智能体 |
| `GET /api/models/options` | `[{providerKey,providerName,models:[{modelId,ref,displayName,level,isDefault,contextWindow}]}]` | 空间设置 |
| `GET /api/runtime-envs/options` | `[{name,description,isDefault,usageType,isSystem}]` | 沙箱 |
| `GET /api/credentials/options?type=` | `[{id,name,type,fields:[{name,envName}]}]` | 工具/凭据 |
| `POST /api/credentials` | 新建凭据 `{name,type,fields:[{name,envName,value}]}` → Credential | 工具/凭据 |
| `GET /api/tool-plugins/options?type=` | `[{id,name,type,endpoint,description,toolCount}]` | 工具/凭据 |
| `GET /api/skills/options` | `[{id,name,description,currentVersion,versions:[...],scope,author}]` | Skills |
| `GET /api/hooks/options` | `[{id,name,description}]` | Skills/Hooks |
| `GET /api/memory-stores/options` | `[{id,name,description,provider,status}]` | 记忆库 |
| `GET /api/channels/options` | `[{id,name,type,status}]` | 通道 |
| `POST /api/files`（multipart: file, name?, source=manual/chat, sessionId?） | FileInfo | 文件中心 |
| `GET /api/files/{id}/download?token=&spaceId=&inline=1` | 文件流 | 文件中心 |
| `GET /api/files/{id}/text` | `{name,mimeType,content}` | 文件中心 |
| `GET /api/sessions?agentId=` / `/api/sessions/{id}` | 会话 | 会话 |

### 会话 API（会话模块实现；智能体编辑页的「Agent 调试」面板也调用这些）
| 接口 | 说明 |
|---|---|
| `GET /api/sessions?agentId=&mine=0/1&source=&status=&version=&q=&page=&size=` | 分页 `{id,title,source,sourceRef,status,agentId,agentVersion,modelRef,runtimeEnv,creator,createdAt,updatedAt,lastEventAt}` |
| `POST /api/sessions` `{agentId, title?, modelRef?, runtimeEnv?, source?: 'web'/'debug', agentVersion?}` | 创建会话（调用 `RunService.createSession`） |
| `GET /api/sessions/{id}` | 会话 + agent 简要信息 |
| `PUT /api/sessions/{id}` `{title}` / `DELETE /api/sessions/{id}` | 改名 / 删除 |
| `POST /api/sessions/{id}/messages` `{content, fileIds?}` | 异步执行，返回 `{runId}` |
| `POST /api/sessions/{id}/stop` | 停止执行 |
| `GET /api/sessions/{id}/events?after=0` | `SessionEvent[]`（按 seq） |
| `GET /api/sessions/{id}/stream?token=&spaceId=&after=` | SSE：事件名 `event`（SessionEvent JSON）、`session`（ChatSession JSON） |
| `GET /api/sessions/{id}/artifacts` | 产物 FileInfo[] |
| `GET /api/sessions/{id}/info` | 用量统计/运行环境/时间 |

SessionEvent 字段：`id, sessionId, runId, seq, type(input/agent/tool/model/thinking/error/status), name, content, result, status(ok/error/running), durationMs, inputTokens, outputTokens, startedAt, createdAt`。
工具事件 `content` 为参数 JSON 字符串、`result` 为结果文本；`status` 事件 `name=artifact` 时 `content` 为 `{fileId,name,version}`。

### LLM 直接调用（执行器提供）
`POST /llm/complete` `{model: <ModelService.toExecutorSpec 结果>, system?, messages:[{role,content}], max_tokens?}` → `{text, usage:{input_tokens,output_tokens}, duration_ms}`（用于 AI 创建 Agent 草稿、Provider 测试连接）。
后端拿模型 spec：`modelService.toExecutorSpec(modelService.resolve(spaceId, ref))`。Provider 测试连接时可自己拼 spec：`{base_url, api_key, api_type, model}`。

## Python 执行器接口（`executor/`，由主 Agent 实现，其他模块按此调用）
| 接口 | 说明 |
|---|---|
| `POST /run`（SSE） | Agent 执行（RunService 已接好） |
| `POST /runs/{runId}/cancel` | 停止 |
| `GET /health` | `{ok, embedding_model, milvus}` |
| `POST /mcp/discover` `{endpoint, transport, headers:{}}` | `{tools:[{name,description,input_schema}]}` |
| `POST /tools/invoke` `{plugin:{type,endpoint,transport,headers,config,credential,tools:[...]}, tool, args}` | `{ok, result, duration_ms}`（插件调试） |
| `POST /embed/index` `{space_id, store_id, doc_id, path, text, file_path}` | `{chunks, keywords:[...]}`，写入 Milvus 并在记忆库目录生成 `_index/keywords.md` |
| `POST /embed/delete` `{store_id, doc_id?}` | `{deleted}` |
| `POST /embed/search` `{space_id, store_ids:[...], query, top_k}` | `{model, dim, results:[{store_id,doc_id,path,chunk_index,text,score}]}` |
| `POST /docs/convert` `{file_path}` | `{text}`：xlsx/xls/xlsm/csv → Markdown 表格文本 |
| `POST /channels/feishu/start` `{channel_id, space_id, app_id, app_secret, settings}` | 建立飞书 WebSocket 长连接 |
| `POST /channels/feishu/stop` `{channel_id}` | |
| `GET /channels/status` | `{channels:{id:{state,error}}}` |
| `POST /feishu/send` `{app_id, app_secret, chat_id, text, title?}` | 发送卡片消息（定时任务接收方通知） |
| `GET /feishu/bot-info?app_id&app_secret` → `{bot_name}` | 校验凭据并取机器人名 |

飞书收到消息后执行器回调后端 `POST /api/internal/channels/{channelId}/message`
`{chat_id, chat_type, message_id, thread_id, text, sender_id, sender_name}` → 后端执行 Agent 并返回 `{reply, sessionId, status, footer}`，执行器负责回复到飞书。

## 前端（Vue 3 + Vite + TS + Pinia + lucide-vue-next）
- 设计系统：`src/styles/base.css`（`.page .page-head .page-title .page-sub .btn .btn-primary .btn-sm .icon-btn .input .textarea .field .form-row .tag .tag-green/blue/orange/red/purple/cyan .table-wrap .table .segmented .tabs .card .empty-box .notice .md .code-block` 等），**优先用这些类**，页面内 `<style scoped>` 只写特有样式；颜色只用 CSS 变量（支持深色模式）。
- 组件 `src/components/ui/`：`Modal`（props: open,title,subtitle,width,flush；slots: default/footer/head-extra）、`Drawer`、`Select`（options: `{label,value,sub,group,extra,tag}`，props: searchable, chevrons, size）、`DropdownMenu`（items: `{label,icon,danger,divider,onClick}`）、`Popover`、`Switch`、`Pagination`、`SearchInput`、`Empty`、`AgentAvatar`（name, avatar, size, radius）、`UserAvatar`（name, avatar, size, showName, sub）、`MarkdownView`（source, compact）、`toast.ts`（`toast.success/error`、`await confirm({title,message,danger,requireText})`）。
- HTTP：`import { http, authedUrl, openStream } from '@/api/http'`；`http.get(url, query)` / `post` / `put` / `del` / `upload(url, formData)`，自动带 token 与空间头，错误自动 toast。每个模块写自己的 `src/api/<module>.ts`。
- 权限：`const auth = useAuth()`（`@/stores/auth`），`auth.can('agent:edit')` 控制按钮；`auth.user`、`auth.space`。
- 格式化：`@/utils/format`（`fmtTime, fmtDate, ago, remain, bytes, kfmt, cnNum, thousands, duration, shortId, copyText, colorOf`）。
- 路由已注册（`src/router/index.ts`），**按路由里写好的文件路径创建页面组件**，不要改路由文件和 MainLayout。
- 页面根元素用 `<div class="page">`；标题 `.page-title`（20px）、副标题 `.page-sub`；表格用 `.table-wrap > table.table`。
- 中文界面，所有交互都要真实调用后端，不要写死假数据。
