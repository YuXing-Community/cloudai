package com.cloudai.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudai.auth.Perm;
import com.cloudai.auth.RequirePerm;
import com.cloudai.auth.UserContext;
import com.cloudai.common.BizException;
import com.cloudai.common.Json;
import com.cloudai.common.R;
import com.cloudai.entity.PluginEntities.Hook;
import com.cloudai.mapper.Mappers.HookMapper;
import com.cloudai.service.SpaceService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Hooks：按运行时创建可被多个 Agent 复用的 Hook 配置，更新生成新版本 */
@RestController
@RequestMapping("/api/hooks")
@RequiredArgsConstructor
public class HookController {
    private final HookMapper mapper;
    private final SpaceService spaceService;

    static final Set<String> EVENTS = Set.of("PreCompact", "Stop", "PreToolUse", "PostToolUse", "PostToolUseFailure");

    @GetMapping
    @RequirePerm(Perm.PLUGIN_VIEW)
    public R<List<Hook>> list() {
        return R.ok(mapper.selectList(new LambdaQueryWrapper<Hook>().eq(Hook::getSpaceId, UserContext.spaceId()).orderByDesc(Hook::getUpdatedAt)));
    }

    @GetMapping("/options")
    public R<List<Map<String, Object>>> options() {
        return R.ok(mapper.selectList(new LambdaQueryWrapper<Hook>().eq(Hook::getSpaceId, UserContext.spaceId()).orderByDesc(Hook::getUpdatedAt))
                .stream().map(h -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", h.getId());
                    m.put("name", h.getName());
                    m.put("description", h.getDescription());
                    m.put("version", h.getVersion());
                    return m;
                }).toList());
    }

    @GetMapping("/{id}")
    @RequirePerm(Perm.PLUGIN_VIEW)
    public R<Map<String, Object>> get(@PathVariable Long id) {
        Hook h = load(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hook", h);
        m.put("creator", spaceService.userBrief(h.getCreatedBy()));
        return R.ok(m);
    }

    @Data
    public static class Req {
        private String name;
        private String description;
        private String runtime;
        private JsonNode config;
    }

    @PostMapping
    @RequirePerm(Perm.PLUGIN_EDIT)
    public R<Hook> create(@RequestBody Req req) {
        validate(req);
        Hook h = new Hook();
        h.setSpaceId(UserContext.spaceId());
        h.setName(req.name.trim());
        h.setDescription(req.description);
        h.setRuntime(req.runtime == null ? "claudecode" : req.runtime);
        h.setConfig(req.config);
        h.setVersion(1);
        h.setCreatedBy(UserContext.userId());
        mapper.insert(h);
        return R.ok(h);
    }

    @PutMapping("/{id}")
    @RequirePerm(Perm.PLUGIN_EDIT)
    public R<Hook> update(@PathVariable Long id, @RequestBody Req req) {
        Hook h = load(id);
        validate(req);
        h.setName(req.name.trim());
        h.setDescription(req.description);
        if (req.runtime != null) h.setRuntime(req.runtime);
        h.setConfig(req.config);
        h.setVersion(h.getVersion() + 1);
        mapper.updateById(h);
        return R.ok(h);
    }

    @DeleteMapping("/{id}")
    @RequirePerm(Perm.PLUGIN_EDIT)
    public R<Void> delete(@PathVariable Long id) {
        mapper.deleteById(load(id).getId());
        return R.ok();
    }

    private void validate(Req req) {
        BizException.check(req.name != null && !req.name.isBlank(), "请填写 Hook 名称");
        if (req.config == null) req.config = Json.obj();
        JsonNode hooks = req.config.path("hooks");
        if (hooks.isObject()) {
            var it = hooks.fields();
            while (it.hasNext()) {
                var e = it.next();
                BizException.check(EVENTS.contains(e.getKey()), "不支持的 Hook 事件：" + e.getKey());
                int gi = 0;
                for (JsonNode g : e.getValue()) {
                    gi++;
                    BizException.check(g.path("handlers").size() > 0, e.getKey() + " 匹配组 " + gi + " 至少需要一个处理器");
                    for (JsonNode hd : g.path("handlers")) {
                        String type = hd.path("type").asText("command");
                        if ("http".equals(type)) BizException.check(hd.path("url").asText().matches("^https?://.+"), e.getKey() + " 的 HTTP 处理器缺少有效 URL");
                        else BizException.check(!hd.path("command").asText().isBlank(), e.getKey() + " 的命令处理器缺少命令");
                    }
                }
            }
        }
    }

    private Hook load(Long id) {
        Hook h = mapper.selectById(id);
        if (h == null || !UserContext.spaceId().equals(h.getSpaceId())) throw BizException.notFound("Hook");
        return h;
    }
}
