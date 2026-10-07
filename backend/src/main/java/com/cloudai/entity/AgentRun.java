package com.cloudai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudai.common.JsonNodeTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Agent 执行数据：每次触发 Agent 执行一行，用量统计的数据来源 */
@Data
@TableName(value = "agent_run", autoResultMap = true)
public class AgentRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String spaceId;
    private String agentId;
    private String sessionId;
    private Long userId;
    private String triggerType;
    private String modelRef;
    /** running/success/failed/stopped */
    private String status;
    private Long inputTokens;
    private Long outputTokens;
    private Long cacheRead;
    private Long cacheWrite;
    private BigDecimal cost;
    private Integer turns;
    private Integer toolCalls;
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode skillsUsed;
    private Long durationMs;
    private String error;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
