package com.cloudai.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cloudai.auth.Perm;
import com.cloudai.auth.RequirePerm;
import com.cloudai.auth.UserContext;
import com.cloudai.common.BizException;
import com.cloudai.common.Crypto;
import com.cloudai.common.Json;
import com.cloudai.common.R;
import com.cloudai.entity.ModelEntities.ModelDef;
import com.cloudai.entity.ModelEntities.ModelProvider;
import com.cloudai.mapper.Mappers.ModelDefMapper;
import com.cloudai.mapper.Mappers.ModelProviderMapper;
import com.cloudai.service.ExecutorClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/** 模型管理：Provider 与模型（含模型等级、价格、上下文窗口） */
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelController {
    private final ModelProviderMapper providerMapper;
    private final ModelDefMapper defMapper;
    private final Crypto crypto;
    private final ExecutorClient executor;
    private final JdbcTemplate jdbc;

    private static final Set<String> API_TYPES = Set.of("openai-completions", "openai-responses", "anthropic");

    @GetMapping("/providers")
    public R<Map<String, Object>> providers() {
        String sid = UserContext.spaceId();
        List<ModelProvider> ps = providerMapper.selectList(new LambdaQueryWrapper<ModelProvider>()
                .eq(ModelProvider::getSpaceId, sid).orderByDesc(ModelProvider::getUpdatedAt));
        List<Map<String, Object>> rows = new ArrayList<>();
        int modelCount = 0;
        for (ModelProvider p : ps) {
            List<ModelDef> ms = models(p.getId());
            modelCount += ms.size();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", p.getId());
            r.put("providerKey", p.getProviderKey());
            r.put("name", p.getName());
            r.put("baseUrl", p.getBaseUrl());
            r.put("apiType", p.getApiType());
            r.put("tokenType", p.getTokenType());
            r.put("tokenCompress", p.getTokenCompress());
            r.put("status", p.getStatus());
            r.put("apiKeyMasked", Crypto.mask(safeDecrypt(p.getApiKeyEnc())));
            r.put("updatedAt", p.getUpdatedAt());
            r.put("models", ms);
            rows.add(r);
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("providers", ps.size());
        stats.put("models", modelCount);
        Long today = jdbc.queryForObject("SELECT COUNT(*) FROM agent_run WHERE space_id = ? AND started_at >= CURDATE()", Long.class, sid);
        Long turns = jdbc.queryForObject("SELECT COALESCE(SUM(turns),0) FROM agent_run WHERE space_id = ? AND started_at >= CURDATE()", Long.class, sid);
        Double avg = jdbc.queryForObject("""
                SELECT AVG(e.duration_ms) FROM session_event e JOIN chat_session s ON s.id = e.session_id
                WHERE s.space_id = ? AND e.type = 'model' AND e.created_at >= CURDATE()""", Double.class, sid);
        stats.put("todayCalls", turns == null || turns == 0 ? today : turns);
        stats.put("avgLatencyMs", avg == null ? null : Math.round(avg));
        return R.ok(Map.of("providers", rows, "stats", stats));
    }

    private List<ModelDef> models(Long providerId) {
        return defMapper.selectList(new LambdaQueryWrapper<ModelDef>().eq(ModelDef::getProviderId, providerId).orderByAsc(ModelDef::getId));
    }

    private String safeDecrypt(String enc) {
        try {
            return crypto.decrypt(enc);
        } catch (Exception e) {
            return "";
        }
    }

    @Data
    public static class ModelReq {
        private Long id;
        private String modelId;
        private String displayName;
        private String level;
        private Integer contextWindow;
        private Integer maxTokens;
        private BigDecimal inputPrice;
        private BigDecimal outputPrice;
        private BigDecimal cachePrice;
        private Boolean isDefault;
        private JsonNode extra;
    }

    @Data
    public static class ProviderReq {
        private String providerKey;
        private String name;
        private String baseUrl;
        private String apiKey;
        private String apiType;
        private String tokenType;
        private Boolean tokenCompress;
        private List<ModelReq> models;
    }

    @PostMapping("/providers")
    @RequirePerm(Perm.MODEL_MANAGE)
    @Transactional
    public R<Map<String, Object>> create(@RequestBody ProviderReq req) {
        validate(req, true);
        String sid = UserContext.spaceId();
        long dup = providerMapper.selectCount(new LambdaQueryWrapper<ModelProvider>().eq(ModelProvider::getSpaceId, sid)
                .eq(ModelProvider::getProviderKey, req.providerKey));
        BizException.check(dup == 0, "Provider Key 已存在：" + req.providerKey);
        ModelProvider p = new ModelProvider();
        p.setSpaceId(sid);
        p.setProviderKey(req.providerKey.trim());
        apply(p, req);
        p.setCreatedBy(UserContext.userId());
        providerMapper.insert(p);
        syncModels(p, req.models);
        return R.ok(Map.of("id", p.getId()));
    }

    @PutMapping("/providers/{id}")
    @RequirePerm(Perm.MODEL_MANAGE)
    @Transactional
    public R<Void> update(@PathVariable Long id, @RequestBody ProviderReq req) {
        ModelProvider p = load(id);
        validate(req, false);
        apply(p, req);
        providerMapper.updateById(p);
        if (req.models != null) syncModels(p, req.models);
        return R.ok();
    }

    @DeleteMapping("/providers/{id}")
    @RequirePerm(Perm.MODEL_MANAGE)
    @Transactional
    public R<Void> delete(@PathVariable Long id) {
        ModelProvider p = load(id);
        defMapper.delete(new LambdaQueryWrapper<ModelDef>().eq(ModelDef::getProviderId, p.getId()));
        providerMapper.deleteById(p.getId());
        return R.ok();
    }

    @PutMapping("/default")
    @RequirePerm(Perm.MODEL_MANAGE)
    @Transactional
    public R<Void> setDefault(@RequestBody Map<String, String> body) {
        String ref = body.getOrDefault("ref", "");
        BizException.check(ref.contains("/"), "模型引用格式错误");
        String sid = UserContext.spaceId();
        String pk = ref.substring(0, ref.indexOf('/')), mid = ref.substring(ref.indexOf('/') + 1);
        ModelProvider p = providerMapper.selectOne(new LambdaQueryWrapper<ModelProvider>().eq(ModelProvider::getSpaceId, sid).eq(ModelProvider::getProviderKey, pk));
        BizException.check(p != null, "Provider 不存在");
        clearDefault(sid);
        defMapper.update(null, new LambdaUpdateWrapper<ModelDef>().eq(ModelDef::getProviderId, p.getId()).eq(ModelDef::getModelId, mid).set(ModelDef::getIsDefault, true));
        return R.ok();
    }

    private void clearDefault(String sid) {
        jdbc.update("UPDATE model_def d JOIN model_provider p ON p.id = d.provider_id SET d.is_default = 0 WHERE p.space_id = ?", sid);
    }

    @Data
    public static class TestReq {
        private Long providerId;
        private String baseUrl;
        private String apiKey;
        private String apiType;
        private String modelId;
    }

    /** 测试连接：用一个最小请求调用模型 */
    @PostMapping("/test")
    @RequirePerm(Perm.MODEL_MANAGE)
    public R<Map<String, Object>> test(@RequestBody TestReq req) {
        String key = req.apiKey;
        String baseUrl = req.baseUrl, apiType = req.apiType, modelId = req.modelId;
        if (req.providerId != null) {
            ModelProvider p = load(req.providerId);
            if (key == null || key.isBlank()) {
                // 使用已保存的密钥时只能发往已保存的地址，防止把密钥发到任意 URL
                key = safeDecrypt(p.getApiKeyEnc());
                baseUrl = p.getBaseUrl();
            }
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = p.getBaseUrl();
            if (apiType == null || apiType.isBlank()) apiType = p.getApiType();
            if (modelId == null || modelId.isBlank()) {
                List<ModelDef> ms = models(p.getId());
                modelId = ms.isEmpty() ? null : ms.get(0).getModelId();
            }
        }
        BizException.check(baseUrl != null && !baseUrl.isBlank(), "请填写 Base URL");
        BizException.check(modelId != null && !modelId.isBlank(), "请至少添加一个模型再测试");
        ObjectNode spec = Json.obj().put("base_url", baseUrl).put("api_key", key == null ? "" : key)
                .put("api_type", apiType == null ? "openai-completions" : apiType).put("model", modelId).put("ref", modelId);
        ObjectNode call = Json.obj();
        call.set("model", spec);
        call.set("messages", Json.arr().add(Json.obj().put("role", "user").put("content", "ping，请只回复 pong")));
        call.put("max_tokens", 16);
        long t0 = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            JsonNode r = executor.call("POST", "/llm/complete", call, 60);
            out.put("ok", true);
            out.put("text", r.path("text").asText(""));
        } catch (BizException e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
        }
        out.put("latencyMs", System.currentTimeMillis() - t0);
        out.put("model", modelId);
        return R.ok(out);
    }

    /** 会话/编排页的模型下拉（任何成员可用） */
    @GetMapping("/options")
    public R<List<Map<String, Object>>> options() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ModelProvider p : providerMapper.selectList(new LambdaQueryWrapper<ModelProvider>()
                .eq(ModelProvider::getSpaceId, UserContext.spaceId()).orderByAsc(ModelProvider::getId))) {
            List<Map<String, Object>> ms = new ArrayList<>();
            for (ModelDef d : models(p.getId())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("modelId", d.getModelId());
                m.put("ref", p.getProviderKey() + "/" + d.getModelId());
                m.put("displayName", d.getDisplayName());
                m.put("level", d.getLevel());
                m.put("isDefault", Boolean.TRUE.equals(d.getIsDefault()));
                m.put("contextWindow", d.getContextWindow());
                ms.add(m);
            }
            if (ms.isEmpty()) continue;
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("providerKey", p.getProviderKey());
            g.put("providerName", p.getName());
            g.put("models", ms);
            out.add(g);
        }
        return R.ok(out);
    }

    // ------------------------------------------------------------------

    private void validate(ProviderReq req, boolean creating) {
        if (creating) {
            BizException.check(req.providerKey != null && req.providerKey.matches("^[a-zA-Z0-9_\\-]{1,64}$"),
                    "Provider Key 只能包含字母、数字、下划线和短横线");
        }
        BizException.check(req.name != null && !req.name.isBlank(), "请填写名称");
        BizException.check(req.baseUrl != null && req.baseUrl.matches("^https?://.+"), "Base URL 须以 http(s):// 开头");
        BizException.check(req.apiType == null || API_TYPES.contains(req.apiType), "不支持的 API 类型");
        if (req.models != null) {
            Set<String> ids = new HashSet<>();
            for (ModelReq m : req.models) {
                BizException.check(m.modelId != null && !m.modelId.isBlank(), "模型 ID 不能为空");
                BizException.check(ids.add(m.modelId.trim()), "模型 ID 重复：" + m.modelId);
            }
        }
    }

    private void apply(ModelProvider p, ProviderReq req) {
        p.setName(req.name.trim());
        p.setBaseUrl(req.baseUrl.trim().replaceAll("/+$", ""));
        if (req.apiKey != null && !req.apiKey.isBlank()) p.setApiKeyEnc(crypto.encrypt(req.apiKey.trim()));
        p.setApiType(req.apiType == null ? "openai-completions" : req.apiType);
        p.setTokenType(req.tokenType == null ? "custom" : req.tokenType);
        p.setTokenCompress(Boolean.TRUE.equals(req.tokenCompress));
        p.setStatus("available");
    }

    private void syncModels(ModelProvider p, List<ModelReq> reqs) {
        if (reqs == null) return;
        Map<Long, ModelDef> existing = new HashMap<>();
        for (ModelDef d : models(p.getId())) existing.put(d.getId(), d);
        boolean anyDefault = reqs.stream().anyMatch(m -> Boolean.TRUE.equals(m.isDefault));
        if (anyDefault) clearDefault(p.getSpaceId());
        Set<Long> keep = new HashSet<>();
        for (ModelReq m : reqs) {
            ModelDef d = m.id != null && existing.containsKey(m.id) ? existing.get(m.id) : new ModelDef();
            d.setProviderId(p.getId());
            d.setModelId(m.modelId.trim());
            d.setDisplayName(m.displayName);
            d.setLevel(m.level == null ? "medium" : m.level);
            d.setContextWindow(m.contextWindow == null ? 128000 : m.contextWindow);
            d.setMaxTokens(m.maxTokens == null ? 8192 : m.maxTokens);
            d.setInputPrice(m.inputPrice);
            d.setOutputPrice(m.outputPrice);
            d.setCachePrice(m.cachePrice);
            d.setIsDefault(Boolean.TRUE.equals(m.isDefault));
            d.setExtra(m.extra);
            if (d.getId() == null) defMapper.insert(d);
            else defMapper.updateById(d);
            keep.add(d.getId());
        }
        for (Long id : existing.keySet()) if (!keep.contains(id)) defMapper.deleteById(id);
    }

    private ModelProvider load(Long id) {
        ModelProvider p = providerMapper.selectById(id);
        if (p == null || !UserContext.spaceId().equals(p.getSpaceId())) throw BizException.notFound("Provider");
        return p;
    }
}
