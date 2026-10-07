package com.cloudai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

public final class AgentLinks {
    private AgentLinks() {}

    @Data
    @TableName("agent_favorite")
    public static class AgentFavorite {
        /** 联合主键 (user_id, agent_id)，只按条件读写 */
        @TableId(type = IdType.INPUT)
        private Long userId;
        private String agentId;
        private LocalDateTime createdAt;
    }

    @Data
    @TableName("agent_grant")
    public static class AgentGrant {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String agentId;
        private Long userId;
        private LocalDateTime createdAt;
    }
}
