package com.cloudai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudai.common.JsonNodeTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;

/** 运行环境、沙箱、Agent 小队与任务看板 */
public final class RuntimeEntities {
    private RuntimeEntities() {}

    @Data
    @TableName(value = "runtime_env", autoResultMap = true)
    public static class RuntimeEnv {
        @TableId(type = IdType.AUTO)
        private Long id;
        /** null = 系统环境 */
        private String spaceId;
        private String name;
        private String description;
        private Boolean isSystem;
        private Boolean platformOnly;
        private String template;
        /** agent/sandbox */
        private String usageType;
        private String resourceGroup;
        private Integer prewarm;
        private String networkPolicy;
        /** [{manager, name, version}] */
        @TableField(typeHandler = JsonNodeTypeHandler.class)
        private JsonNode packages;
        /** [{key, value, system}] */
        @TableField(typeHandler = JsonNodeTypeHandler.class)
        private JsonNode metadata;
        private Integer version;
        private String status;
        private LocalDateTime createdAt;
        @com.baomidou.mybatisplus.annotation.TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
        private LocalDateTime updatedAt;
    }

    @Data
    @TableName("sandbox")
    public static class Sandbox {
        @TableId(type = IdType.INPUT)
        private String id;
        private String spaceId;
        private String name;
        private String envName;
        private String podName;
        /** running/stopped/destroyed */
        private String status;
        private String resourceGroup;
        private String owner;
        private String sessionId;
        private String workDir;
        private Integer timeoutSeconds;
        private LocalDateTime createdAt;
        private LocalDateTime destroyAt;
    }

    @Data
    @TableName(value = "agent_team", autoResultMap = true)
    public static class AgentTeam {
        @TableId(type = IdType.INPUT)
        private String id;
        private String spaceId;
        private String name;
        private String description;
        /** expert_pool/pipeline */
        private String pattern;
        /** [{role, label, agentId, description}]，第一个为 router */
        @TableField(typeHandler = JsonNodeTypeHandler.class)
        private JsonNode roles;
        private Long createdBy;
        private LocalDateTime createdAt;
        @com.baomidou.mybatisplus.annotation.TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
        private LocalDateTime updatedAt;
    }

    @Data
    @TableName(value = "team_session", autoResultMap = true)
    public static class TeamSession {
        @TableId(type = IdType.INPUT)
        private String id;
        private String spaceId;
        private String teamId;
        private String name;
        private String goal;
        private String submitter;
        /** running/waiting_input/completed/failed/terminated */
        private String status;
        private Boolean archived;
        @TableField(typeHandler = JsonNodeTypeHandler.class)
        private JsonNode state;
        private LocalDateTime createdAt;
        private LocalDateTime finishedAt;
    }

    @Data
    @TableName("team_task")
    public static class TeamTask {
        @TableId(type = IdType.INPUT)
        private String id;
        private String teamSessionId;
        private String parentId;
        private String title;
        private String description;
        private String role;
        private String agentId;
        /** pending/running/waiting_input/waiting_approval/done/needs_revision/failed */
        private String status;
        private LocalDateTime createdAt;
        @com.baomidou.mybatisplus.annotation.TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
        private LocalDateTime updatedAt;
    }

    @Data
    @TableName("team_run")
    public static class TeamRun {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String teamSessionId;
        private String taskId;
        private String role;
        private String agentId;
        private String input;
        private String output;
        private String status;
        private String childSessionId;
        private Long durationMs;
        private LocalDateTime createdAt;
    }

    @Data
    @TableName("team_message")
    public static class TeamMessage {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String teamSessionId;
        /** user_input/message/event */
        private String kind;
        private String taskId;
        private Long runId;
        private String content;
        private String answer;
        /** open/answered */
        private String status;
        private LocalDateTime createdAt;
    }
}
