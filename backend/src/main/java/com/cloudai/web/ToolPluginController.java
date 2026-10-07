package com.cloudai.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudai.auth.Perm;
import com.cloudai.auth.RequirePerm;
import com.cloudai.auth.UserContext;
import com.cloudai.common.BizException;
import com.cloudai.common.Json;
import com.cloudai.common.R;
import com.cloudai.entity.PluginEntities.ToolItem;
import com.cloudai.entity.PluginEntities.ToolPlugin;
import com.cloudai.mapper.Mappers.ToolItemMapper;
import com.cloudai.mapper.Mappers.ToolPluginMapper;
import com.cloudai.service.CredentialService;
import com.cloudai.service.ExecutorClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 工具集成：MCP Server / OpenAPI / 云日志 三类插件及其工具 */
@RestController
@RequestMapping("/api/tool-plugins")
@RequiredArgsConstructor
public class ToolPluginController {
    private final ToolPluginMapper pluginMapper;
    private final ToolItemMapper toolMapper;
    private final ExecutorClient executor;
    private final CredentialService credentialService;

    private static final Map<String, String> CLOUDLOG_ENDPOINTS = Map.of(
            "aliyun_sls", "cn-hangzhou.log.aliyuncs.com",
            "volc_tls", "tls-cn-beijing.volces.com",
            "huawei_lts", "lts.cn-north-4.myhuaweicloud.com");

    // ------------------------------------------------------------------ 查询

