-- CloudAI MySQL schema (database: cloudai)
-- 所有业务表都带 space_id 做空间隔离；时间统一 DATETIME(3)，JSON 字段用 JSON 类型。

CREATE TABLE IF NOT EXISTS sys_user (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  username      VARCHAR(64)  NOT NULL UNIQUE COMMENT '登录用户名',
  email         VARCHAR(128) NOT NULL UNIQUE COMMENT '注册邮箱',
  display_name  VARCHAR(64)  NOT NULL COMMENT '显示名',
  password_hash VARCHAR(128) NOT NULL,
  avatar        VARCHAR(512) NULL,
  is_admin      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '平台系统管理员',
  status        VARCHAR(16)  NOT NULL DEFAULT 'active',
  last_login_at DATETIME(3)  NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) COMMENT '用户表';

CREATE TABLE IF NOT EXISTS space (
  id                 VARCHAR(36)  PRIMARY KEY,
  name               VARCHAR(64)  NOT NULL,
  description        VARCHAR(512) NULL,
  type               VARCHAR(16)  NOT NULL DEFAULT 'team' COMMENT 'team/personal',
  custom_env_enabled TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否允许自定义运行环境',
  owner_id           BIGINT       NOT NULL,
  created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) COMMENT '空间表';

CREATE TABLE IF NOT EXISTS space_role (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  space_id    VARCHAR(36)  NULL COMMENT 'NULL 表示系统内置角色',
  code        VARCHAR(64)  NOT NULL,
  name        VARCHAR(64)  NOT NULL,
  description VARCHAR(256) NULL,
  permissions JSON         NOT NULL COMMENT '权限点数组',
  is_system   TINYINT(1)   NOT NULL DEFAULT 0,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_space_code (space_id, code)
) COMMENT '空间角色（含自定义角色）';

CREATE TABLE IF NOT EXISTS space_member (
  id        BIGINT PRIMARY KEY AUTO_INCREMENT,
  space_id  VARCHAR(36) NOT NULL,
  user_id   BIGINT      NOT NULL,
  role_code VARCHAR(64) NOT NULL,
  joined_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_space_user (space_id, user_id),
  KEY idx_user (user_id)
) COMMENT '空间成员';

CREATE TABLE IF NOT EXISTS model_provider (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  space_id       VARCHAR(36)  NOT NULL,
  provider_key   VARCHAR(64)  NOT NULL,
  name           VARCHAR(128) NOT NULL,
  base_url       VARCHAR(512) NOT NULL,
  api_key_enc    TEXT         NULL COMMENT 'AES 加密',
  api_type       VARCHAR(32)  NOT NULL DEFAULT 'openai-completions' COMMENT 'openai-completions/openai-responses/anthropic',
  token_type     VARCHAR(16)  NOT NULL DEFAULT 'custom' COMMENT 'custom/personal',
  token_compress TINYINT(1)   NOT NULL DEFAULT 0,
  status         VARCHAR(16)  NOT NULL DEFAULT 'available',
  created_by     BIGINT       NULL,
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_space_key (space_id, provider_key)
) COMMENT '模型 Provider';

CREATE TABLE IF NOT EXISTS model_def (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  provider_id    BIGINT       NOT NULL,
  model_id       VARCHAR(128) NOT NULL,
  display_name   VARCHAR(128) NULL,
  level          VARCHAR(16)  NOT NULL DEFAULT 'medium' COMMENT '模型等级 low/medium/high/xhigh/max',
  context_window INT          NOT NULL DEFAULT 128000,
  max_tokens     INT          NOT NULL DEFAULT 8192,
  input_price    DECIMAL(12,6) NULL COMMENT '元/百万 token',
  output_price   DECIMAL(12,6) NULL,
  cache_price    DECIMAL(12,6) NULL,
  is_default     TINYINT(1)   NOT NULL DEFAULT 0,
  extra          JSON         NULL,
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_provider (provider_id)
) COMMENT '模型';

