package com.cloudai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudai.common.JsonNodeTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Agent 评测任务、执行批次、评测结果、人工审核 */
public final class EvalEntities {
    private EvalEntities() {}

    @Data
    @TableName(value = "eval_task", autoResultMap = true)
    public static class EvalTask {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String spaceId;
        private String name;
        private String description;
        @TableField(typeHandler = JsonNodeTypeHandler.class)
        private JsonNode agentIds;
        private String judgeModelRef;
        /** 为空：直接评测原会话日志；非空：用该模型重放原会话的用户输入后再评测 */
        @TableField(updateStrategy = FieldStrategy.ALWAYS)
        private String replayModelRef;
        /** {timeMode:all/range/recent, from, to, recentHours, sessionIds:[], keyword, sources:[], statuses:[]} */
        @TableField(typeHandler = JsonNodeTypeHandler.class)
        private JsonNode filters;
        private String criteria;
        private Integer passScore;
        private Boolean skipEvaluated;
        /** manual/schedule/realtime */
        private String triggerMode;
        private String cron;
        private String timezone;
        @TableField(updateStrategy = FieldStrategy.ALWAYS)
        private LocalDateTime nextFireAt;
        @TableField(updateStrategy = FieldStrategy.ALWAYS)
        private LocalDateTime realtimeSince;
        private Boolean enabled;
        private String lastStatus;
        private LocalDateTime lastRunAt;
        private Long creatorId;
        private LocalDateTime createdAt;
        @TableField(fill = FieldFill.INSERT_UPDATE)
        private LocalDateTime updatedAt;
    }

    @Data
    @TableName("eval_run")
    public static class EvalRun {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long taskId;
        private String spaceId;
        /** manual/schedule/realtime */
        private String triggerType;
        /** running/success/partial/failed/stopped */
        private String status;
        private Integer total;
        private Integer done;
        private Integer failed;
        private BigDecimal avgScore;
        private Integer passCount;
        private String error;
        private Long creatorId;
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;
    }

    @Data
    @TableName(value = "eval_result", autoResultMap = true)
    public static class EvalResult {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String spaceId;
        private Long taskId;
        private Long runId;
        private String sessionId;
        private String replaySessionId;
        private String agentId;
        private Integer agentVersion;
        private String sessionTitle;
        private String sessionModelRef;
        private String judgeModelRef;
        private LocalDateTime sessionLastEventAt;
        /** pending/running/success/failed */
        private String status;
        private BigDecimal score;
        private Boolean passed;
        @TableField(typeHandler = JsonNodeTypeHandler.class)
        private JsonNode dimensions;
        private String summary;
        @TableField(typeHandler = JsonNodeTypeHandler.class)
        private JsonNode issues;
        @TableField(typeHandler = JsonNodeTypeHandler.class)
        private JsonNode suggestions;
        private String improvedReply;
        private Integer judgeInputTokens;
        private Integer judgeOutputTokens;
        private Long durationMs;
        private String error;
        private LocalDateTime createdAt;
        private LocalDateTime finishedAt;
    }

    @Data
    @TableName(value = "eval_annotation", autoResultMap = true)
    public static class EvalAnnotation {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String spaceId;
        /** eval=基于模型评测结果 / session=直接基于会话 */
        private String mode;
        private Long evalResultId;
        private String sessionId;
        private String agentId;
        @TableField(updateStrategy = FieldStrategy.ALWAYS)
        private Integer score;
        /** good/acceptable/bad */
        @TableField(updateStrategy = FieldStrategy.ALWAYS)
        private String label;
        @TableField(typeHandler = JsonNodeTypeHandler.class)
        private JsonNode tags;
        @TableField(updateStrategy = FieldStrategy.ALWAYS)
        private Boolean agreeWithModel;
        @TableField(updateStrategy = FieldStrategy.ALWAYS)
        private String comment;
        @TableField(updateStrategy = FieldStrategy.ALWAYS)
        private String correctedReply;
        private Long annotatorId;
        private LocalDateTime createdAt;
        @TableField(fill = FieldFill.INSERT_UPDATE)
        private LocalDateTime updatedAt;
    }
}
