package com.cloudai.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudai.common.JsonNodeTypeHandler;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;

/** 定时任务、消息通道、文件、记忆库 */
public final class OpsEntities {
    private OpsEntities() {}

    @Data
    @TableName(value = "schedule_task", autoResultMap = true)
    public static class ScheduleTask {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String spaceId;
        private String name;
        private String agentId;
        /** null = latest */
        @com.baomidou.mybatisplus.annotation.TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
        private Integer agentVersion;
        private String runtimeEnv;
        private String prompt;
        private Boolean enabled;
        private Integer retryTimes;
        /** [{channelId, chatId, label, notifyResult, notifyFailure}] */
        @TableField(typeHandler = JsonNodeTypeHandler.class)
        private JsonNode receivers;
        /** hourly/daily/workday/weekly/monthly/cron */
        private String cronMode;
        /** 5 段 cron */
        private String cron;
        private String timezone;
        @TableField(updateStrategy = FieldStrategy.ALWAYS)
        private LocalDateTime nextFireAt;
        private String lastStatus;
        private LocalDateTime lastRunAt;
        private Long creatorId;
        private LocalDateTime createdAt;
        @com.baomidou.mybatisplus.annotation.TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
        private LocalDateTime updatedAt;
    }

    @Data
    @TableName("schedule_run")
    public static class ScheduleRun {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long taskId;
        private LocalDateTime plannedAt;
        /** running/success/failed/skipped */
        private String status;
        private String sessionId;
        private Integer attempt;
        @com.baomidou.mybatisplus.annotation.TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
        private String error;
        private Boolean manual;
        private LocalDateTime createdAt;
        private LocalDateTime finishedAt;
    }

    @Data
    @TableName(value = "message_channel", autoResultMap = true)
    public static class MessageChannel {
        @TableId(type = IdType.INPUT)
        private String id;
        private String spaceId;
        /** feishu/webhook */
        private String type;
        private String name;
        private String appId;
        @JsonIgnore
        private String appSecretEnc;
        private String botName;
        private String agentId;
        private String runtimeEnv;
        /** requireMention/filterAtAll/topicSession/showFooter/finalOnly/showTyping/replyInThread */
        @TableField(typeHandler = JsonNodeTypeHandler.class)
        private JsonNode settings;
        private String webhookToken;
        /** active/disabled/error */
        private String status;
        @com.baomidou.mybatisplus.annotation.TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
        private String statusMessage;
        private Long createdBy;
        private LocalDateTime createdAt;
        @com.baomidou.mybatisplus.annotation.TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
        private LocalDateTime updatedAt;
    }

    @Data
    @TableName(value = "file_info", autoResultMap = true)
    public static class FileInfo {
        @TableId(type = IdType.INPUT)
        private String id;
        private String spaceId;
        private String name;
        private String fileName;
        /** session=会话文件 artifact=产物文件 */
        private String category;
        /** manual/chat/agent */
        private String source;
        private Long size;
        private String mimeType;
        private String dirPath;
        private String storagePath;
        private String sessionId;
        private Integer version;
        private String shareToken;
        @TableField(typeHandler = JsonNodeTypeHandler.class)
        private JsonNode metadata;
        private Long uploaderId;
        private LocalDateTime createdAt;
    }

    @Data
    @TableName("memory_store")
    public static class MemoryStore {
        @TableId(type = IdType.INPUT)
        private String id;
        private String spaceId;
        private String name;
        private String description;
        private String provider;
        private String namespace;
        /** compat/native */
        private String mode;
        /** shared/user */
        private String isolation;
        /** active/archived */
        private String status;
        private Long createdBy;
        private LocalDateTime createdAt;
        @com.baomidou.mybatisplus.annotation.TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
        private LocalDateTime updatedAt;
    }

    @Data
    @TableName("memory_doc")
    public static class MemoryDoc {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String storeId;
        private String path;
        private Long size;
        private Boolean hidden;
        /** pending/indexed/failed/skipped */
        private String indexStatus;
        private Integer chunkCount;
        @com.baomidou.mybatisplus.annotation.TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
        private String indexError;
        private String updatedBy;
        private LocalDateTime createdAt;
        @com.baomidou.mybatisplus.annotation.TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
        private LocalDateTime updatedAt;
    }
}
