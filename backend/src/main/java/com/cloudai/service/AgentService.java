package com.cloudai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudai.common.BizException;
import com.cloudai.common.Json;
import com.cloudai.entity.Agent;
import com.cloudai.entity.AgentVersion;
import com.cloudai.mapper.Mappers.AgentMapper;
import com.cloudai.mapper.Mappers.AgentVersionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Agent 定义的创建、保存（版本化）与 .md 落盘 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {
    private final AgentMapper agentMapper;
    private final AgentVersionMapper versionMapper;
    private final ModelService modelService;
    private final Storage storage;

    public static final String DEFAULT_PROMPT = """
            # 角色
            你是一个专业、可靠的 AI 助手。

            # 目标
            - 理解用户的问题并给出准确、可执行的答案
            - 需要时调用工具获取事实，不编造数据

            # 回复要求
            - 先给结论，再给依据
            - 使用 Markdown 组织内容
            """;

    public ObjectNode defaultConfig(String spaceId) {
        ObjectNode c = Json.obj();
        c.put("prompt", DEFAULT_PROMPT);
        ObjectNode model = Json.obj();
        String ref = modelService.defaultRef(spaceId);
        if (ref != null) model.put("primary", ref);
        else model.putNull("primary");
        model.set("fallbacks", Json.arr());
        model.set("retry", Json.obj().put("enabled", true).put("times", 3).put("baseDelay", 5).put("maxDelay", 60));
        model.put("showThinking", false);
        c.set("model", model);
        c.set("runtime", Json.obj().put("kind", "claudecode").put("env", "cloudai-cli-runtime").put("maxSessions", 10)
                .put("sandboxIsolation", false).put("workspace", "").put("maxTurns", 40).put("timeoutSeconds", 1800));
        c.set("hooks", Json.arr());
        ArrayNode tools = Json.arr();
        AgentResolver.DEFAULT_TOOLS.forEach(tools::add);
        c.set("builtinTools", tools);
        for (String k : new String[]{"mcpServers", "cliPlugins", "skills", "memoryStores", "subAgents", "envVars", "metadata"}) {
            c.set(k, Json.arr());
        }
        c.put("memoryEnabled", false);
        return c;
    }

    @Transactional
    public Agent create(String spaceId, long ownerId, String name, String identifier, String description, String avatar,
                        String group, JsonNode config) {
        BizException.check(identifier != null && identifier.matches("^[a-zA-Z][a-zA-Z0-9_\\-]{0,59}$"),
                "英文标识须以字母开头，只能包含字母、数字、下划线和短横线");
        long dup = agentMapper.selectCount(new LambdaQueryWrapper<Agent>().eq(Agent::getSpaceId, spaceId).eq(Agent::getIdentifier, identifier));
        BizException.check(dup == 0, "英文标识已存在：" + identifier);
        Agent a = new Agent();
        a.setId(Json.uuid());
        a.setSpaceId(spaceId);
        a.setName(name);
        a.setIdentifier(identifier);
        a.setDescription(description);
        a.setAvatar(avatar);
        a.setGroupName(group == null || group.isBlank() ? null : group.trim());
        a.setOwnerId(ownerId);
        a.setStatus("available");
        a.setVersion(1);
        a.setConfig(config == null ? defaultConfig(spaceId) : config);
        a.setModelRef(a.getConfig().path("model").path("primary").asText(null));
        agentMapper.insert(a);
        snapshot(a, ownerId, "创建");
        writeMarkdown(a);
        return a;
    }

    /** 保存配置：版本 +1，写快照与 .md */
    @Transactional
    public Agent save(Agent a, long userId, String note) {
        a.setVersion(a.getVersion() + 1);
        a.setModelRef(a.getConfig().path("model").path("primary").asText(null));
        a.setUpdatedAt(LocalDateTime.now());
        agentMapper.updateById(a);
        snapshot(a, userId, note);
        writeMarkdown(a);
        return a;
    }

    private void snapshot(Agent a, long userId, String note) {
        AgentVersion v = new AgentVersion();
        v.setAgentId(a.getId());
        v.setVersion(a.getVersion());
        v.setConfig(a.getConfig());
        v.setNote(note);
        v.setCreatedBy(userId);
        versionMapper.insert(v);
    }

    /** Agent 定义落盘为 AGENT.md：front matter 元数据 + 人设提示词 */
    public void writeMarkdown(Agent a) {
        try {
            Path dir = storage.agentDir(a.getSpaceId(), a.getId());
            Files.createDirectories(dir);
            JsonNode c = a.getConfig();
            StringBuilder sb = new StringBuilder("---\n");
            sb.append("id: ").append(a.getId()).append('\n');
            sb.append("name: ").append(yaml(a.getName())).append('\n');
            sb.append("identifier: ").append(a.getIdentifier()).append('\n');
            sb.append("description: ").append(yaml(Objects.toString(a.getDescription(), ""))).append('\n');
            sb.append("version: ").append(a.getVersion()).append('\n');
            sb.append("model: ").append(c.path("model").path("primary").asText("")).append('\n');
            sb.append("runtime: ").append(c.path("runtime").path("kind").asText("")).append(" / ")
                    .append(c.path("runtime").path("env").asText("")).append('\n');
            sb.append("tools: ").append(Json.write(c.path("builtinTools"))).append('\n');
            sb.append("skills: ").append(Json.write(names(c.path("skills")))).append('\n');
            sb.append("mcp: ").append(Json.write(names(c.path("mcpServers")))).append('\n');
            sb.append("subAgents: ").append(Json.write(names(c.path("subAgents")))).append('\n');
            sb.append("updatedAt: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
            sb.append("---\n\n").append(c.path("prompt").asText("")).append('\n');
            Files.writeString(dir.resolve("AGENT.md"), sb.toString());
            Files.writeString(dir.resolve("agent.json"), Json.M.writerWithDefaultPrettyPrinter().writeValueAsString(c));
        } catch (Exception e) {
            log.warn("write AGENT.md failed: {}", e.getMessage());
        }
    }

    private static ArrayNode names(JsonNode arr) {
        ArrayNode out = Json.arr();
        for (JsonNode n : arr) out.add(n.path("name").asText(n.path("agentId").asText(n.path("skillId").asText(""))));
        return out;
    }

    private static String yaml(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }
}
