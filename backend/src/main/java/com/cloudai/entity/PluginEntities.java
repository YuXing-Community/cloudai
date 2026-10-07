package com.cloudai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudai.common.JsonNodeTypeHandler;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;

/** Plugins：Skills、工具集成、凭据、Hooks */
public final class PluginEntities {
    private PluginEntities() {}

    @Data
    @TableName(value = "skill", autoResultMap = true)
    public static class Skill {
        @TableId(type = IdType.INPUT)
        private String id;
        private String spaceId;
        private String name;
        private String description;
        private String category;
        @TableField(typeHandler = JsonNodeTypeHandler.class)
        private JsonNode tags;
        private Long authorId;
        private String currentVersion;
        private Integer callCount;
        /** active/invalid */
        private String status;
        @TableField(typeHandler = JsonNodeTypeHandler.class)
        private JsonNode sharedSpaces;
        private LocalDateTime createdAt;
        @com.baomidou.mybatisplus.annotation.TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
        private LocalDateTime updatedAt;
    }

    @Data
    @TableName("skill_version")
    public static class SkillVersion {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String skillId;
        private String version;
        /** 相对 data 根目录，如 skills/<spaceId>/<name>/<version> */
        private String dirPath;
        private Integer fileCount;
        private Long totalSize;
        private Long createdBy;
        private LocalDateTime createdAt;
    }

    @Data
    @TableName(value = "tool_plugin", autoResultMap = true)
    public static class ToolPlugin {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String spaceId;
        /** mcp/openapi/cloudlog */
        private String type;
        private String pluginKey;
        private String name;
        private String description;
        private String endpoint;
        /** streamable-http/sse */
        private String transport;
        /** none/bearer/header/credential */
        private String authMode;
        /** [{name, value}] */
        @TableField(typeHandler = JsonNodeTypeHandler.class)
        private JsonNode headers;
        /** basePath/timeoutMs/vendor/credentialId/bearerToken 等 */
        @TableField(typeHandler = JsonNodeTypeHandler.class)
        private JsonNode config;
        private Boolean enabled;
        private Long createdBy;
        private LocalDateTime createdAt;
        @com.baomidou.mybatisplus.annotation.TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
        private LocalDateTime updatedAt;
    }

    @Data
    @TableName(value = "tool_item", autoResultMap = true)
    public static class ToolItem {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long pluginId;
        private String name;
        private String description;
        /** low/medium/high */
        private String riskLevel;
        /** internal/external */
        private String scope;
        @TableField(typeHandler = JsonNodeTypeHandler.class)
        private JsonNode inputSchema;
        /** OpenAPI/云日志工具：{method, path, query...} */
        @TableField(typeHandler = JsonNodeTypeHandler.class)
        private JsonNode httpConfig;
        private Boolean enabled;
        private LocalDateTime createdAt;
    }

    @Data
    @TableName(value = "credential", autoResultMap = true)
    public static class Credential {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String spaceId;
        private String name;
        /** generic/gitlab/feishu/cloudlog */
        private String type;
        @JsonIgnore
        private String fieldsEnc;
        /** [{name, envName}] 不含值 */
        @TableField(typeHandler = JsonNodeTypeHandler.class)
        private JsonNode fieldNames;
        private Long ownerId;
        private LocalDateTime createdAt;
        @com.baomidou.mybatisplus.annotation.TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
        private LocalDateTime updatedAt;
    }

    @Data
    @TableName(value = "hook", autoResultMap = true)
    public static class Hook {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String spaceId;
        private String name;
        private String description;
        private String runtime;
        /** {allow:[], deny:[], hooks:{PreToolUse:[{matcher, handlers:[{type, command, url, timeout, shell, args, async}]}]}} */
        @TableField(typeHandler = JsonNodeTypeHandler.class)
        private JsonNode config;
        private Integer version;
        private Long createdBy;
        private LocalDateTime createdAt;
        @com.baomidou.mybatisplus.annotation.TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
        private LocalDateTime updatedAt;
    }
}
