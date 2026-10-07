package com.cloudai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudai.common.JsonNodeTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 定义。config 结构见 docs/agent-config.md：
 * prompt / model / runtime / hooks / builtinTools / mcpServers / cliPlugins / skills /
 * memoryStores / memoryEnabled / subAgents / envVars / metadata
 */
@Data
@TableName(value = "agent", autoResultMap = true)
public class Agent {
    @TableId(type = IdType.INPUT)
    private String id;
    private String spaceId;
    private String name;
    private String identifier;
    private String description;
    private String avatar;
    @com.baomidou.mybatisplus.annotation.TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String groupName;
    private Long ownerId;
    private String status;
    private Integer version;
    @com.baomidou.mybatisplus.annotation.TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String modelRef;
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode config;
    private LocalDateTime createdAt;
    @com.baomidou.mybatisplus.annotation.TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
