package com.cloudai.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.auth.Perm;
import com.cloudai.auth.RequirePerm;
import com.cloudai.auth.UserContext;
import com.cloudai.common.BizException;
import com.cloudai.common.PageResult;
import com.cloudai.common.R;
import com.cloudai.entity.RuntimeEntities.RuntimeEnv;
import com.cloudai.entity.RuntimeEntities.Sandbox;
import com.cloudai.mapper.Mappers.RuntimeEnvMapper;
import com.cloudai.mapper.Mappers.SandboxMapper;
import com.cloudai.service.RunService;
import com.cloudai.service.SpaceService;
import com.cloudai.service.Storage;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

/** 沙箱：运行环境、Sandbox 实例、会话工作区 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SandboxController {
    private final RuntimeEnvMapper envMapper;
    private final SandboxMapper sandboxMapper;
    private final SpaceService spaceService;
    private final RunService runService;
    private final Storage storage;
    private final JdbcTemplate jdbc;

    // ================================================================ 运行环境

    private String defaultEnv(String sid) {
        try {
            Long id = jdbc.queryForObject("SELECT env_id FROM space_default_env WHERE space_id = ?", Long.class, sid);
            RuntimeEnv e = id == null ? null : envMapper.selectById(id);
            if (e != null) return e.getName();
        } catch (Exception ignored) {
        }
        return "cloudai-cli-runtime";
    }

    private List<RuntimeEnv> visibleEnvs(String sid) {
        return envMapper.selectList(new LambdaQueryWrapper<RuntimeEnv>()
                .and(w -> w.isNull(RuntimeEnv::getSpaceId).or().eq(RuntimeEnv::getSpaceId, sid))
                .orderByDesc(RuntimeEnv::getIsSystem).orderByAsc(RuntimeEnv::getId));
    }

    @GetMapping("/runtime-envs")
    @RequirePerm(Perm.SANDBOX_VIEW)
    public R<Map<String, Object>> envs(@RequestParam(required = false) String usage,
                                       @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size) {
        String sid = UserContext.spaceId();
        String def = defaultEnv(sid);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RuntimeEnv e : visibleEnvs(sid)) {
            if (usage != null && !usage.isBlank() && !usage.equals(e.getUsageType())) continue;
            Map<String, Object> m = envRow(e);
            m.put("isDefault", e.getName().equals(def));
            rows.add(m);
        }
        Boolean custom = jdbc.queryForObject("SELECT custom_env_enabled FROM space WHERE id = ?", Boolean.class, sid);
        return R.ok(Map.of("page", PageResult.ofList(rows, page, size), "customEnabled", Boolean.TRUE.equals(custom)));
    }

    private Map<String, Object> envRow(RuntimeEnv e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("name", e.getName());
        m.put("description", e.getDescription());
        m.put("isSystem", e.getIsSystem());
        m.put("platformOnly", e.getPlatformOnly());
        m.put("template", e.getTemplate());
        m.put("usageType", e.getUsageType());
        m.put("resourceGroup", e.getResourceGroup());
        m.put("prewarm", e.getPrewarm());
        m.put("networkPolicy", e.getNetworkPolicy());
        m.put("packages", e.getPackages());
        m.put("metadata", e.getMetadata());
        m.put("version", e.getVersion());
        m.put("status", e.getStatus());
        m.put("updatedAt", e.getUpdatedAt());
        Map<String, Integer> pc = new LinkedHashMap<>();
        if (e.getPackages() != null) for (JsonNode p : e.getPackages()) pc.merge(p.path("manager").asText("pip"), 1, Integer::sum);
        m.put("packageSummary", pc);
        return m;
    }

    @GetMapping("/runtime-envs/options")
    public R<List<Map<String, Object>>> envOptions() {
        String sid = UserContext.spaceId();
        String def = defaultEnv(sid);
        return R.ok(visibleEnvs(sid).stream().filter(e -> "available".equals(e.getStatus())).map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.getName());
            m.put("description", e.getDescription());
            m.put("isDefault", e.getName().equals(def));
            m.put("usageType", e.getUsageType());
            m.put("isSystem", e.getIsSystem());
            return m;
        }).toList());
    }

    @PutMapping("/runtime-envs/{id}/default")
    @RequirePerm(Perm.SANDBOX_EDIT)
    public R<Void> setDefault(@PathVariable Long id) {
        RuntimeEnv e = loadEnv(id);
        jdbc.update("REPLACE INTO space_default_env (space_id, env_id) VALUES (?, ?)", UserContext.spaceId(), e.getId());
        return R.ok();
    }

    @Data
    public static class EnvReq {
        private String name;
        private String description;
        private String usageType;
        private String resourceGroup;
        private Integer prewarm;
        private String networkPolicy;
        private JsonNode packages;
        private JsonNode metadata;
    }

    @PostMapping("/runtime-envs")
    @RequirePerm(Perm.SANDBOX_EDIT)
    public R<RuntimeEnv> createEnv(@RequestBody EnvReq req) {
        String sid = UserContext.spaceId();
        Boolean custom = jdbc.queryForObject("SELECT custom_env_enabled FROM space WHERE id = ?", Boolean.class, sid);
        BizException.check(Boolean.TRUE.equals(custom), "当前空间未开启自定义运行环境，无法创建运行环境");
        BizException.check(req.name != null && req.name.matches("^[a-z][a-z0-9\\-]{1,62}$"), "名称须为小写字母开头，可含数字和短横线");
        long dup = envMapper.selectCount(new LambdaQueryWrapper<RuntimeEnv>().eq(RuntimeEnv::getName, req.name)
                .and(w -> w.isNull(RuntimeEnv::getSpaceId).or().eq(RuntimeEnv::getSpaceId, sid)));
        BizException.check(dup == 0, "运行环境名称已存在");
        RuntimeEnv e = new RuntimeEnv();
        e.setSpaceId(sid);
        e.setIsSystem(false);
        e.setPlatformOnly(false);
        e.setTemplate("custom");
        e.setVersion(1);
        e.setStatus("available");
        applyEnv(e, req);
        envMapper.insert(e);
        return R.ok(e);
    }

    @PutMapping("/runtime-envs/{id}")
    @RequirePerm(Perm.SANDBOX_EDIT)
    public R<RuntimeEnv> updateEnv(@PathVariable Long id, @RequestBody EnvReq req) {
        RuntimeEnv e = loadEnv(id);
        BizException.check(!Boolean.TRUE.equals(e.getIsSystem()), "系统环境由平台统一维护，当前工作空间仅可查看配置");
        req.name = e.getName();
        applyEnv(e, req);
        e.setVersion(e.getVersion() + 1);
        envMapper.updateById(e);
        return R.ok(e);
    }

    @DeleteMapping("/runtime-envs/{id}")
    @RequirePerm(Perm.SANDBOX_EDIT)
    public R<Void> deleteEnv(@PathVariable Long id) {
        RuntimeEnv e = loadEnv(id);
        BizException.check(!Boolean.TRUE.equals(e.getIsSystem()), "系统环境不可删除");
        envMapper.deleteById(e.getId());
        return R.ok();
    }

    private void applyEnv(RuntimeEnv e, EnvReq r) {
        e.setName(r.name);
        e.setDescription(r.description);
        e.setUsageType(r.usageType == null ? "agent" : r.usageType);
        e.setResourceGroup(r.resourceGroup == null ? "local" : r.resourceGroup);
        e.setPrewarm(r.prewarm == null ? 0 : r.prewarm);
        e.setNetworkPolicy(r.networkPolicy == null ? "default" : r.networkPolicy);
        e.setPackages(r.packages);
        e.setMetadata(r.metadata);
    }

    private RuntimeEnv loadEnv(Long id) {
        RuntimeEnv e = envMapper.selectById(id);
        if (e == null || (e.getSpaceId() != null && !UserContext.spaceId().equals(e.getSpaceId()))) throw BizException.notFound("运行环境");
        return e;
    }

    // ================================================================ Sandbox

    @GetMapping("/sandboxes")
    @RequirePerm(Perm.SANDBOX_VIEW)
    public R<PageResult<Map<String, Object>>> sandboxes(@RequestParam(required = false) String q,
                                                        @RequestParam(required = false) String env,
                                                        @RequestParam(defaultValue = "running") String status,
                                                        @RequestParam(defaultValue = "1") long page,
                                                        @RequestParam(defaultValue = "10") long size) {
        var w = new LambdaQueryWrapper<Sandbox>().eq(Sandbox::getSpaceId, UserContext.spaceId());
        if (q != null && !q.isBlank()) w.and(x -> x.like(Sandbox::getName, q).or().like(Sandbox::getId, q).or().like(Sandbox::getPodName, q));
        if (env != null && !env.isBlank()) w.eq(Sandbox::getEnvName, env);
        if (status != null && !status.isBlank() && !"all".equals(status)) w.eq(Sandbox::getStatus, status);
        w.orderByDesc(Sandbox::getCreatedAt);
        Page<Sandbox> p = sandboxMapper.selectPage(new Page<>(page, size), w);
        return R.ok(PageResult.of(p, s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("name", s.getName());
            m.put("status", s.getStatus());
            m.put("envName", s.getEnvName());
            m.put("podName", s.getPodName());
            m.put("resourceGroup", s.getResourceGroup());
            m.put("owner", s.getOwner());
            m.put("sessionId", s.getSessionId());
            m.put("timeoutSeconds", s.getTimeoutSeconds());
            m.put("createdAt", s.getCreatedAt());
            m.put("destroyAt", s.getDestroyAt());
            m.put("running", s.getSessionId() != null && runService.isRunning(s.getSessionId()));
            return m;
        }));
    }

    @PutMapping("/sandboxes/{id}/timeout")
    @RequirePerm(Perm.SANDBOX_EDIT)
    public R<Void> timeout(@PathVariable String id, @RequestBody Map<String, Integer> body) {
        Sandbox s = loadSandbox(id);
        int sec = body.getOrDefault("seconds", 1800);
        BizException.check(sec >= 60 && sec <= 7 * 24 * 3600, "超时须在 60 秒 ~ 7 天之间");
        s.setTimeoutSeconds(sec);
        s.setDestroyAt(LocalDateTime.now().plusSeconds(sec));
        sandboxMapper.updateById(s);
        return R.ok();
    }

    @PostMapping("/sandboxes/{id}/destroy")
    @RequirePerm(Perm.SANDBOX_EDIT)
    public R<Void> destroy(@PathVariable String id) {
        destroySandbox(loadSandbox(id));
        return R.ok();
    }

    private void destroySandbox(Sandbox s) {
        if (s.getSessionId() != null && runService.isRunning(s.getSessionId())) runService.stop(s.getSessionId());
        s.setStatus("destroyed");
        s.setDestroyAt(LocalDateTime.now());
        sandboxMapper.updateById(s);
        // 只回收进程资源；工作区文件保留给「工作区」页面查看，由用户手动清理
    }

    /** 到期自动销毁 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void reap() {
        List<Sandbox> expired = sandboxMapper.selectList(new LambdaQueryWrapper<Sandbox>()
                .eq(Sandbox::getStatus, "running").le(Sandbox::getDestroyAt, LocalDateTime.now()));
        for (Sandbox s : expired) {
            try {
                destroySandbox(s);
            } catch (Exception e) {
                log.warn("reap sandbox {} failed: {}", s.getId(), e.getMessage());
            }
        }
    }

    private Sandbox loadSandbox(String id) {
        Sandbox s = sandboxMapper.selectById(id);
        if (s == null || !UserContext.spaceId().equals(s.getSpaceId())) throw BizException.notFound("Sandbox");
        return s;
    }

    // ================================================================ 工作区（会话沙箱的文件）

    @GetMapping("/workspaces")
    @RequirePerm(Perm.SANDBOX_VIEW)
    public R<List<Map<String, Object>>> workspaces() {
        String sid = UserContext.spaceId();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.workspace_dir dir, MAX(s.updated_at) updated_at, COUNT(*) sessions, MAX(s.title) title,
                       MAX(a.name) agent_name, MAX(s.id) session_id
                FROM chat_session s LEFT JOIN agent a ON a.id = s.agent_id
                WHERE s.space_id = ? AND s.workspace_dir IS NOT NULL GROUP BY s.workspace_dir ORDER BY updated_at DESC LIMIT 200""", sid);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) {
            Path p = storage.abs(String.valueOf(r.get("dir")));
            if (!Files.isDirectory(p)) continue;
            Map<String, Object> m = new LinkedHashMap<>(r);
            long[] stat = dirStat(p);
            m.put("files", stat[0]);
            m.put("size", stat[1]);
            out.add(m);
        }
        return R.ok(out);
    }

    @GetMapping("/workspaces/tree")
    @RequirePerm(Perm.SANDBOX_VIEW)
    public R<List<Map<String, Object>>> tree(@RequestParam String dir) {
        Path root = workspaceRoot(dir);
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, 6)) {
            walk.filter(x -> !x.equals(root)).limit(2000).forEach(x -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", root.relativize(x).toString().replace('\\', '/'));
                m.put("dir", Files.isDirectory(x));
                try {
                    m.put("size", Files.isDirectory(x) ? 0 : Files.size(x));
                } catch (IOException e) {
                    m.put("size", 0);
                }
                out.add(m);
            });
        } catch (IOException e) {
            throw new BizException("读取工作区失败");
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("path"))));
        return R.ok(out);
    }

    @GetMapping("/workspaces/file")
    @RequirePerm(Perm.SANDBOX_VIEW)
    public R<Map<String, Object>> file(@RequestParam String dir, @RequestParam String path) {
        Path f = Storage.within(workspaceRoot(dir), path);
        BizException.check(Files.isRegularFile(f), "文件不存在");
        try {
            long size = Files.size(f);
            BizException.check(size < 2 * 1024 * 1024, "文件超过 2MB，不支持预览");
            byte[] b = Files.readAllBytes(f);
            for (int i = 0; i < Math.min(b.length, 4096); i++) {
                if (b[i] == 0) return R.ok(Map.of("binary", true, "size", size));
            }
            return R.ok(Map.of("content", new String(b, java.nio.charset.StandardCharsets.UTF_8), "size", size));
        } catch (IOException e) {
            throw new BizException("读取文件失败");
        }
    }

    @DeleteMapping("/workspaces")
    @RequirePerm(Perm.SANDBOX_EDIT)
    public R<Void> clean(@RequestParam String dir) {
        Storage.deleteRecursively(workspaceRoot(dir));
        return R.ok();
    }

    /** 只允许访问本空间会话所使用的工作区目录 */
    private Path workspaceRoot(String dir) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM chat_session WHERE space_id = ? AND workspace_dir = ?", Long.class,
                UserContext.spaceId(), dir);
        if (n == null || n == 0) throw BizException.notFound("工作区");
        Path p = storage.abs(dir);
        BizException.check(p.startsWith(storage.root().resolve("workspaces")), "非法路径");
        return p;
    }

    private static long[] dirStat(Path p) {
        long[] r = new long[2];
        try (Stream<Path> walk = Files.walk(p)) {
            walk.filter(Files::isRegularFile).forEach(f -> {
                r[0]++;
                try {
                    r[1] += Files.size(f);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
        return r;
    }

    @SuppressWarnings("unused")
    private Map<String, Object> brief(Long uid) {
        return spaceService.userBrief(uid);
    }
}
