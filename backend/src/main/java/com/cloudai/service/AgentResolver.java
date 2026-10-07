package com.cloudai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudai.common.BizException;
import com.cloudai.common.Json;
import com.cloudai.config.AppProperties;
import com.cloudai.entity.Agent;
import com.cloudai.entity.AgentVersion;
import com.cloudai.entity.OpsEntities.MemoryStore;
import com.cloudai.entity.PluginEntities.*;
import com.cloudai.mapper.Mappers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 把 Agent 定义解析为执行器可直接运行的 payload：
 * 解析模型、MCP/OpenAPI 插件、Skills 目录、记忆库、SubAgents（限同空间）、环境变量（解密凭据）、Hooks。
 */
@Service
@RequiredArgsConstructor
public class AgentResolver {
    private final AgentMapper agentMapper;
    private final AgentVersionMapper versionMapper;
    private final ToolPluginMapper pluginMapper;
    private final ToolItemMapper toolMapper;
    private final SkillMapper skillMapper;
    private final SkillVersionMapper skillVersionMapper;
    private final MemoryStoreMapper storeMapper;
    private final HookMapper hookMapper;
    private final ModelService modelService;
    private final CredentialService credentialService;
    private final Storage storage;
    private final AppProperties props;

    public static final List<String> DEFAULT_TOOLS = List.of("Bash", "Read", "Edit", "Write", "LS", "Glob", "Grep", "artifact_save");

    /** 读取指定版本配置；version 为 null 时用 agent 当前配置 */
    public JsonNode config(Agent a, Integer version) {
        if (version == null) return a.getConfig() == null ? Json.obj() : a.getConfig();
        AgentVersion v = versionMapper.selectOne(new LambdaQueryWrapper<AgentVersion>()
                .eq(AgentVersion::getAgentId, a.getId()).eq(AgentVersion::getVersion, version));
        return v == null ? a.getConfig() : v.getConfig();
    }