CREATE TABLE IF NOT EXISTS agent (
  id          VARCHAR(36)  PRIMARY KEY,
  space_id    VARCHAR(36)  NOT NULL,
  name        VARCHAR(128) NOT NULL,
  identifier  VARCHAR(64)  NOT NULL COMMENT '英文标识',
  description VARCHAR(1024) NULL,
  avatar      VARCHAR(512) NULL COMMENT 'preset:<n> 或图片 URL',
  group_name  VARCHAR(64)  NULL,
  owner_id    BIGINT       NOT NULL,
  status      VARCHAR(16)  NOT NULL DEFAULT 'available' COMMENT 'available/archived',
  version     INT          NOT NULL DEFAULT 1 COMMENT '当前已发布版本',
  model_ref   VARCHAR(256) NULL COMMENT 'providerKey/modelId',
  config      JSON         NOT NULL COMMENT '编排配置（提示词/模型/工具/MCP/CLI/Skills/记忆/SubAgents/环境变量/Metadata/Hooks）',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_space_identifier (space_id, identifier),
  KEY idx_space (space_id)
) COMMENT 'Agent 信息表';

CREATE TABLE IF NOT EXISTS agent_version (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  agent_id   VARCHAR(36) NOT NULL,
  version    INT         NOT NULL,
  config     JSON        NOT NULL,
  note       VARCHAR(256) NULL,
  created_by BIGINT      NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_agent_version (agent_id, version)
) COMMENT 'Agent 版本';

CREATE TABLE IF NOT EXISTS agent_favorite (
  user_id  BIGINT      NOT NULL,
  agent_id VARCHAR(36) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, agent_id)
) COMMENT 'Agent 收藏';

CREATE TABLE IF NOT EXISTS agent_grant (
  id       BIGINT PRIMARY KEY AUTO_INCREMENT,
  agent_id VARCHAR(36) NOT NULL,
  user_id  BIGINT      NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_agent_user (agent_id, user_id)
) COMMENT 'Agent 分享用户授权';

CREATE TABLE IF NOT EXISTS chat_session (
  id            VARCHAR(36)  PRIMARY KEY,
  space_id      VARCHAR(36)  NOT NULL,
  agent_id      VARCHAR(36)  NOT NULL,
  agent_version INT          NULL,
  title         VARCHAR(256) NOT NULL,
  source        VARCHAR(16)  NOT NULL DEFAULT 'web' COMMENT 'web/debug/feishu/webhook/schedule/team/share',
  source_ref    VARCHAR(256) NULL COMMENT '飞书 chat_id / 定时任务 id 等',
  status        VARCHAR(16)  NOT NULL DEFAULT 'idle' COMMENT 'idle/running/completed/failed/stopped',
  model_ref     VARCHAR(256) NULL,
  runtime_kind  VARCHAR(32)  NULL COMMENT 'claudecode/cloudai',
  runtime_env   VARCHAR(64)  NULL,
  workspace_dir VARCHAR(512) NULL,
  creator_id    BIGINT       NULL,
  input_tokens  BIGINT       NOT NULL DEFAULT 0,
  output_tokens BIGINT       NOT NULL DEFAULT 0,
  cache_read    BIGINT       NOT NULL DEFAULT 0,
  cache_write   BIGINT       NOT NULL DEFAULT 0,
  context_tokens BIGINT      NOT NULL DEFAULT 0,
  last_event_at DATETIME(3)  NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_space_agent (space_id, agent_id),
  KEY idx_updated (updated_at)
) COMMENT '会话';

CREATE TABLE IF NOT EXISTS session_event (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id  VARCHAR(36)  NOT NULL,
  run_id      BIGINT       NULL,
  seq         INT          NOT NULL,
  type        VARCHAR(16)  NOT NULL COMMENT 'input/agent/tool/model/status/error/thinking',
  name        VARCHAR(128) NULL COMMENT '工具名/模型名',
  content     LONGTEXT     NULL COMMENT '文本内容或工具参数 JSON',
  result      LONGTEXT     NULL COMMENT '工具结果',
  status      VARCHAR(16)  NOT NULL DEFAULT 'ok' COMMENT 'ok/error/running',
  duration_ms BIGINT       NOT NULL DEFAULT 0,
  input_tokens  INT        NOT NULL DEFAULT 0,
  output_tokens INT        NOT NULL DEFAULT 0,
  started_at  DATETIME(3)  NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_session_seq (session_id, seq)
) COMMENT '执行步骤表（Session 事件流）';

