package com.cloudai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudai.common.JsonNodeTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName(value = "agent_version", autoResultMap = true)
public class AgentVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String agentId;
    private Integer version;
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode config;
    private String note;
    private Long createdBy;
    private LocalDateTime createdAt;
}
