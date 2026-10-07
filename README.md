# CloudAI — 企业级 Agent 托管平台

CloudAI 把“个人助理工具”升级为“可创建、可托管、可复用、可治理”的生产级 Agent 平台。

## 架构

```
┌────────────┐   HTTP/SSE   ┌──────────────────┐  HTTP/SSE   ┌──────────────────────┐
│ frontend   │ ───────────▶ │ backend (Java)   │ ──────────▶ │ executor (Python)     │
│ Vue3 + Vite│              │ Spring Boot 3    │             │ FastAPI               │
│ :5173      │ ◀─────────── │ :8580            │ ◀────────── │ :8590                 │
└────────────┘              └──────┬───────────┘             └──────┬───────────────┘
                                   │ JDBC                           │ pymilvus / BGE
                            ┌──────▼──────┐                  ┌──────▼──────────────┐
                            │ MySQL       │                  │ Milvus (独立实例)    │
                            │ 库: cloudai │                  │ :19540 (非 datademand)│
                            └─────────────┘                  └─────────────────────┘
```

| 模块 | 技术 | 职责 |
|---|---|---|
| `frontend/` | Vue 3 + Vite + TypeScript + Pinia + Vue Router | 全部页面 |
| `backend/` | Java 17 + Spring Boot 3 + MyBatis-Plus + MySQL | 用户/权限/空间、所有业务 CRUD、调度、调用记录入库 |
| `executor/` | Python 3 + FastAPI | Agent 执行循环（模型调用 + 工具调用）、MCP 客户端、embedding、Milvus 读写 |
| `data/` | 本地文件 | `.md`、Skill、上传文件、记忆文档、会话工作区按类型存放 |
| `deploy/` | docker-compose | CloudAI 专用的 Milvus 独立实例 |

## 数据存储

- **MySQL `cloudai` 库**：用户（带角色权限）、空间、成员、角色、Agent、Agent 版本、会话、执行步骤、Agent 执行数据、Skill、工具集成、定时任务、执行记录、消息通道、文件、记忆库与记忆文档、模型 Provider、凭据、Hook、运行环境、Sandbox、Agent Team 任务。
- **Milvus（独立实例，端口 19540）**：collection `cloudai_knowledge`。知识库/记忆库文档上传后由 BGE（`BAAI/bge-small-zh-v1.5`，512 维）切块 embedding 写入，同时在对应目录生成 `_index/keywords.md` 关键词索引文件。
- **文件目录** `data/`：
  - `data/skills/<space>/<skill>/<version>/` — Skill 包（含 SKILL.md）
  - `data/files/<space>/` — 文件中心上传文件
  - `data/memory/<space>/<store>/` — 记忆库文档（.md 等）及关键词索引
  - `data/agents/<space>/<agent>/` — Agent 定义导出的 .md
  - `data/workspaces/<session>/` — 会话沙箱工作目录（Agent 的 Bash/Read/Write 在此执行）

## 快速开始

前置：JDK 17+、Node 18+、[uv](https://docs.astral.sh/uv/)、Docker（colima 即可）。Maven 用本机 `mvn` 或 `~/tools/apache-maven-3.9.16`；MySQL 与 Milvus 都以 Docker 容器运行，无需本机安装。

```bash
./scripts/dev.sh all        # 启动 MySQL/Milvus 容器 → 执行器 → 后端 → 前端
./scripts/dev.sh status     # 查看各服务端口状态
./scripts/dev.sh stop       # 停止应用进程（容器保留）
./scripts/restart-backend.sh / restart-executor.sh   # 改代码后单独重启
```

Maven 依赖走 `backend/.mvn-settings.xml`（阿里云镜像，不影响 `~/.m2/settings.xml`）；BGE 向量模型首次启动时从 ModelScope 下载到 `data/models/`。

打开 http://localhost:5573 ，注册第一个账号（自动成为系统管理员并获得个人空间），然后：

1. 空间配置 → 模型管理：添加 Provider（OpenAI 兼容 / Responses / Anthropic）与模型
2. 智能体 → 创建 Agent：配置提示词、模型、工具、MCP、Skills、记忆库、SubAgents、环境变量
3. 会话：选择 Agent 开始对话；对话 / 产物 / 轨迹 / 信息 四个视图
4. 定时任务、消息通道（飞书长连接 / Webhook）、Agent 小队 + 任务看板 均调用同一套执行链路

| 服务 | 端口 | 日志 |
|---|---|---|
| 前端 Vite | 5573 | `.logs/frontend.log` |
| 后端 Spring Boot | 8580 | `.logs/backend.log` |
| Python 执行器 | 8590 | `.logs/executor.log` |
| Milvus（CloudAI 独立实例） | 19540 / 9592 | `docker logs cloudai-milvus-standalone` |
| MySQL 8（库 `cloudai`，root 无密码） | 3306 | `docker logs cloudai-mysql` |

`scripts/demo_mcp_server.py` 是测试用的本地 MCP Server（:8599，含两个 MCP 工具和一个 OpenAPI 接口），可用来体验「工具集成」。

## 功能清单

- **Agents**：会话、工作区、智能体（版本化编排配置）、Agent 小队、任务看板、定时任务、消息通道、文件中心、记忆库、向量库查询、用量
- **Plugins**：工具集成（MCP Server / OpenAPI / 云日志）、MCP 服务、凭据、Skills（文件夹/zip 上传、版本、共享）、Hooks
- **沙箱**：Sandbox 实例（超时/销毁）、运行环境
- **空间配置**：基础信息、成员管理（自定义角色权限）、模型管理

## 说明与限制

- 沙箱是本地实现：每个会话一个隔离工作目录 + 进程组，文件类工具限制在工作目录内；未做容器级隔离（Kubernetes Pod / Envoy 出站代理需接入真实集群）。
- 云日志查询目前实现了阿里云 SLS；火山云 TLS、华为云 LTS 可建插件但查询尚未实现。
- 「记忆 Provider」「API Keys」「迁移导入」不在本期范围，界面中显示为即将上线。