CREATE TABLE IF NOT EXISTS agent_run (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  space_id      VARCHAR(36)  NOT NULL,
  agent_id      VARCHAR(36)  NOT NULL,
  session_id    VARCHAR(36)  NOT NULL,
  user_id       BIGINT       NULL,
  trigger_type  VARCHAR(16)  NOT NULL COMMENT 'web/debug/feishu/webhook/schedule/team',
  model_ref     VARCHAR(256) NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'running' COMMENT 'running/success/failed/stopped',
  input_tokens  BIGINT       NOT NULL DEFAULT 0,
  output_tokens BIGINT       NOT NULL DEFAULT 0,
  cache_read    BIGINT       NOT NULL DEFAULT 0,
  cache_write   BIGINT       NOT NULL DEFAULT 0,
  cost          DECIMAL(14,6) NULL COMMENT 'NULL 表示模型未配置价格',
  turns         INT          NOT NULL DEFAULT 0,
  tool_calls    INT          NOT NULL DEFAULT 0,
  skills_used   JSON         NULL,
  duration_ms   BIGINT       NOT NULL DEFAULT 0,
  error         TEXT         NULL,
  started_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at   DATETIME(3)  NULL,
  KEY idx_space_time (space_id, started_at),
  KEY idx_session (session_id)
) COMMENT 'Agent 执行数据表';

CREATE TABLE IF NOT EXISTS skill (
  id              VARCHAR(36)  PRIMARY KEY,
  space_id        VARCHAR(36)  NOT NULL,
  name            VARCHAR(128) NOT NULL,
  description     TEXT         NULL,
  category        VARCHAR(64)  NULL,
  tags            JSON         NULL,
  author_id       BIGINT       NOT NULL,
  current_version VARCHAR(32)  NOT NULL,
  call_count      INT          NOT NULL DEFAULT 0,
  status          VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active/invalid',
  shared_spaces   JSON         NULL COMMENT '已授权的其他空间 id 列表',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_space_name (space_id, name)
) COMMENT 'Skill 信息';

CREATE TABLE IF NOT EXISTS skill_version (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  skill_id   VARCHAR(36)  NOT NULL,
  version    VARCHAR(32)  NOT NULL,
  dir_path   VARCHAR(512) NOT NULL COMMENT 'data/skills 下的目录',
  file_count INT          NOT NULL DEFAULT 0,
  total_size BIGINT       NOT NULL DEFAULT 0,
  created_by BIGINT       NULL,
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_skill_version (skill_id, version)
) COMMENT 'Skill 版本';

CREATE TABLE IF NOT EXISTS tool_plugin (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  space_id    VARCHAR(36)  NOT NULL,
  type        VARCHAR(16)  NOT NULL COMMENT 'mcp/openapi/cloudlog',
  plugin_key  VARCHAR(64)  NULL,
  name        VARCHAR(128) NOT NULL,
  description VARCHAR(1024) NULL,
  endpoint    VARCHAR(1024) NULL,
  transport   VARCHAR(32)  NULL COMMENT 'streamable-http/sse',
  auth_mode   VARCHAR(32)  NOT NULL DEFAULT 'none' COMMENT 'none/bearer/header/credential',
  headers     JSON         NULL,
  config      JSON         NULL COMMENT 'basePath/timeout/vendor/credentialId 等',
  enabled     TINYINT(1)   NOT NULL DEFAULT 1,
  created_by  BIGINT       NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_space (space_id)
) COMMENT '工具集成表';

CREATE TABLE IF NOT EXISTS tool_item (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  plugin_id    BIGINT       NOT NULL,
  name         VARCHAR(128) NOT NULL,
  description  TEXT         NULL,
  risk_level   VARCHAR(16)  NOT NULL DEFAULT 'low' COMMENT 'low/medium/high',
  scope        VARCHAR(16)  NOT NULL DEFAULT 'internal' COMMENT 'internal/external',
  input_schema JSON         NULL,
  http_config  JSON         NULL COMMENT 'OpenAPI/云日志工具：method/path/query 等',
  enabled      TINYINT(1)   NOT NULL DEFAULT 1,
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_plugin (plugin_id)
) COMMENT '插件下的工具';

CREATE TABLE IF NOT EXISTS credential (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  space_id    VARCHAR(36)  NOT NULL,
  name        VARCHAR(128) NOT NULL,
  type        VARCHAR(32)  NOT NULL DEFAULT 'generic' COMMENT 'generic/gitlab/feishu/cloudlog',
  fields_enc  TEXT         NOT NULL COMMENT 'AES 加密的 [{name, envName, value}]',
  field_names JSON         NOT NULL COMMENT '[{name, envName}]，不含值',
  owner_id    BIGINT       NOT NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_space (space_id)
) COMMENT '凭据';