    /**
     * @param modelOverride 会话里临时选择的模型，可为 null
     */
    public ObjectNode resolve(Agent agent, JsonNode cfg, String modelOverride, int depth) {
        String spaceId = agent.getSpaceId();
        ObjectNode p = Json.obj();
        p.set("agent", Json.obj().put("id", agent.getId()).put("name", agent.getName())
                .put("identifier", agent.getIdentifier()).put("description", Objects.toString(agent.getDescription(), "")));
        p.put("system_prompt", cfg.path("prompt").asText(""));

        // 模型：会话覆盖 > 主模型 > 空间默认；备用模型做容灾
        JsonNode mc = cfg.path("model");
        String primary = modelOverride != null && !modelOverride.isBlank() ? modelOverride : mc.path("primary").asText(null);
        if (primary == null || primary.isBlank()) primary = agent.getModelRef();
        if (primary == null || primary.isBlank()) primary = modelService.defaultRef(spaceId);
        ArrayNode models = Json.arr();
        models.add(modelService.toExecutorSpec(modelService.resolve(spaceId, primary)));
        for (JsonNode fb : mc.path("fallbacks")) {
            try {
                models.add(modelService.toExecutorSpec(modelService.resolve(spaceId, fb.asText())));
            } catch (BizException ignored) {
                // 备用模型被删除时跳过
            }
        }
        p.set("models", models);
        JsonNode retry = mc.path("retry");
        p.set("retry", Json.obj()
                .put("enabled", retry.path("enabled").asBoolean(true))
                .put("times", retry.path("times").asInt(2))
                .put("base_delay", retry.path("baseDelay").asDouble(3))
                .put("max_delay", retry.path("maxDelay").asDouble(30)));
        p.put("show_thinking", mc.path("showThinking").asBoolean(false));

        JsonNode rt = cfg.path("runtime");
        p.put("runtime_kind", rt.path("kind").asText("claudecode"));
        p.put("max_turns", rt.path("maxTurns").asInt(40));
        p.put("timeout_seconds", rt.path("timeoutSeconds").asInt(1800));
        if (rt.hasNonNull("temperature")) p.put("temperature", rt.path("temperature").asDouble());

        // 内置工具
        ArrayNode builtin = Json.arr();
        if (cfg.has("builtinTools") && cfg.get("builtinTools").isArray()) cfg.get("builtinTools").forEach(builtin::add);
        else DEFAULT_TOOLS.forEach(builtin::add);
        p.set("builtin_tools", builtin);

        // 插件：MCP / OpenAPI / 云日志
        ArrayNode plugins = Json.arr();
        for (JsonNode ref : cfg.path("mcpServers")) {
            ToolPlugin tp = pluginMapper.selectById(ref.path("pluginId").asLong());
            if (tp == null || !spaceId.equals(tp.getSpaceId()) || !Boolean.TRUE.equals(tp.getEnabled())) continue;
            plugins.add(pluginSpec(tp));
        }
        p.set("plugins", plugins);

        // CLI 插件：凭据注入环境变量
        Map<String, String> env = new LinkedHashMap<>();
        ArrayNode clis = Json.arr();
        for (JsonNode cli : cfg.path("cliPlugins")) {
            String type = cli.path("type").asText();
            clis.add(Json.obj().put("type", type).put("label", cli.path("label").asText("")));
            if (cli.hasNonNull("credentialId")) {
                Map<String, String> vals = credentialService.values(spaceId, cli.get("credentialId").asLong());
                switch (type) {
                    case "lark" -> {
                        putIf(env, "LARK_APP_ID", first(vals, "app_id", "appId", "APP_ID"));
                        putIf(env, "LARK_APP_SECRET", first(vals, "app_secret", "appSecret", "APP_SECRET"));
                    }
                    case "gitlab" -> {
                        putIf(env, "GITLAB_TOKEN", first(vals, "token", "private_token", "GITLAB_TOKEN"));
                        putIf(env, "GITLAB_HOST", first(vals, "host", "url", "GITLAB_HOST"));
                    }
                    default -> vals.forEach((k, v) -> env.put(k.toUpperCase(), v));
                }
            }
        }
        p.set("cli_plugins", clis);

        // Skills：本空间或授权给本空间的 Skill
        ArrayNode skills = Json.arr();
        for (JsonNode ref : cfg.path("skills")) {
            Skill s = skillMapper.selectById(ref.path("skillId").asText());
            if (s == null || !"active".equals(s.getStatus())) continue;
            if (!spaceId.equals(s.getSpaceId()) && !contains(s.getSharedSpaces(), spaceId)) continue;
            String ver = ref.path("version").asText("");
            if (ver.isBlank() || "latest".equals(ver)) ver = s.getCurrentVersion();
            String finalVer = ver;
            SkillVersion sv = skillVersionMapper.selectOne(new LambdaQueryWrapper<SkillVersion>()
                    .eq(SkillVersion::getSkillId, s.getId()).eq(SkillVersion::getVersion, finalVer));
            if (sv == null) continue;
            skills.add(Json.obj().put("id", s.getId()).put("name", s.getName())
                    .put("description", Objects.toString(s.getDescription(), ""))
                    .put("version", ver).put("dir", storage.abs(sv.getDirPath()).toString()));
        }
        p.set("skills", skills);

        // 记忆库（知识库）
        ArrayNode stores = Json.arr();
        for (JsonNode ref : cfg.path("memoryStores")) {
            MemoryStore ms = storeMapper.selectById(ref.path("storeId").asText());
            if (ms == null || !spaceId.equals(ms.getSpaceId()) || !"active".equals(ms.getStatus())) continue;
            stores.add(Json.obj().put("id", ms.getId()).put("name", ms.getName())
                    .put("description", Objects.toString(ms.getDescription(), ""))
                    .put("access", ref.path("access").asText("rw"))
                    .put("instruction", ref.path("instruction").asText(""))
                    .put("dir", storage.memoryDir(spaceId, ms.getId()).toString()));
        }
        p.set("memory_stores", stores);
        p.put("memory_enabled", cfg.path("memoryEnabled").asBoolean(false));

        // SubAgents：只能调用同一空间内的 Agent（空间隔离），最多嵌套 2 层
        ArrayNode subs = Json.arr();
        if (depth < 2) {
            for (JsonNode ref : cfg.path("subAgents")) {
                Agent sa = agentMapper.selectById(ref.path("agentId").asText());
                if (sa == null || !spaceId.equals(sa.getSpaceId()) || sa.getId().equals(agent.getId())) continue;
                try {
                    ObjectNode sp = resolve(sa, config(sa, null), null, depth + 1);
                    subs.add(Json.obj().put("id", sa.getId()).put("name", sa.getName())
                            .put("identifier", sa.getIdentifier())
                            .put("description", Objects.toString(sa.getDescription(), ref.path("description").asText("")))
                            .set("payload", sp));
                } catch (BizException e) {
                    // SubAgent 未配置模型等，跳过
                }
            }
        }
        p.set("sub_agents", subs);

        // 环境变量
        for (JsonNode ev : cfg.path("envVars")) {
            if ("plain".equals(ev.path("type").asText())) {
                if (!ev.path("name").asText().isBlank()) env.put(ev.path("name").asText(), ev.path("value").asText(""));
            } else if (ev.hasNonNull("credentialId")) {
                var cred = credentialService.get(spaceId, ev.get("credentialId").asLong());
                for (var f : credentialService.fields(cred)) {
                    if ("all".equals(ev.path("scope").asText())) env.put(f.envName(), f.value());
                    else if (f.name().equals(ev.path("field").asText())) env.put(ev.path("name").asText(f.envName()), f.value());
                }
            }
        }
        ObjectNode envNode = Json.obj();
        env.forEach(envNode::put);
        p.set("env", envNode);

        // Hooks
        ArrayNode hooks = Json.arr();
        for (JsonNode hid : cfg.path("hooks")) {
            Hook h = hookMapper.selectById(hid.asLong());
            if (h != null && spaceId.equals(h.getSpaceId())) hooks.add(Json.obj().put("name", h.getName()).set("config", h.getConfig()));
        }
        p.set("hooks", hooks);

        p.set("metadata", cfg.path("metadata").isArray() ? cfg.get("metadata") : Json.arr());
        return p;
    }

