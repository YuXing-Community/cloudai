package com.cloudai.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.auth.Perm;
import com.cloudai.auth.RequirePerm;
import com.cloudai.auth.UserContext;
import com.cloudai.common.BizException;
import com.cloudai.common.Json;
import com.cloudai.common.PageResult;
import com.cloudai.common.R;
import com.cloudai.config.AppProperties;
import com.cloudai.entity.Agent;
import com.cloudai.entity.AgentLinks.AgentFavorite;
import com.cloudai.entity.AgentLinks.AgentGrant;
import com.cloudai.entity.AgentVersion;
import com.cloudai.entity.SpaceMember;
import com.cloudai.mapper.CoreMappers.SpaceMemberMapper;
import com.cloudai.mapper.Mappers.*;
import com.cloudai.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 智能体：列表/创建/编排配置保存（版本化）/复制/导入导出/负责人/收藏/归档/分享授权 */
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentController {
    private final AgentMapper agentMapper;
    private final AgentVersionMapper versionMapper;
    private final AgentFavoriteMapper favMapper;
    private final AgentGrantMapper grantMapper;
    private final SpaceMemberMapper memberMapper;
    private final AgentService agentService;
    private final SpaceService spaceService;
    private final ModelService modelService;
    private final ExecutorClient executor;
    private final AppProperties props;

    // ------------------------------------------------------------------ 查询

    @GetMapping
    @RequirePerm(Perm.AGENT_VIEW)
    public R<Map<String, Object>> list(@RequestParam(defaultValue = "mine") String tab,
                                       @RequestParam(required = false) String group,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(defaultValue = "false") boolean archived,
                                       @RequestParam(defaultValue = "updated") String sort,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "20") long size) {
        String sid = UserContext.spaceId();
        long uid = UserContext.userId();
        Set<String> favs = favorites(uid);
        var w = baseQuery(sid, archived, group, q);
        switch (tab) {
            case "favorite" -> {
                if (favs.isEmpty()) w.eq(Agent::getId, "__none__");
                else w.in(Agent::getId, favs);
            }
            case "mine" -> w.eq(Agent::getOwnerId, uid);
            default -> { }
        }
        if ("name".equals(sort)) w.orderByAsc(Agent::getName);
        else if ("created".equals(sort)) w.orderByDesc(Agent::getCreatedAt);
        else w.orderByDesc(Agent::getUpdatedAt);
        Page<Agent> p = agentMapper.selectPage(new Page<>(page, Math.min(size, 100)), w);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", PageResult.of(p, a -> row(a, favs)));
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("favorite", favs.isEmpty() ? 0 : agentMapper.selectCount(baseQuery(sid, archived, null, null).in(Agent::getId, favs)));
        counts.put("mine", agentMapper.selectCount(baseQuery(sid, archived, null, null).eq(Agent::getOwnerId, uid)));
        counts.put("all", agentMapper.selectCount(baseQuery(sid, archived, null, null)));
        out.put("counts", counts);
        return R.ok(out);
    }

    private LambdaQueryWrapper<Agent> baseQuery(String sid, boolean archived, String group, String q) {
        var w = new LambdaQueryWrapper<Agent>().eq(Agent::getSpaceId, sid);
        if (!archived) w.eq(Agent::getStatus, "available");
        if (group != null && !group.isBlank()) {
            if ("__none__".equals(group)) w.isNull(Agent::getGroupName);
            else w.eq(Agent::getGroupName, group);
        }
        if (q != null && !q.isBlank()) {
            w.and(x -> x.like(Agent::getName, q).or().like(Agent::getDescription, q).or().like(Agent::getIdentifier, q));
        }
        return w;
    }

    @GetMapping("/groups")
    @RequirePerm(Perm.AGENT_VIEW)
    public R<List<String>> groups() {
        return R.ok(agentMapper.selectList(new LambdaQueryWrapper<Agent>().eq(Agent::getSpaceId, UserContext.spaceId())
                        .isNotNull(Agent::getGroupName).select(Agent::getGroupName))
                .stream().map(Agent::getGroupName).filter(Objects::nonNull).distinct().sorted().toList());
    }

    @GetMapping("/options")
    public R<List<Map<String, Object>>> options() {
        return R.ok(agentMapper.selectList(new LambdaQueryWrapper<Agent>().eq(Agent::getSpaceId, UserContext.spaceId())
                        .eq(Agent::getStatus, "available").orderByDesc(Agent::getUpdatedAt))
                .stream().map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", a.getId());
                    m.put("name", a.getName());
                    m.put("identifier", a.getIdentifier());
                    m.put("avatar", a.getAvatar());
                    m.put("version", a.getVersion());
                    m.put("description", a.getDescription());
                    m.put("modelRef", a.getModelRef());
                    return m;
                }).toList());
    }

    @GetMapping("/{id}")
    @RequirePerm(Perm.AGENT_VIEW)
    public R<Map<String, Object>> get(@PathVariable String id) {
        Agent a = load(id);
        Map<String, Object> r = row(a, favorites(UserContext.userId()));
        r.put("config", a.getConfig());
        r.put("versions", versionMapper.selectList(new LambdaQueryWrapper<AgentVersion>().eq(AgentVersion::getAgentId, id)
                        .select(AgentVersion::getId, AgentVersion::getVersion, AgentVersion::getNote, AgentVersion::getCreatedBy, AgentVersion::getCreatedAt)
                        .orderByDesc(AgentVersion::getVersion))
                .stream().map(v -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("version", v.getVersion());
                    m.put("note", v.getNote());
                    m.put("createdAt", v.getCreatedAt());
                    m.put("createdBy", spaceService.userBrief(v.getCreatedBy()));
                    return m;
                }).toList());
        return R.ok(r);
    }

    @GetMapping("/{id}/versions/{v}")
    @RequirePerm(Perm.AGENT_VIEW)
    public R<AgentVersion> version(@PathVariable String id, @PathVariable int v) {
        load(id);
        AgentVersion av = versionMapper.selectOne(new LambdaQueryWrapper<AgentVersion>().eq(AgentVersion::getAgentId, id).eq(AgentVersion::getVersion, v));
        if (av == null) throw BizException.notFound("版本");
        return R.ok(av);
    }

    // ------------------------------------------------------------------ 创建 / 保存

    @Data
    public static class CreateReq {
        @NotBlank @Size(max = 60) private String name;
        @NotBlank @Size(max = 60) private String identifier;
        @Size(max = 1000) private String description;
        private String avatar;
        private String groupName;
        /** AI 创建时带上生成的提示词 */
        private String prompt;
    }

    @PostMapping
    @RequirePerm(Perm.AGENT_EDIT)
    public R<Map<String, Object>> create(@Valid @RequestBody CreateReq req) {
        String sid = UserContext.spaceId();
        ObjectNode cfg = agentService.defaultConfig(sid);
        if (req.prompt != null && !req.prompt.isBlank()) cfg.put("prompt", req.prompt);
        Agent a = agentService.create(sid, UserContext.userId(), req.name.trim(), req.identifier.trim(), req.description,
                req.avatar, req.groupName, cfg);
        return R.ok(Map.of("id", a.getId()));
    }

    /** AI 创建：根据一句话需求生成名称、标识、描述和人设提示词 */
    @PostMapping("/ai-draft")
    @RequirePerm(Perm.AGENT_EDIT)
    public R<JsonNode> aiDraft(@RequestBody Map<String, String> body) {
        String req = body.getOrDefault("requirement", "").trim();
        BizException.check(!req.isEmpty(), "请描述你想要的 Agent");
        String ref = modelService.defaultRef(UserContext.spaceId());
        BizException.check(ref != null, "空间还没有可用模型，请先在「空间配置 → 模型管理」添加");
        ObjectNode call = Json.obj();
        call.set("model", modelService.toExecutorSpec(modelService.resolve(UserContext.spaceId(), ref)));
        call.put("system", "你是企业 Agent 设计专家。根据用户需求设计一个 Agent，只输出 JSON，不要输出其它内容。JSON 字段："
                + "name（中文显示名，≤20 字）、identifier（英文标识，小写字母开头，可含数字和短横线，≤40 字符）、"
                + "description（一句话说明负责处理的问题范围）、prompt（Markdown 格式的完整人设与回复逻辑，包含 角色/目标/工作流程/边界/输出格式）。");
        call.set("messages", Json.arr().add(Json.obj().put("role", "user").put("content", req)));
        call.put("max_tokens", 3000);
        JsonNode res = executor.call("POST", "/llm/complete", call, 150);
        String text = res.path("text").asText("");
        int s = text.indexOf('{'), e = text.lastIndexOf('}');
        BizException.check(s >= 0 && e > s, "模型没有返回有效的 JSON，请重试");
        JsonNode draft;
        try {
            draft = Json.M.readTree(text.substring(s, e + 1));
        } catch (Exception ex) {
            throw new BizException("解析模型输出失败，请重试");
        }
        return R.ok(draft);
    }

    @Data
    public static class SaveReq {
        private String name;
        private String description;
        private String avatar;
        private String groupName;
        private JsonNode config;
        private String note;
    }

    @PutMapping("/{id}")
    @RequirePerm(Perm.AGENT_EDIT)
    public R<Map<String, Object>> save(@PathVariable String id, @RequestBody SaveReq req) {
        Agent a = load(id);
        if (req.name != null && !req.name.isBlank()) a.setName(req.name.trim());
        if (req.description != null) a.setDescription(req.description);
        if (req.avatar != null) a.setAvatar(req.avatar);
        if (req.groupName != null) a.setGroupName(req.groupName.isBlank() ? null : req.groupName.trim());
        if (req.config != null) {
            BizException.check(req.config.isObject(), "config 必须是对象");
            validateSubAgents(a, req.config);
            a.setConfig(req.config);
        }
        agentService.save(a, UserContext.userId(), req.note);
        return R.ok(Map.of("id", a.getId(), "version", a.getVersion()));
    }

    /** 空间隔离：SubAgent 只能是同空间的其他 Agent */
    private void validateSubAgents(Agent a, JsonNode cfg) {
        for (JsonNode s : cfg.path("subAgents")) {
            String sidRef = s.path("agentId").asText();
            BizException.check(!a.getId().equals(sidRef), "SubAgent 不能是自己");
            Agent sub = agentMapper.selectById(sidRef);
            BizException.check(sub != null && a.getSpaceId().equals(sub.getSpaceId()), "SubAgent 必须属于当前空间，不能跨空间调用");
        }
    }

    @PostMapping("/{id}/copy")
    @RequirePerm(Perm.AGENT_EDIT)
    public R<Map<String, Object>> copy(@PathVariable String id) {
        Agent a = load(id);
        String ident = uniqueIdentifier(a.getSpaceId(), a.getIdentifier() + "-copy");
        Agent c = agentService.create(a.getSpaceId(), UserContext.userId(), a.getName() + " 副本", ident, a.getDescription(),
                a.getAvatar(), a.getGroupName(), a.getConfig().deepCopy());
        return R.ok(Map.of("id", c.getId()));
    }

    // ------------------------------------------------------------------ 导入导出 Package

    @GetMapping("/{id}/export")
    @RequirePerm(Perm.AGENT_VIEW)
    public ResponseEntity<byte[]> export(@PathVariable String id) {
        Agent a = load(id);
        ObjectNode pkg = Json.obj();
        pkg.put("format", "cloudai.agent/v1");
        pkg.put("name", a.getName());
        pkg.put("identifier", a.getIdentifier());
        pkg.put("description", a.getDescription());
        pkg.put("avatar", a.getAvatar());
        pkg.put("groupName", a.getGroupName());
        pkg.put("version", a.getVersion());
        pkg.set("config", a.getConfig());
        byte[] bytes;
        try {
            bytes = Json.M.writerWithDefaultPrettyPrinter().writeValueAsBytes(pkg);
        } catch (Exception e) {
            throw new BizException("导出失败");
        }
        String fn = URLEncoder.encode(a.getIdentifier() + ".cloudai.json", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fn)
                .contentType(MediaType.APPLICATION_JSON).body(bytes);
    }

    @PostMapping("/import")
    @RequirePerm(Perm.AGENT_EDIT)
    @Transactional
    public R<Map<String, Object>> importPkg(@RequestParam("file") MultipartFile file) throws Exception {
        JsonNode pkg = Json.M.readTree(file.getBytes());
        BizException.check(pkg != null && pkg.has("config") && pkg.has("identifier"), "不是有效的 CloudAI Agent Package");
        String sid = UserContext.spaceId();
        ObjectNode cfg = (ObjectNode) pkg.get("config").deepCopy();
        // 跨空间导入：SubAgent 引用可能不属于当前空间，清理掉以保证空间隔离
        var subs = Json.arr();
        for (JsonNode s : cfg.path("subAgents")) {
            Agent sub = agentMapper.selectById(s.path("agentId").asText());
            if (sub != null && sid.equals(sub.getSpaceId())) subs.add(s);
        }
        cfg.set("subAgents", subs);
        Agent a = agentService.create(sid, UserContext.userId(), pkg.path("name").asText("导入的 Agent"),
                uniqueIdentifier(sid, pkg.path("identifier").asText("agent")), pkg.path("description").asText(null),
                pkg.path("avatar").asText(null), pkg.path("groupName").asText(null), cfg);
        return R.ok(Map.of("id", a.getId(), "name", a.getName()));
    }

    private String uniqueIdentifier(String sid, String base) {
        String b = base.replaceAll("[^a-zA-Z0-9_\\-]", "-");
        if (b.isEmpty() || !Character.isLetter(b.charAt(0))) b = "a-" + b;
        b = b.length() > 50 ? b.substring(0, 50) : b;
        String cand = b;
        for (int i = 2; agentMapper.selectCount(new LambdaQueryWrapper<Agent>().eq(Agent::getSpaceId, sid).eq(Agent::getIdentifier, cand)) > 0; i++) {
            cand = b + "-" + i;
        }
        return cand;
    }

    // ------------------------------------------------------------------ 负责人 / 收藏 / 归档 / 删除

    @PutMapping("/{id}/owner")
    @RequirePerm(Perm.AGENT_EDIT)
    public R<Void> owner(@PathVariable String id, @RequestBody Map<String, Long> body) {
        Agent a = load(id);
        Long uid = body.get("userId");
        boolean member = memberMapper.selectCount(new LambdaQueryWrapper<SpaceMember>()
                .eq(SpaceMember::getSpaceId, a.getSpaceId()).eq(SpaceMember::getUserId, uid)) > 0;
        BizException.check(member, "负责人必须是空间成员");
        a.setOwnerId(uid);
        agentMapper.updateById(a);
        return R.ok();
    }

    @PostMapping("/{id}/favorite")
    @RequirePerm(Perm.AGENT_VIEW)
    public R<Map<String, Boolean>> favorite(@PathVariable String id) {
        load(id);
        long uid = UserContext.userId();
        var w = new LambdaQueryWrapper<AgentFavorite>().eq(AgentFavorite::getUserId, uid).eq(AgentFavorite::getAgentId, id);
        if (favMapper.selectCount(w) > 0) {
            favMapper.delete(w);
            return R.ok(Map.of("favorite", false));
        }
        AgentFavorite f = new AgentFavorite();
        f.setUserId(uid);
        f.setAgentId(id);
        favMapper.insert(f);
        return R.ok(Map.of("favorite", true));
    }

    @PutMapping("/{id}/archive")
    @RequirePerm(Perm.AGENT_EDIT)
    public R<Void> archive(@PathVariable String id, @RequestBody Map<String, Boolean> body) {
        Agent a = load(id);
        a.setStatus(Boolean.TRUE.equals(body.get("archived")) ? "archived" : "available");
        agentMapper.updateById(a);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @RequirePerm(Perm.AGENT_EDIT)
    @Transactional
    public R<Void> delete(@PathVariable String id) {
        Agent a = load(id);
        versionMapper.delete(new LambdaQueryWrapper<AgentVersion>().eq(AgentVersion::getAgentId, id));
        favMapper.delete(new LambdaQueryWrapper<AgentFavorite>().eq(AgentFavorite::getAgentId, id));
        grantMapper.delete(new LambdaQueryWrapper<AgentGrant>().eq(AgentGrant::getAgentId, id));
        agentMapper.deleteById(a.getId());
        return R.ok();
    }

    // ------------------------------------------------------------------ 分享 / 用户授权

    @GetMapping("/{id}/share")
    @RequirePerm(Perm.AGENT_VIEW)
    public R<Map<String, Object>> share(@PathVariable String id) {
        Agent a = load(id);
        return R.ok(Map.of("url", props.getPublicUrl().replaceAll("/+$", "") + "/share/agent/" + a.getId()));
    }

    @GetMapping("/{id}/grants")
    @RequirePerm(Perm.AGENT_VIEW)
    public R<List<Map<String, Object>>> grants(@PathVariable String id) {
        load(id);
        return R.ok(grantMapper.selectList(new LambdaQueryWrapper<AgentGrant>().eq(AgentGrant::getAgentId, id))
                .stream().map(g -> {
                    Map<String, Object> m = new LinkedHashMap<>(spaceService.userBrief(g.getUserId()));
                    m.put("grantId", g.getId());
                    m.put("createdAt", g.getCreatedAt());
                    return m;
                }).toList());
    }

    @PostMapping("/{id}/grants")
    @RequirePerm(Perm.AGENT_EDIT)
    public R<Void> grant(@PathVariable String id, @RequestBody Map<String, List<Long>> body) {
        load(id);
        for (Long uid : body.getOrDefault("userIds", List.of())) {
            long n = grantMapper.selectCount(new LambdaQueryWrapper<AgentGrant>().eq(AgentGrant::getAgentId, id).eq(AgentGrant::getUserId, uid));
            if (n > 0) continue;
            AgentGrant g = new AgentGrant();
            g.setAgentId(id);
            g.setUserId(uid);
            grantMapper.insert(g);
        }
        return R.ok();
    }

    @DeleteMapping("/{id}/grants/{grantId}")
    @RequirePerm(Perm.AGENT_EDIT)
    public R<Void> revoke(@PathVariable String id, @PathVariable Long grantId) {
        load(id);
        grantMapper.delete(new LambdaQueryWrapper<AgentGrant>().eq(AgentGrant::getId, grantId).eq(AgentGrant::getAgentId, id));
        return R.ok();
    }

    // ------------------------------------------------------------------

    private Agent load(String id) {
        Agent a = agentMapper.selectById(id);
        if (a == null || !UserContext.spaceId().equals(a.getSpaceId())) throw BizException.notFound("Agent");
        return a;
    }

    private Set<String> favorites(long uid) {
        Set<String> s = new HashSet<>();
        favMapper.selectList(new LambdaQueryWrapper<AgentFavorite>().eq(AgentFavorite::getUserId, uid)).forEach(f -> s.add(f.getAgentId()));
        return s;
    }

    private Map<String, Object> row(Agent a, Set<String> favs) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", a.getId());
        r.put("name", a.getName());
        r.put("identifier", a.getIdentifier());
        r.put("description", a.getDescription());
        r.put("avatar", a.getAvatar());
        r.put("groupName", a.getGroupName());
        r.put("status", a.getStatus());
        r.put("version", a.getVersion());
        JsonNode c = a.getConfig() == null ? Json.obj() : a.getConfig();
        String ref = c.path("model").path("primary").asText(a.getModelRef());
        Map<String, Object> model = new LinkedHashMap<>();
        if (ref != null && ref.contains("/")) {
            model.put("providerKey", ref.substring(0, ref.indexOf('/')));
            model.put("modelId", ref.substring(ref.indexOf('/') + 1));
        }
        r.put("model", model);
        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("skills", c.path("skills").size());
        cap.put("mcp", c.path("mcpServers").size());
        cap.put("cli", c.path("cliPlugins").size());
        cap.put("memory", c.path("memoryStores").size());
        cap.put("subAgents", c.path("subAgents").size());
        cap.put("envVars", c.path("envVars").size());
        r.put("capabilities", cap);
        r.put("owner", spaceService.userBrief(a.getOwnerId()));
        r.put("favorite", favs.contains(a.getId()));
        r.put("createdAt", a.getCreatedAt());
        r.put("updatedAt", a.getUpdatedAt());
        return r;
    }
}