CREATE TABLE IF NOT EXISTS hook (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  space_id    VARCHAR(36)  NOT NULL,
  name        VARCHAR(128) NOT NULL,
  description VARCHAR(512) NULL,
  runtime     VARCHAR(32)  NOT NULL DEFAULT 'claudecode',
  config      JSON         NOT NULL COMMENT '{allow:[],deny:[],hooks:{EventName:[{matcher,handlers:[]}]}}',
  version     INT          NOT NULL DEFAULT 1,
  created_by  BIGINT       NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) COMMENT 'Hooks';

CREATE TABLE IF NOT EXISTS schedule_task (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  space_id      VARCHAR(36)  NOT NULL,
  name          VARCHAR(128) NOT NULL,
  agent_id      VARCHAR(36)  NOT NULL,
  agent_version INT          NULL COMMENT 'NULL = latest',
  runtime_env   VARCHAR(64)  NULL,
  prompt        TEXT         NOT NULL,
  enabled       TINYINT(1)   NOT NULL DEFAULT 1,
  retry_times   INT          NOT NULL DEFAULT 0,
  receivers     JSON         NULL COMMENT '[{channelId, chatId, notifyResult, notifyFailure}]',
  cron_mode     VARCHAR(16)  NOT NULL DEFAULT 'cron' COMMENT 'hourly/daily/workday/weekly/monthly/cron',
  cron          VARCHAR(64)  NOT NULL COMMENT '5 段 cron',
  timezone      VARCHAR(64)  NOT NULL DEFAULT 'Asia/Shanghai',
  next_fire_at  DATETIME(3)  NULL,
  last_status   VARCHAR(16)  NULL,
  last_run_at   DATETIME(3)  NULL,
  creator_id    BIGINT       NOT NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_fire (enabled, next_fire_at)
) COMMENT '定时任务表';

CREATE TABLE IF NOT EXISTS schedule_run (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id    BIGINT       NOT NULL,
  planned_at DATETIME(3)  NOT NULL,
  status     VARCHAR(16)  NOT NULL COMMENT 'running/success/failed/skipped',
  session_id VARCHAR(36)  NULL,
  attempt    INT          NOT NULL DEFAULT 1,
  error      TEXT         NULL,
  manual     TINYINT(1)   NOT NULL DEFAULT 0,
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at DATETIME(3) NULL,
  KEY idx_task (task_id, planned_at)
) COMMENT '定时任务执行记录';

CREATE TABLE IF NOT EXISTS message_channel (
  id             VARCHAR(36)  PRIMARY KEY,
  space_id       VARCHAR(36)  NOT NULL,
  type           VARCHAR(16)  NOT NULL COMMENT 'feishu/webhook',
  name           VARCHAR(128) NOT NULL,
  app_id         VARCHAR(128) NULL,
  app_secret_enc TEXT         NULL,
  bot_name       VARCHAR(128) NULL,
  agent_id       VARCHAR(36)  NOT NULL,
  runtime_env    VARCHAR(64)  NULL,
  settings       JSON         NULL COMMENT '飞书设置开关',
  webhook_token  VARCHAR(64)  NULL,
  status         VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active/disabled/error',
  status_message VARCHAR(512) NULL,
  created_by     BIGINT       NULL,
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_space (space_id)
) COMMENT '消息通道表';

CREATE TABLE IF NOT EXISTS file_info (
  id          VARCHAR(36)  PRIMARY KEY,
  space_id    VARCHAR(36)  NOT NULL,
  name        VARCHAR(256) NOT NULL COMMENT '显示名称',
  file_name   VARCHAR(256) NOT NULL COMMENT '原始文件名',
  category    VARCHAR(16)  NOT NULL DEFAULT 'session' COMMENT 'session=会话文件 artifact=产物文件',
  source      VARCHAR(16)  NOT NULL DEFAULT 'manual' COMMENT 'manual/chat/agent',
  size        BIGINT       NOT NULL DEFAULT 0,
  mime_type   VARCHAR(128) NULL,
  dir_path    VARCHAR(512) NOT NULL COMMENT '所在目录（相对 data 根目录）',
  storage_path VARCHAR(512) NOT NULL COMMENT '完整相对路径',
  session_id  VARCHAR(36)  NULL,
  version     INT          NOT NULL DEFAULT 1,
  share_token VARCHAR(64)  NULL,
  metadata    JSON         NULL,
  uploader_id BIGINT       NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_space_cat (space_id, category),
  KEY idx_session (session_id)
) COMMENT '文件信息表（记录所在目录及元数据）';

