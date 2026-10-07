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
@TableName(value = "space_role", autoResultMap = true)
public class SpaceRole {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String spaceId;
    private String code;
    private String name;
    private String description;
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode permissions;
    private Boolean isSystem;
    private LocalDateTime createdAt;
}
