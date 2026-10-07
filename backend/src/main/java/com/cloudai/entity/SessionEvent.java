package com.cloudai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 执行步骤（Session 事件流）。type: input/agent/tool/model/thinking/error/status */
@Data
@TableName("session_event")
public class SessionEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private Long runId;
    private Integer seq;
    private String type;
    private String name;
    private String content;
    private String result;
    /** ok/error/running */
    private String status;
    private Long durationMs;
    private Integer inputTokens;
    private Integer outputTokens;
    private LocalDateTime startedAt;
    private LocalDateTime createdAt;
}