CREATE TABLE IF NOT EXISTS memory_store (
  id          VARCHAR(36)  PRIMARY KEY,
  space_id    VARCHAR(36)  NOT NULL,
  name        VARCHAR(128) NOT NULL,
  description VARCHAR(1024) NULL,
  provider    VARCHAR(64)  NOT NULL DEFAULT 'openviking',
  namespace   VARCHAR(64)  NOT NULL DEFAULT 'default',
  mode        VARCHAR(16)  NOT NULL DEFAULT 'compat' COMMENT 'compat/native',
  isolation   VARCHAR(16)  NOT NULL DEFAULT 'shared' COMMENT 'shared/user',
  status      VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active/archived',
  created_by  BIGINT       NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_space (space_id)
) COMMENT '记忆库（知识库）';

CREATE TABLE IF NOT EXISTS memory_doc (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  store_id     VARCHAR(36)  NOT NULL,
  path         VARCHAR(512) NOT NULL COMMENT '库内路径，如 /team/style.md',
  size         BIGINT       NOT NULL DEFAULT 0,
  hidden       TINYINT(1)   NOT NULL DEFAULT 0,
  index_status VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending/indexed/failed/skipped',
  chunk_count  INT          NOT NULL DEFAULT 0,
  index_error  VARCHAR(1024) NULL,
  updated_by   VARCHAR(64)  NULL,
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_store_path (store_id, path)
) COMMENT '记忆信息表';

CREATE TABLE IF NOT EXISTS runtime_env (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  space_id      VARCHAR(36)  NULL COMMENT 'NULL = 系统环境',
  name          VARCHAR(64)  NOT NULL,
  description   VARCHAR(512) NULL,
  is_system     TINYINT(1)   NOT NULL DEFAULT 0,
  platform_only TINYINT(1)   NOT NULL DEFAULT 0,
  template      VARCHAR(64)  NULL,
  usage_type    VARCHAR(32)  NOT NULL DEFAULT 'agent' COMMENT 'agent/sandbox',
  resource_group VARCHAR(64) NOT NULL DEFAULT 'local',
  prewarm       INT          NOT NULL DEFAULT 0,
  network_policy VARCHAR(32) NOT NULL DEFAULT 'default',
  packages      JSON         NULL COMMENT '[{manager, name, version}]',
  metadata      JSON         NULL COMMENT '[{key, value, system}]',
  version       INT          NOT NULL DEFAULT 1,
  status        VARCHAR(16)  NOT NULL DEFAULT 'available',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) COMMENT '运行环境';

CREATE TABLE IF NOT EXISTS space_default_env (
  space_id VARCHAR(36) PRIMARY KEY,
  env_id   BIGINT      NOT NULL
) COMMENT '空间默认运行环境';

CREATE TABLE IF NOT EXISTS sandbox (
  id              VARCHAR(36)  PRIMARY KEY,
  space_id        VARCHAR(36)  NOT NULL,
  name            VARCHAR(128) NOT NULL,
  env_name        VARCHAR(64)  NOT NULL,
  pod_name        VARCHAR(256) NOT NULL,
  status          VARCHAR(16)  NOT NULL DEFAULT 'running' COMMENT 'running/stopped/destroyed',
  resource_group  VARCHAR(64)  NOT NULL DEFAULT 'local',
  owner           VARCHAR(64)  NULL,
  session_id      VARCHAR(36)  NULL,
  work_dir        VARCHAR(512) NOT NULL,
  timeout_seconds INT          NOT NULL DEFAULT 28800,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  destroy_at      DATETIME(3)  NULL,
  KEY idx_space_status (space_id, status)
) COMMENT '沙箱实例';

