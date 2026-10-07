package com.cloudai.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudai.auth.Perm;
import com.cloudai.auth.RequirePerm;
import com.cloudai.auth.UserContext;
import com.cloudai.common.BizException;
import com.cloudai.common.Json;
import com.cloudai.common.R;
import com.cloudai.entity.PluginEntities.Credential;
import com.cloudai.mapper.Mappers.CredentialMapper;
import com.cloudai.service.CredentialService;
import com.cloudai.service.SpaceService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 凭据：值 AES 加密存储，任何接口都不返回原值 */
@RestController
@RequestMapping("/api/credentials")
@RequiredArgsConstructor
public class CredentialController {
    private final CredentialMapper mapper;
    private final CredentialService service;
    private final SpaceService spaceService;
    private final JdbcTemplate jdbc;

    @GetMapping
    @RequirePerm(Perm.PLUGIN_VIEW)
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String q, @RequestParam(required = false) String type) {
        var w = new LambdaQueryWrapper<Credential>().eq(Credential::getSpaceId, UserContext.spaceId());
        if (q != null && !q.isBlank()) w.like(Credential::getName, q);
        if (type != null && !type.isBlank()) w.eq(Credential::getType, type);
        w.orderByDesc(Credential::getUpdatedAt);
        return R.ok(mapper.selectList(w).stream().map(this::row).toList());
    }

    private Map<String, Object> row(Credential c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("type", c.getType());
        m.put("fields", c.getFieldNames());
        m.put("owner", spaceService.userBrief(c.getOwnerId()));
        Long refs = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent WHERE space_id = ? AND (
                  JSON_CONTAINS(COALESCE(JSON_EXTRACT(config, '$.envVars'), JSON_ARRAY()), JSON_OBJECT('credentialId', CAST(? AS UNSIGNED)))
                  OR JSON_CONTAINS(COALESCE(JSON_EXTRACT(config, '$.cliPlugins'), JSON_ARRAY()), JSON_OBJECT('credentialId', CAST(? AS UNSIGNED))))""",
                Long.class, c.getSpaceId(), c.getId(), c.getId());
        m.put("refAgents", refs);
        m.put("createdAt", c.getCreatedAt());
        m.put("updatedAt", c.getUpdatedAt());
        return m;
    }

    @GetMapping("/options")
    public R<List<Map<String, Object>>> options(@RequestParam(required = false) String type) {
        var w = new LambdaQueryWrapper<Credential>().eq(Credential::getSpaceId, UserContext.spaceId());
        if (type != null && !type.isBlank()) w.eq(Credential::getType, type);
        return R.ok(mapper.selectList(w.orderByDesc(Credential::getUpdatedAt)).stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("type", c.getType());
            m.put("fields", c.getFieldNames());
            return m;
        }).toList());
    }

    @Data
    public static class Req {
        private String name;
        private String type;
        private List<Map<String, String>> fields;
    }

    @PostMapping
    @RequirePerm({Perm.PLUGIN_EDIT, Perm.AGENT_EDIT})
    public R<Map<String, Object>> create(@RequestBody Req req) {
        BizException.check(req.name != null && !req.name.isBlank(), "请填写凭证名称");
        BizException.check(req.fields != null && !req.fields.isEmpty(), "至少需要一个密钥字段");
        Credential c = new Credential();
        c.setSpaceId(UserContext.spaceId());
        c.setName(req.name.trim());
        c.setType(req.type == null || req.type.isBlank() ? "generic" : req.type);
        c.setOwnerId(UserContext.userId());
        service.setFields(c, fields(req));
        mapper.insert(c);
        return R.ok(row(c));
    }

    @PutMapping("/{id}")
    @RequirePerm(Perm.PLUGIN_EDIT)
    public R<Map<String, Object>> update(@PathVariable Long id, @RequestBody Req req) {
        Credential c = service.get(UserContext.spaceId(), id);
        if (req.name != null && !req.name.isBlank()) c.setName(req.name.trim());
        if (req.type != null && !req.type.isBlank()) c.setType(req.type);
        if (req.fields != null) service.setFields(c, fields(req));
        mapper.updateById(c);
        return R.ok(row(c));
    }

    @DeleteMapping("/{id}")
    @RequirePerm(Perm.PLUGIN_EDIT)
    public R<Void> delete(@PathVariable Long id) {
        Credential c = service.get(UserContext.spaceId(), id);
        mapper.deleteById(c.getId());
        return R.ok();
    }

    private List<CredentialService.Field> fields(Req req) {
        List<CredentialService.Field> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Map<String, String> f : req.fields) {
            String n = Objects.toString(f.get("name"), "").trim();
            if (n.isEmpty()) continue;
            BizException.check(names.add(n), "字段名称重复：" + n);
            out.add(new CredentialService.Field(n, Objects.toString(f.get("envName"), "").trim(), f.get("value")));
        }
        BizException.check(!out.isEmpty(), "至少需要一个密钥字段");
        return out;
    }

    @SuppressWarnings("unused")
    private static String json(Object o) {
        return Json.write(o);
    }
}