    @GetMapping
    @RequirePerm(Perm.PLUGIN_VIEW)
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String q, @RequestParam(required = false) String type) {
        var w = new LambdaQueryWrapper<ToolPlugin>().eq(ToolPlugin::getSpaceId, UserContext.spaceId());
        if (type != null && !type.isBlank()) w.eq(ToolPlugin::getType, type);
        w.orderByDesc(ToolPlugin::getCreatedAt);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolPlugin p : pluginMapper.selectList(w)) {
            List<ToolItem> tools = tools(p.getId());
            if (q != null && !q.isBlank()) {
                String k = q.toLowerCase();
                boolean hit = p.getName().toLowerCase().contains(k) || Objects.toString(p.getEndpoint(), "").toLowerCase().contains(k)
                        || tools.stream().anyMatch(t -> t.getName().toLowerCase().contains(k));
                if (!hit) continue;
            }
            Map<String, Object> m = row(p);
            m.put("tools", tools.stream().map(t -> Map.of("id", t.getId(), "name", t.getName(), "enabled", Boolean.TRUE.equals(t.getEnabled()))).toList());
            out.add(m);
        }
        return R.ok(out);
    }

    @GetMapping("/options")
    public R<List<Map<String, Object>>> options(@RequestParam(required = false) String type) {
        var w = new LambdaQueryWrapper<ToolPlugin>().eq(ToolPlugin::getSpaceId, UserContext.spaceId()).eq(ToolPlugin::getEnabled, true);
        if (type != null && !type.isBlank()) w.eq(ToolPlugin::getType, type);
        return R.ok(pluginMapper.selectList(w.orderByDesc(ToolPlugin::getCreatedAt)).stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("type", p.getType());
            m.put("endpoint", p.getEndpoint());
            m.put("description", p.getDescription());
            m.put("toolCount", toolMapper.selectCount(new LambdaQueryWrapper<ToolItem>().eq(ToolItem::getPluginId, p.getId()).eq(ToolItem::getEnabled, true)));
            return m;
        }).toList());
    }

    @GetMapping("/{id}")
    @RequirePerm(Perm.PLUGIN_VIEW)
    public R<Map<String, Object>> get(@PathVariable Long id) {
        ToolPlugin p = load(id);
        Map<String, Object> m = row(p);
        m.put("tools", tools(id));
        return R.ok(m);
    }

    private Map<String, Object> row(ToolPlugin p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("type", p.getType());
        m.put("pluginKey", p.getPluginKey());
        m.put("name", p.getName());
        m.put("description", p.getDescription());
        m.put("endpoint", p.getEndpoint());
        m.put("transport", p.getTransport());
        m.put("authMode", p.getAuthMode());
        // Header 值可能含密钥，返回前脱敏；保存时回传 ****** 表示沿用原值
        var masked = Json.arr();
        if (p.getHeaders() != null) for (JsonNode x : p.getHeaders()) {
            String v = x.path("value").asText("");
            masked.add(Json.obj().put("name", x.path("name").asText()).put("value", v.isEmpty() ? "" : "******"));
        }
        m.put("headers", masked);
        ObjectNode conf = p.getConfig() == null ? Json.obj() : ((ObjectNode) p.getConfig()).deepCopy();
        if (conf.has("bearerToken")) conf.put("bearerToken", "******");
        m.put("config", conf);
        m.put("enabled", p.getEnabled());
        m.put("createdAt", p.getCreatedAt());
        m.put("updatedAt", p.getUpdatedAt());
        return m;
    }

    private List<ToolItem> tools(Long pluginId) {
        return toolMapper.selectList(new LambdaQueryWrapper<ToolItem>().eq(ToolItem::getPluginId, pluginId).orderByAsc(ToolItem::getId));
    }

    // ------------------------------------------------------------------ 创建 / 编辑

    @Data
    public static class PluginReq {
        private String type;
        private String pluginKey;
        private String name;
        private String description;
        private String endpoint;
        private String transport;
        private String authMode;
        private String bearerToken;
        private JsonNode headers;
        private JsonNode config;
        /** MCP：测试连接拿到的工具，保存时一并注册 */
        private JsonNode tools;
        private Boolean enabled;
    }

    @PostMapping("/discover")
    @RequirePerm(Perm.PLUGIN_EDIT)
    public R<JsonNode> discover(@RequestBody PluginReq req) {
        BizException.check(req.endpoint != null && req.endpoint.matches("^https?://.+"), "Endpoint URL 须以 http(s):// 开头");
        ObjectNode body = Json.obj().put("endpoint", req.endpoint).put("transport", req.transport == null ? "streamable-http" : req.transport);
        body.set("headers", headerMap(req.headers, req.authMode, req.bearerToken));
        return R.ok(executor.call("POST", "/mcp/discover", body, 90));
    }

    @PostMapping
    @RequirePerm(Perm.PLUGIN_EDIT)
    @Transactional
    public R<Map<String, Object>> create(@RequestBody PluginReq req) {
        BizException.check(Set.of("mcp", "openapi", "cloudlog").contains(req.type), "插件类型错误");
        BizException.check(req.name != null && !req.name.isBlank(), "请填写显示名称");
        ToolPlugin p = new ToolPlugin();
        p.setSpaceId(UserContext.spaceId());
        p.setType(req.type);
        p.setCreatedBy(UserContext.userId());
        p.setEnabled(true);
        apply(p, req);
        if ("openapi".equals(req.type)) {
            BizException.check(req.pluginKey != null && req.pluginKey.matches("^[a-zA-Z0-9_\\-]{1,64}$"), "插件 Key 仅保留字母、数字、下划线和短横线");
            long dup = pluginMapper.selectCount(new LambdaQueryWrapper<ToolPlugin>().eq(ToolPlugin::getSpaceId, p.getSpaceId()).eq(ToolPlugin::getPluginKey, req.pluginKey));
            BizException.check(dup == 0, "插件 Key 已存在");
            p.setPluginKey(req.pluginKey);
        } else {
            p.setPluginKey(req.name.replaceAll("[^a-zA-Z0-9_\\-]", "_"));
        }
        pluginMapper.insert(p);
        if ("mcp".equals(req.type) && req.tools != null) upsertTools(p, req.tools, true);
        return R.ok(Map.of("id", p.getId()));
    }

    @PutMapping("/{id}")
    @RequirePerm(Perm.PLUGIN_EDIT)
    public R<Map<String, Object>> update(@PathVariable Long id, @RequestBody PluginReq req) {
        ToolPlugin p = load(id);
        if (req.name != null && !req.name.isBlank()) apply(p, req);
        if (req.enabled != null) p.setEnabled(req.enabled);
        pluginMapper.updateById(p);
        return R.ok(row(p));
    }

    @DeleteMapping("/{id}")
    @RequirePerm(Perm.PLUGIN_EDIT)
    @Transactional
    public R<Void> delete(@PathVariable Long id) {
        ToolPlugin p = load(id);
        toolMapper.delete(new LambdaQueryWrapper<ToolItem>().eq(ToolItem::getPluginId, p.getId()));
        pluginMapper.deleteById(p.getId());
        return R.ok();
    }

    private void apply(ToolPlugin p, PluginReq req) {
        p.setName(req.name.trim());
        p.setDescription(req.description);
        ObjectNode conf = p.getConfig() == null ? Json.obj() : (ObjectNode) p.getConfig();
        if (req.config != null && req.config.isObject()) {
            req.config.fields().forEachRemaining(e -> {
                if (!"bearerToken".equals(e.getKey())) conf.set(e.getKey(), e.getValue());
            });
        }
        if ("cloudlog".equals(p.getType())) {
            String vendor = conf.path("vendor").asText("aliyun_sls");
            BizException.check(CLOUDLOG_ENDPOINTS.containsKey(vendor), "不支持的云厂商");
            BizException.check(conf.hasNonNull("credentialId"), "请选择 AK/SK 凭证");
            credentialService.get(p.getSpaceId(), conf.get("credentialId").asLong());
            String ep = conf.path("endpoint").asText("");
            p.setEndpoint(ep.isBlank() ? CLOUDLOG_ENDPOINTS.get(vendor) : ep);
            p.setAuthMode("credential");
        } else {
            BizException.check(req.endpoint != null && req.endpoint.matches("^https?://.+"), "Endpoint URL 须以 http(s):// 开头");
            p.setEndpoint(req.endpoint.trim());
            p.setTransport(req.transport == null ? "streamable-http" : req.transport);
            p.setAuthMode(req.authMode == null ? "none" : req.authMode);
            if (req.bearerToken != null && !req.bearerToken.isBlank() && !"******".equals(req.bearerToken)) conf.put("bearerToken", req.bearerToken);
            if (!"bearer".equals(p.getAuthMode())) conf.remove("bearerToken");
        }
        if (req.headers != null) {
            Map<String, String> old = new HashMap<>();
            if (p.getHeaders() != null) for (JsonNode x : p.getHeaders()) old.put(x.path("name").asText(), x.path("value").asText(""));
            var hs = Json.arr();
            for (JsonNode x : req.headers) {
                String n = x.path("name").asText("").trim(), v = x.path("value").asText("");
                if (n.isEmpty()) continue;
                if ("******".equals(v)) v = old.getOrDefault(n, "");
                hs.add(Json.obj().put("name", n).put("value", v));
            }
            p.setHeaders(hs);
        }
        p.setConfig(conf);
    }

    private ObjectNode headerMap(JsonNode headers, String authMode, String bearer) {
        ObjectNode h = Json.obj();
        if (headers != null) for (JsonNode x : headers) if (!x.path("name").asText().isBlank()) h.put(x.path("name").asText(), x.path("value").asText());
        if ("bearer".equals(authMode) && bearer != null && !bearer.isBlank()) h.put("Authorization", "Bearer " + bearer);
        return h;
    }

    // ------------------------------------------------------------------ 工具

    /** 重新发现 MCP 工具：新增加入、已有更新、消失删除 */
    @PostMapping("/{id}/rediscover")
    @RequirePerm(Perm.PLUGIN_EDIT)
    @Transactional
    public R<Map<String, Object>> rediscover(@PathVariable Long id) {
        ToolPlugin p = load(id);
        BizException.check("mcp".equals(p.getType()), "只有 MCP 插件支持发现工具");
        ObjectNode body = Json.obj().put("endpoint", p.getEndpoint()).put("transport", p.getTransport());
        body.set("headers", headerMap(p.getHeaders(), p.getAuthMode(), p.getConfig() == null ? null : p.getConfig().path("bearerToken").asText(null)));
        JsonNode res = executor.call("POST", "/mcp/discover", body, 90);
        int[] stat = upsertTools(p, res.path("tools"), true);
        return R.ok(Map.of("added", stat[0], "updated", stat[1], "removed", stat[2]));
    }

    private int[] upsertTools(ToolPlugin p, JsonNode tools, boolean removeMissing) {
        Map<String, ToolItem> existing = new HashMap<>();
        for (ToolItem t : tools(p.getId())) existing.put(t.getName(), t);
        int added = 0, updated = 0, removed = 0;
        Set<String> seen = new HashSet<>();
        for (JsonNode t : tools) {
            String name = t.path("name").asText();
            if (name.isBlank()) continue;
            seen.add(name);
            ToolItem it = existing.get(name);
            if (it == null) {
                it = new ToolItem();
                it.setPluginId(p.getId());
                it.setName(name);
                it.setRiskLevel("low");
                it.setScope("internal");
                it.setEnabled(true);
                added++;
            } else updated++;
            it.setDescription(t.path("description").asText(""));
            it.setInputSchema(t.has("input_schema") ? t.get("input_schema") : t.get("inputSchema"));
            if (it.getId() == null) toolMapper.insert(it);
            else toolMapper.updateById(it);
        }
        if (removeMissing) {
            for (ToolItem t : existing.values()) {
                if (!seen.contains(t.getName())) {
                    toolMapper.deleteById(t.getId());
                    removed++;
                }
            }
        }
        return new int[]{added, updated, removed};
    }

    @Data
    public static class ToolReq {
        private String name;
        private String description;
        private String riskLevel;
        private String scope;
        private Boolean enabled;
        private JsonNode inputSchema;
        private JsonNode httpConfig;
    }

    @PostMapping("/{id}/tools")
    @RequirePerm(Perm.PLUGIN_EDIT)
    public R<ToolItem> addTool(@PathVariable Long id, @RequestBody ToolReq req) {
        ToolPlugin p = load(id);
        BizException.check(!"mcp".equals(p.getType()), "MCP 插件的工具请通过「发现工具」同步");
        BizException.check(req.name != null && req.name.matches("^[a-zA-Z0-9_\\-]{1,64}$"), "工具名仅支持字母、数字、下划线和短横线");
        long dup = toolMapper.selectCount(new LambdaQueryWrapper<ToolItem>().eq(ToolItem::getPluginId, id).eq(ToolItem::getName, req.name));
        BizException.check(dup == 0, "工具名已存在");
        ToolItem t = new ToolItem();
        t.setPluginId(id);
        t.setName(req.name);
        t.setDescription(req.description);
        t.setRiskLevel(req.riskLevel == null ? "low" : req.riskLevel);
        t.setScope(req.scope == null ? "internal" : req.scope);
        t.setEnabled(true);
        t.setInputSchema(req.inputSchema == null ? Json.obj().put("type", "object").set("properties", Json.obj()) : req.inputSchema);
        t.setHttpConfig(req.httpConfig);
        toolMapper.insert(t);
        return R.ok(t);
    }

    @PutMapping("/{id}/tools/{toolId}")
    @RequirePerm(Perm.PLUGIN_EDIT)
    public R<ToolItem> updateTool(@PathVariable Long id, @PathVariable Long toolId, @RequestBody ToolReq req) {
        load(id);
        ToolItem t = loadTool(id, toolId);
        if (req.description != null) t.setDescription(req.description);
        if (req.riskLevel != null) {
            BizException.check(Set.of("low", "medium", "high").contains(req.riskLevel), "风险等级错误");
            t.setRiskLevel(req.riskLevel);
        }
        if (req.scope != null) t.setScope(req.scope);
        if (req.enabled != null) t.setEnabled(req.enabled);
        if (req.inputSchema != null) t.setInputSchema(req.inputSchema);
        if (req.httpConfig != null) t.setHttpConfig(req.httpConfig);
        toolMapper.updateById(t);
        return R.ok(t);
    }

    @DeleteMapping("/{id}/tools/{toolId}")
    @RequirePerm(Perm.PLUGIN_EDIT)
    public R<Void> deleteTool(@PathVariable Long id, @PathVariable Long toolId) {
        load(id);
        toolMapper.deleteById(loadTool(id, toolId).getId());
        return R.ok();
    }

    /** 导入 OpenAPI 3 规范，每个 operation 生成一个工具 */
    @PostMapping("/{id}/openapi-import")
    @RequirePerm(Perm.PLUGIN_EDIT)
    @Transactional
    public R<Map<String, Object>> importOpenApi(@PathVariable Long id, @RequestBody Map<String, String> body) {
        ToolPlugin p = load(id);
        BizException.check("openapi".equals(p.getType()), "只有 OpenAPI 插件支持导入");
        JsonNode spec = Json.read(body.getOrDefault("spec", ""));
        BizException.check(spec != null && spec.has("paths"), "不是有效的 OpenAPI JSON（缺少 paths）");
        int added = 0, skipped = 0;
        var it = spec.get("paths").fields();
        while (it.hasNext()) {
            var pe = it.next();
            String path = pe.getKey();
            var ops = pe.getValue().fields();
            while (ops.hasNext()) {
                var oe = ops.next();
                String method = oe.getKey().toUpperCase();
                if (!Set.of("GET", "POST", "PUT", "DELETE", "PATCH").contains(method)) continue;
                JsonNode op = oe.getValue();
                String name = op.path("operationId").asText("");
                if (name.isBlank()) name = method.toLowerCase() + "_" + path.replaceAll("[^a-zA-Z0-9]+", "_");
                name = name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
                if (name.length() > 64) name = name.substring(0, 64);
                String finalName = name;
                if (toolMapper.selectCount(new LambdaQueryWrapper<ToolItem>().eq(ToolItem::getPluginId, id).eq(ToolItem::getName, finalName)) > 0) {
                    skipped++;
                    continue;
                }
                ObjectNode schema = Json.obj().put("type", "object");
                ObjectNode props = Json.obj();
                ArrayNode required = Json.arr();
                ArrayNode query = Json.arr();
                List<JsonNode> params = new ArrayList<>();
                pe.getValue().path("parameters").forEach(params::add);
                op.path("parameters").forEach(params::add);
                for (JsonNode prm : params) {
                    prm = deref(spec, prm);
                    String pn = prm.path("name").asText();
                    if (pn.isBlank() || "header".equals(prm.path("in").asText()) || "cookie".equals(prm.path("in").asText())) continue;
                    ObjectNode ps = prm.has("schema") ? (ObjectNode) deref(spec, prm.get("schema")).deepCopy() : Json.obj().put("type", "string");
                    if (prm.has("description")) ps.put("description", prm.get("description").asText());
                    props.set(pn, ps);
                    if (prm.path("required").asBoolean(false)) required.add(pn);
                    if ("query".equals(prm.path("in").asText())) query.add(pn);
                }
                JsonNode rb = deref(spec, op.path("requestBody")).path("content").path("application/json").path("schema");
                if (!rb.isMissingNode()) {
                    JsonNode rbs = deref(spec, rb);
                    rbs.path("properties").fields().forEachRemaining(e -> props.set(e.getKey(), deref(spec, e.getValue())));
                    rbs.path("required").forEach(required::add);
                }
                schema.set("properties", props);
                if (!required.isEmpty()) schema.set("required", required);
                ToolItem t = new ToolItem();
                t.setPluginId(id);
                t.setName(name);
                t.setDescription(op.path("summary").asText(op.path("description").asText(method + " " + path)));
                t.setRiskLevel("GET".equals(method) ? "low" : "medium");
                t.setScope("internal");
                t.setEnabled(true);
                t.setInputSchema(schema);
                t.setHttpConfig(Json.obj().put("method", method).put("path", path).set("query", query));
                toolMapper.insert(t);
                added++;
            }
        }
        return R.ok(Map.of("added", added, "skipped", skipped));
    }

    private JsonNode deref(JsonNode spec, JsonNode n) {
        for (int i = 0; i < 8 && n != null && n.has("$ref"); i++) {
            String ref = n.get("$ref").asText();
            if (!ref.startsWith("#/")) break;
            JsonNode cur = spec;
            for (String part : ref.substring(2).split("/")) cur = cur.path(part.replace("~1", "/").replace("~0", "~"));
            n = cur;
        }
        return n == null ? Json.obj() : n;
    }

    /** 调试单个工具 */
    @PostMapping("/{id}/tools/{toolId}/invoke")
    @RequirePerm(Perm.PLUGIN_EDIT)
    public R<JsonNode> invoke(@PathVariable Long id, @PathVariable Long toolId, @RequestBody Map<String, Object> body) {
        ToolPlugin p = load(id);
        ToolItem t = loadTool(id, toolId);
        ObjectNode plugin = Json.obj().put("type", p.getType()).put("name", p.getName()).put("endpoint", p.getEndpoint())
                .put("transport", Objects.toString(p.getTransport(), "streamable-http"));
        JsonNode conf = p.getConfig() == null ? Json.obj() : p.getConfig();
        plugin.set("headers", headerMap(p.getHeaders(), p.getAuthMode(), conf.path("bearerToken").asText(null)));
        plugin.set("config", conf);
        if (conf.hasNonNull("credentialId")) {
            ObjectNode cv = Json.obj();
            credentialService.values(p.getSpaceId(), conf.get("credentialId").asLong()).forEach(cv::put);
            plugin.set("credential", cv);
        }
        ObjectNode tn = Json.obj().put("name", t.getName());
        if (t.getHttpConfig() != null) tn.set("http_config", t.getHttpConfig());
        plugin.set("tools", Json.arr().add(tn));
        ObjectNode call = Json.obj();
        call.set("plugin", plugin);
        call.put("tool", t.getName());
        call.set("args", Json.M.valueToTree(body.getOrDefault("args", Map.of())));
        return R.ok(executor.call("POST", "/tools/invoke", call, 120));
    }

    private ToolPlugin load(Long id) {
        ToolPlugin p = pluginMapper.selectById(id);
        if (p == null || !UserContext.spaceId().equals(p.getSpaceId())) throw BizException.notFound("插件");
        return p;
    }

    private ToolItem loadTool(Long pluginId, Long toolId) {
        ToolItem t = toolMapper.selectById(toolId);
        if (t == null || !pluginId.equals(t.getPluginId())) throw BizException.notFound("工具");
        return t;
    }
}