CREATE TABLE IF NOT EXISTS agent_team (
  id          VARCHAR(36)  PRIMARY KEY,
  space_id    VARCHAR(36)  NOT NULL,
  name        VARCHAR(128) NOT NULL,
  description VARCHAR(512) NULL,
  pattern     VARCHAR(32)  NOT NULL DEFAULT 'expert_pool' COMMENT 'expert_pool/pipeline',
  roles       JSON         NOT NULL COMMENT '[{role, agentId, label, description}]，第一个为 router',
  created_by  BIGINT       NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) COMMENT 'Agent 小队';

CREATE TABLE IF NOT EXISTS team_session (
  id          VARCHAR(36)  PRIMARY KEY,
  space_id    VARCHAR(36)  NOT NULL,
  team_id     VARCHAR(36)  NOT NULL,
  name        VARCHAR(128) NOT NULL,
  goal        TEXT         NULL,
  submitter   VARCHAR(64)  NULL,
  status      VARCHAR(16)  NOT NULL DEFAULT 'running' COMMENT 'running/waiting_input/completed/failed/terminated',
  archived    TINYINT(1)   NOT NULL DEFAULT 0,
  state       JSON         NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at DATETIME(3)  NULL
) COMMENT 'Agent Team 运行（Task Board）';

CREATE TABLE IF NOT EXISTS team_task (
  id              VARCHAR(36)  PRIMARY KEY,
  team_session_id VARCHAR(36)  NOT NULL,
  parent_id       VARCHAR(36)  NULL,
  title           VARCHAR(256) NOT NULL,
  description     TEXT         NULL,
  role            VARCHAR(64)  NULL,
  agent_id        VARCHAR(36)  NULL,
  status          VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending/running/waiting_input/waiting_approval/done/needs_revision/failed',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_ts (team_session_id)
) COMMENT 'Team 任务';

CREATE TABLE IF NOT EXISTS team_run (
  id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  team_session_id  VARCHAR(36) NOT NULL,
  task_id          VARCHAR(36) NOT NULL,
  role             VARCHAR(64) NOT NULL,
  agent_id         VARCHAR(36) NULL,
  input            LONGTEXT    NULL,
  output           LONGTEXT    NULL,
  status           VARCHAR(16) NOT NULL DEFAULT 'running',
  child_session_id VARCHAR(36) NULL,
  duration_ms      BIGINT      NOT NULL DEFAULT 0,
  created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_ts (team_session_id)
) COMMENT 'Team Agent Runs';

CREATE TABLE IF NOT EXISTS team_message (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  team_session_id VARCHAR(36) NOT NULL,
  kind            VARCHAR(16) NOT NULL COMMENT 'user_input/message/event',
  task_id         VARCHAR(36) NULL,
  run_id          BIGINT      NULL,
  content         TEXT        NULL,
  answer          TEXT        NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'open' COMMENT 'open/answered',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_ts (team_session_id)
) COMMENT 'Team 用户输入/消息/事件';

-- ------------------------------------------------------------------ Agent 评测 / 人工打标

CREATE TABLE IF NOT EXISTS eval_task (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  space_id        VARCHAR(36)  NOT NULL,
  name            VARCHAR(128) NOT NULL,
  description     VARCHAR(1024) NULL,
  agent_ids       JSON         NOT NULL COMMENT '被评测的 Agent id 列表',
  judge_model_ref VARCHAR(256) NOT NULL COMMENT '评测模型 providerKey/modelId',
  replay_model_ref VARCHAR(256) NULL COMMENT '重放执行模型；为空时直接评测原会话日志，非空时用该模型重新执行原会话的用户输入后再评测',
  filters         JSON         NULL COMMENT '{timeMode:all/range/recent, from, to, recentHours, sessionIds:[], keyword, sources:[], statuses:[]}',
  criteria        TEXT         NULL COMMENT '自定义评测标准，追加到评测提示词',
  pass_score      INT          NOT NULL DEFAULT 60 COMMENT '及格分（0-100）',
  skip_evaluated  TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '跳过本任务已评测过且之后无新消息的会话',
  trigger_mode    VARCHAR(16)  NOT NULL DEFAULT 'manual' COMMENT 'manual/schedule/realtime',
  cron            VARCHAR(64)  NULL COMMENT '5 段 cron（schedule 模式）',
  timezone        VARCHAR(64)  NOT NULL DEFAULT 'Asia/Shanghai',
  next_fire_at    DATETIME(3)  NULL,
  realtime_since  DATETIME(3)  NULL COMMENT '实时模式开启时间，只评测此后产生/更新的会话',
  enabled         TINYINT(1)   NOT NULL DEFAULT 1,
  last_status     VARCHAR(16)  NULL,
  last_run_at     DATETIME(3)  NULL,
  creator_id      BIGINT       NOT NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_space (space_id),
  KEY idx_fire (enabled, trigger_mode, next_fire_at)
) COMMENT 'Agent 评测任务';

