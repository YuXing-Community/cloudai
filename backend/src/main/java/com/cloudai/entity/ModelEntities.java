package com.cloudai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudai.common.JsonNodeTypeHandler;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class ModelEntities {
    private ModelEntities() {}

    @Data
    @TableName("model_provider")
    public static class ModelProvider {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String spaceId;
        private String providerKey;
        private String name;
        private String baseUrl;
        @JsonIgnore
        private String apiKeyEnc;
        /** openai-completions/openai-responses/anthropic */
        private String apiType;
        private String tokenType;
        private Boolean tokenCompress;
        private String status;
        private Long createdBy;
        private LocalDateTime createdAt;
        @com.baomidou.mybatisplus.annotation.TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
        private LocalDateTime updatedAt;
    }

    @Data
    @TableName(value = "model_def", autoResultMap = true)
    public static class ModelDef {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long providerId;
        private String modelId;
        @com.baomidou.mybatisplus.annotation.TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
        private String displayName;
        /** trial/standard/high */
        private String level;
        private Integer contextWindow;
        private Integer maxTokens;
        @com.baomidou.mybatisplus.annotation.TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
        private BigDecimal inputPrice;
        @com.baomidou.mybatisplus.annotation.TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
        private BigDecimal outputPrice;
        @com.baomidou.mybatisplus.annotation.TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
        private BigDecimal cachePrice;
        private Boolean isDefault;
        @TableField(typeHandler = JsonNodeTypeHandler.class)
        private JsonNode extra;
        private LocalDateTime createdAt;
    }
}
