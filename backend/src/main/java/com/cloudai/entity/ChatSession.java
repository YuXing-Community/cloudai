package com.cloudai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_session")
public class ChatSession {
    @TableId(type = IdType.INPUT)
    private String id;
    private String spaceId;
    private String agentId;
    private Integer agentVersion;
    private String title;
    /** web/debug/feishu/webhook/schedule/team/share */
    private String source;
    private String sourceRef;
    /** idle/running/completed/failed/stopped */
    private String status;
    private String modelRef;
    private String runtimeKind;
    private String runtimeEnv;
    private String workspaceDir;
    private Long creatorId;
    private Long inputTokens;
    private Long outputTokens;
    private Long cacheRead;
    private Long cacheWrite;
    private Long contextTokens;
    private LocalDateTime lastEventAt;
    private LocalDateTime createdAt;
    @com.baomidou.mybatisplus.annotation.TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
