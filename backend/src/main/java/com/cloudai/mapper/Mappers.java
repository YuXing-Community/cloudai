package com.cloudai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudai.entity.*;
import com.cloudai.entity.AgentLinks.AgentFavorite;
import com.cloudai.entity.AgentLinks.AgentGrant;
import com.cloudai.entity.ModelEntities.ModelDef;
import com.cloudai.entity.ModelEntities.ModelProvider;
import com.cloudai.entity.OpsEntities.*;
import com.cloudai.entity.PluginEntities.*;
import com.cloudai.entity.RuntimeEntities.*;
import org.apache.ibatis.annotations.Mapper;

/** 业务表 Mapper 集合（CRUD 走 MyBatis-Plus BaseMapper） */
public final class Mappers {
    private Mappers() {}

    @Mapper public interface AgentMapper extends BaseMapper<Agent> {}
    @Mapper public interface AgentVersionMapper extends BaseMapper<AgentVersion> {}
    @Mapper public interface AgentFavoriteMapper extends BaseMapper<AgentFavorite> {}
    @Mapper public interface AgentGrantMapper extends BaseMapper<AgentGrant> {}
    @Mapper public interface ChatSessionMapper extends BaseMapper<ChatSession> {}
    @Mapper public interface SessionEventMapper extends BaseMapper<SessionEvent> {}
    @Mapper public interface AgentRunMapper extends BaseMapper<AgentRun> {}
    @Mapper public interface ModelProviderMapper extends BaseMapper<ModelProvider> {}
    @Mapper public interface ModelDefMapper extends BaseMapper<ModelDef> {}

    @Mapper public interface SkillMapper extends BaseMapper<Skill> {}
    @Mapper public interface SkillVersionMapper extends BaseMapper<SkillVersion> {}
    @Mapper public interface ToolPluginMapper extends BaseMapper<ToolPlugin> {}
    @Mapper public interface ToolItemMapper extends BaseMapper<ToolItem> {}
    @Mapper public interface CredentialMapper extends BaseMapper<Credential> {}
    @Mapper public interface HookMapper extends BaseMapper<Hook> {}

    @Mapper public interface ScheduleTaskMapper extends BaseMapper<ScheduleTask> {}
    @Mapper public interface ScheduleRunMapper extends BaseMapper<ScheduleRun> {}
    @Mapper public interface MessageChannelMapper extends BaseMapper<MessageChannel> {}
    @Mapper public interface FileInfoMapper extends BaseMapper<FileInfo> {}
    @Mapper public interface MemoryStoreMapper extends BaseMapper<MemoryStore> {}
    @Mapper public interface MemoryDocMapper extends BaseMapper<MemoryDoc> {}

    @Mapper public interface RuntimeEnvMapper extends BaseMapper<RuntimeEnv> {}
    @Mapper public interface SandboxMapper extends BaseMapper<Sandbox> {}
    @Mapper public interface AgentTeamMapper extends BaseMapper<AgentTeam> {}
    @Mapper public interface TeamSessionMapper extends BaseMapper<TeamSession> {}
    @Mapper public interface TeamTaskMapper extends BaseMapper<TeamTask> {}
    @Mapper public interface TeamRunMapper extends BaseMapper<TeamRun> {}
    @Mapper public interface TeamMessageMapper extends BaseMapper<TeamMessage> {}
}