CREATE TABLE IF NOT EXISTS eval_run (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id       BIGINT       NOT NULL,
  space_id      VARCHAR(36)  NOT NULL,
  trigger_type  VARCHAR(16)  NOT NULL COMMENT 'manual/schedule/realtime',
  status        VARCHAR(16)  NOT NULL DEFAULT 'running' COMMENT 'running/success/partial/failed/stopped',
  total         INT          NOT NULL DEFAULT 0,
  done          INT          NOT NULL DEFAULT 0,
  failed        INT          NOT NULL DEFAULT 0,
  avg_score     DECIMAL(6,2) NULL,
  pass_count    INT          NOT NULL DEFAULT 0,
  error         TEXT         NULL,
  creator_id    BIGINT       NULL,
  started_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at   DATETIME(3)  NULL,
  KEY idx_task (task_id, started_at)
) COMMENT 'Agent 评测执行批次';

CREATE TABLE IF NOT EXISTS eval_result (
  id                BIGINT PRIMARY KEY AUTO_INCREMENT,
  space_id          VARCHAR(36)  NOT NULL,
  task_id           BIGINT       NOT NULL,
  run_id            BIGINT       NOT NULL,
  session_id        VARCHAR(36)  NOT NULL COMMENT '被评测的原会话 id',
  replay_session_id VARCHAR(36)  NULL COMMENT '重放执行生成的会话 id',
  agent_id          VARCHAR(36)  NOT NULL,
  agent_version     INT          NULL,
  session_title     VARCHAR(256) NULL,
  session_model_ref VARCHAR(256) NULL COMMENT 'Agent 执行所用模型',
  judge_model_ref   VARCHAR(256) NOT NULL,
  session_last_event_at DATETIME(3) NULL COMMENT '评测时会话最后事件时间，用于判断会话之后是否有新消息',
  status            VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending/running/success/failed',
  score             DECIMAL(5,1) NULL COMMENT '综合得分 0-100',
  passed            TINYINT(1)   NULL,
  dimensions        JSON         NULL COMMENT '[{key,name,score,comment}]',
  summary           TEXT         NULL COMMENT '评测结论',
  issues            JSON         NULL COMMENT '问题列表 [{severity,step,description}]',
  suggestions       JSON         NULL COMMENT '模型回复内容修改意见 [string]',
  improved_reply    LONGTEXT     NULL COMMENT '建议的改写回复',
  judge_input_tokens  INT        NOT NULL DEFAULT 0,
  judge_output_tokens INT        NOT NULL DEFAULT 0,
  duration_ms       BIGINT       NOT NULL DEFAULT 0,
  error             TEXT         NULL,
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at       DATETIME(3)  NULL,
  KEY idx_task_session (task_id, session_id),
  KEY idx_run (run_id),
  KEY idx_space_session (space_id, session_id)
) COMMENT 'Agent 评测结果（粒度：单个会话）';

CREATE TABLE IF NOT EXISTS eval_annotation (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  space_id        VARCHAR(36)  NOT NULL,
  mode            VARCHAR(16)  NOT NULL COMMENT 'eval=基于模型评测结果打标 / session=直接基于会话打标',
  eval_result_id  BIGINT       NULL,
  session_id      VARCHAR(36)  NOT NULL,
  agent_id        VARCHAR(36)  NULL,
  score           INT          NULL COMMENT '人工评分 0-100',
  label           VARCHAR(16)  NULL COMMENT 'good/acceptable/bad',
  tags            JSON         NULL COMMENT '问题标签',
  agree_with_model TINYINT(1)  NULL COMMENT 'eval 模式：是否认可模型评测结论',
  comment         TEXT         NULL COMMENT '人工意见',
  corrected_reply LONGTEXT     NULL COMMENT '人工修正后的回复',
  annotator_id    BIGINT       NOT NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_space_session (space_id, session_id),
  KEY idx_result (eval_result_id)
) COMMENT '人工审核表（会话打标、打分、意见）';
