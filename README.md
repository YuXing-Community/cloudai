<p align="center"><img src="site/assets/cloudai-logo.jpg" alt="CloudAI" width="200"></p>

# CloudAI

CloudAI 是一个可自托管的 AI 应用平台，用于创建 AI 助手、连接模型与业务工具，并管理知识库、会话、定时任务和团队权限。支持基于会话记录进行模型评测与人工审核。

[项目主页](https://yuxing-community.github.io/cloudai/) · [飞书资料](https://my.feishu.cn/wiki/N9Y0wCJROixSLFkjHcEcAJDonyg?from=auth_notice&hash=b4491cbbcfc61bdf9169be4f196eed80) · [交流群](#资料与交流群) · [快速开始](#快速开始) · [Docker 部署](#docker-部署) · [反馈问题](https://github.com/YuXing-Community/cloudai/issues)

## 资料与交流群

- [飞书资料文档](https://my.feishu.cn/wiki/N9Y0wCJROixSLFkjHcEcAJDonyg?from=auth_notice&hash=b4491cbbcfc61bdf9169be4f196eed80)，访问密码 `349#637x`。
- 使用飞书扫描下方二维码，加入「Cloud AI 用户群」，交流部署、使用与开发问题。点击图片可查看原图。

<a href="site/assets/cloudai-user-group.png"><img src="site/assets/cloudai-user-group.png" alt="Cloud AI 用户群飞书入群二维码，点击查看原图" width="420"></a>

## 架构

```
┌────────────┐   HTTP/SSE   ┌──────────────────┐  HTTP/SSE   ┌──────────────────────┐
│ frontend   │ ───────────▶ │ backend (Java)   │ ──────────▶ │ executor (Python)     │
│ Vue3 + Vite│              │ Spring Boot 3    │             │ FastAPI               │
│ :5573      │ ◀─────────── │ :8580            │ ◀────────── │ :8590                 │
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

- **MySQL `cloudai` 库**：用户（带角色权限）、空间、成员、角色、Agent、Agent 版本、会话、执行步骤、Agent 执行数据、Skill、工具集成、定时任务、执行记录、消息通道、文件、记忆库与记忆文档、模型 Provider、凭据、Hook、运行环境、Sandbox、Agent Team 任务、评测任务 `eval_task` / 评测批次 `eval_run` / 评测结果 `eval_result` / 人工审核表 `eval_annotation`。
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

## Docker 部署

整套服务使用 `deploy/compose.yml`：Nginx 前端、Java 后端、Python 执行器、独立 MySQL，以及 Milvus / etcd / MinIO。仅前端端口对外开放，默认 `5573`；所有容器配置 `unless-stopped` 自动重启。

前置条件：服务器已安装 Docker 和 Docker Compose，并且能下载镜像及构建依赖。执行器使用 Linux AMD64 镜像和 CPU 版 PyTorch，适用于本次部署的 x86_64 服务器。

首次部署，在项目根目录生成服务器本地配置。已有 `deploy/.env` 应保留，尤其不要重新生成加密密钥或数据库密码。不要提交密码和密钥。

```bash
umask 077
{
  for key in CLOUDAI_DB_PASSWORD CLOUDAI_MYSQL_ROOT_PASSWORD CLOUDAI_SECRET CLOUDAI_INTERNAL_TOKEN; do
    printf '%s=%s\n' "$key" "$(openssl rand -hex 32)"
  done
  echo 'CLOUDAI_PUBLIC_URL=http://服务器地址:5573'
} > deploy/.env
```

`CLOUDAI_PUBLIC_URL` 应填写浏览器实际访问的地址，用于生成分享链接。可设置 `CLOUDAI_WEB_PORT` 修改前端端口。

```bash
docker compose --env-file deploy/.env -f deploy/compose.yml config --quiet
docker compose --env-file deploy/.env -f deploy/compose.yml up -d --build --wait --wait-timeout 300
docker compose --env-file deploy/.env -f deploy/compose.yml ps
docker compose --env-file deploy/.env -f deploy/compose.yml logs --tail 100 backend executor
```

后端和执行器共享 `data/`；MySQL、Milvus、etcd 和 MinIO 数据保存在 `deploy/volumes/`。首次部署可同步已有 `data/models/bge-small-zh-v1.5/`，其余业务数据需与数据库一起迁移，不能只复制文件目录。新数据库的第一个注册用户自动成为管理员，模型 Provider 需在界面配置。

若服务器只能通过宿主机本地 HTTP 代理下载构建依赖，在 `deploy/.env` 增加 `CLOUDAI_BUILD_NETWORK=host`，然后让 Docker 构建使用当前终端的代理变量：

```bash
docker compose --env-file deploy/.env -f deploy/compose.yml build --build-arg HTTP_PROXY --build-arg HTTPS_PROXY --build-arg NO_PROXY --build-arg http_proxy --build-arg https_proxy --build-arg no_proxy
docker compose --env-file deploy/.env -f deploy/compose.yml up -d --wait --wait-timeout 300
```

代理仅用于构建，不写入运行镜像。停止服务用 `docker compose --env-file deploy/.env -f deploy/compose.yml down`，保留上述持久化目录即可再次启动。

## 功能清单

- **Agents**：会话、工作区、智能体（版本化编排配置）、Agent 小队、任务看板、定时任务、消息通道、文件中心、记忆库、向量库查询、用量
- **评测**：Agent 评测（选择评测模型 / 时间 / Agent / 执行记录，按会话 ID 落表评分与回复修改意见；可直接评测原会话日志，也可用指定模型重放后评测；手动、定时、实时三种触发）、人工打标（基于模型评测结果复核 / 直接基于会话打标，形成人工审核表，可导出 CSV）
- **Plugins**：工具集成（MCP Server / OpenAPI / 云日志）、MCP 服务、凭据、Skills（文件夹/zip 上传、版本、共享）、Hooks
- **沙箱**：Sandbox 实例（超时/销毁）、运行环境
- **空间配置**：基础信息、成员管理（自定义角色权限）、模型管理

## 说明与限制

- 沙箱是本地实现：每个会话一个隔离工作目录 + 进程组，文件类工具限制在工作目录内；未做容器级隔离（Kubernetes Pod / Envoy 出站代理需接入真实集群）。
- 云日志查询目前实现了阿里云 SLS；火山云 TLS、华为云 LTS 可建插件但查询尚未实现。
- 「记忆 Provider」「API Keys」「迁移导入」不在本期范围，界面中显示为即将上线。


## 项目主页

`site/` 包含独立的静态介绍页，使用原生 HTML、CSS 和 JavaScript。修改主页并推送到 `main` 后，`.github/workflows/pages.yml` 会发布到 GitHub Pages。仓库的 Pages 发布来源需设为 GitHub Actions。

本地预览主页可执行 `python3 -m http.server 8080 --directory site`，然后打开 http://localhost:8080。

## 升级说明

- 升级前备份 MySQL、`data/` 和 `deploy/volumes/`，保留已有 `deploy/.env` 中的密码与密钥。
- 后端启动时通过 `CREATE TABLE IF NOT EXISTS` 补建 `eval_task`、`eval_run`、`eval_result`、`eval_annotation` 表。
- 新模型配置使用 `low`、`medium`、`high`、`xhigh`、`max` 等级。已有 `trial` 和 `standard` 值继续保留，可在模型管理中调整。
- 评测会调用配置的模型服务。启用重放会重新执行用户输入，并可能再次调用工具，使用前应确认工具写入操作的影响。