    private ObjectNode pluginSpec(ToolPlugin tp) {
        ObjectNode n = Json.obj();
        n.put("id", tp.getId());
        n.put("type", tp.getType());
        n.put("name", tp.getName());
        n.put("key", Objects.toString(tp.getPluginKey(), tp.getName()));
        n.put("endpoint", tp.getEndpoint());
        n.put("transport", Objects.toString(tp.getTransport(), "streamable-http"));
        ObjectNode headers = Json.obj();
        if (tp.getHeaders() != null) for (JsonNode h : tp.getHeaders()) headers.put(h.path("name").asText(), h.path("value").asText());
        JsonNode conf = tp.getConfig() == null ? Json.obj() : tp.getConfig();
        if ("bearer".equals(tp.getAuthMode()) && conf.hasNonNull("bearerToken")) {
            headers.put("Authorization", "Bearer " + conf.get("bearerToken").asText());
        }
        if (conf.hasNonNull("credentialId")) {
            Map<String, String> vals = credentialService.values(tp.getSpaceId(), conf.get("credentialId").asLong());
            ObjectNode cv = Json.obj();
            vals.forEach(cv::put);
            n.set("credential", cv);
        }
        n.set("headers", headers);
        n.set("config", conf);
        ArrayNode tools = Json.arr();
        for (ToolItem t : toolMapper.selectList(new LambdaQueryWrapper<ToolItem>().eq(ToolItem::getPluginId, tp.getId()))) {
            if (!Boolean.TRUE.equals(t.getEnabled())) continue;
            ObjectNode tn = Json.obj().put("name", t.getName()).put("description", Objects.toString(t.getDescription(), ""))
                    .put("risk_level", t.getRiskLevel());
            if (t.getInputSchema() != null) tn.set("input_schema", t.getInputSchema());
            if (t.getHttpConfig() != null) tn.set("http_config", t.getHttpConfig());
            tools.add(tn);
        }
        n.set("tools", tools);
        return n;
    }

    public ObjectNode internalInfo(String spaceId) {
        return Json.obj().put("backend_url", props.getBackendUrl()).put("token", props.getInternalToken()).put("space_id", spaceId);
    }

    private static boolean contains(JsonNode arr, String v) {
        if (arr == null) return false;
        for (JsonNode n : arr) if (v.equals(n.asText())) return true;
        return false;
    }

    private static String first(Map<String, String> m, String... keys) {
        for (String k : keys) if (m.containsKey(k)) return m.get(k);
        return null;
    }

    private static void putIf(Map<String, String> m, String k, String v) {
        if (v != null) m.put(k, v);
    }
}
