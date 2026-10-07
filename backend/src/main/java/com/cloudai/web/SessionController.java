package com.cloudai.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.auth.Perm;
import com.cloudai.auth.RequirePerm;
import com.cloudai.auth.UserContext;
import com.cloudai.common.BizException;
import com.cloudai.common.PageResult;
import com.cloudai.common.R;
import com.cloudai.entity.Agent;
import com.cloudai.entity.ChatSession;
import com.cloudai.entity.SessionEvent;
import com.cloudai.entity.ModelEntities.ModelDef;
import com.cloudai.entity.OpsEntities.FileInfo;
import com.cloudai.entity.RuntimeEntities.RuntimeEnv;
import com.cloudai.entity.RuntimeEntities.Sandbox;
import com.cloudai.mapper.Mappers.*;
import com.cloudai.service.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

/** 会话：列表、创建、发送消息、实时事件流、产物、信息 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {
    private final ChatSessionMapper sessionMapper;
    private final SessionEventMapper eventMapper;
    private final AgentMapper agentMapper;
    private final FileInfoMapper fileMapper;
    private final RuntimeEnvMapper envMapper;
    private final SandboxMapper sandboxMapper;
    private final RunService runService;
    private final SessionHub hub;
    private final SpaceService spaceService;
    private final ModelService modelService;

    @GetMapping
    @RequirePerm(Perm.AGENT_VIEW)
    public R<PageResult<Map<String, Object>>> list(@RequestParam(required = false) String agentId,
                                                   @RequestParam(defaultValue = "0") int mine,
                                                   @RequestParam(required = false) String source,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) Integer version,
                                                   @RequestParam(required = false) String q,
                                                   @RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "20") long size) {
        var w = new LambdaQueryWrapper<ChatSession>().eq(ChatSession::getSpaceId, UserContext.spaceId());
        if (agentId != null && !agentId.isBlank()) w.eq(ChatSession::getAgentId, agentId);
        if (mine == 1) w.eq(ChatSession::getCreatorId, UserContext.userId());
        if (source != null && !source.isBlank()) w.eq(ChatSession::getSource, source);
        else w.ne(ChatSession::getSource, "debug");
        if (status != null && !status.isBlank()) w.eq(ChatSession::getStatus, status);
        if (version != null) w.eq(ChatSession::getAgentVersion, version);
        if (q != null && !q.isBlank()) {
            w.and(x -> x.like(ChatSession::getTitle, q).or().likeRight(ChatSession::getId, q).or().like(ChatSession::getSourceRef, q));
        }
        w.orderByDesc(ChatSession::getUpdatedAt);
        Page<ChatSession> p = sessionMapper.selectPage(new Page<>(page, Math.min(size, 100)), w);
        return R.ok(PageResult.of(p, this::row));
    }

    @Data
    public static class CreateReq {
        private String agentId;
        private String title;
        private String modelRef;
        private String runtimeEnv;
        private String source;
        private Integer agentVersion;
    }

    @PostMapping
    @RequirePerm(Perm.SESSION_USE)
    public R<Map<String, Object>> create(@RequestBody CreateReq req) {
        Agent a = agentMapper.selectById(req.agentId);
        if (a == null || !UserContext.spaceId().equals(a.getSpaceId())) throw BizException.notFound("Agent");
        String source = "debug".equals(req.source) ? "debug" : "web";
        ChatSession s = runService.createSession(a, new RunService.SessionOptions(req.title, source, null,
                UserContext.userId(), req.modelRef, req.runtimeEnv, req.agentVersion));
        return R.ok(row(s));
    }

    @GetMapping("/{id}")
    @RequirePerm(Perm.AGENT_VIEW)
    public R<Map<String, Object>> get(@PathVariable String id) {
        ChatSession s = load(id);
        Map<String, Object> r = row(s);
        Agent a = agentMapper.selectById(s.getAgentId());
        if (a != null) {
            r.put("agent", Map.of("id", a.getId(), "name", a.getName(), "identifier", a.getIdentifier(),
                    "avatar", Objects.toString(a.getAvatar(), ""), "version", a.getVersion()));
        }
        r.put("running", runService.isRunning(id));
        return R.ok(r);
    }

    @PutMapping("/{id}")
    @RequirePerm(Perm.SESSION_USE)
    public R<Void> rename(@PathVariable String id, @RequestBody Map<String, String> body) {
        ChatSession s = load(id);
        String t = body.getOrDefault("title", "").trim();
        BizException.check(!t.isEmpty(), "标题不能为空");
        sessionMapper.update(null, new LambdaUpdateWrapper<ChatSession>().eq(ChatSession::getId, s.getId())
                .set(ChatSession::getTitle, RunService.truncate(t, 200)));
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @RequirePerm(Perm.SESSION_USE)
    public R<Void> delete(@PathVariable String id) {
        ChatSession s = load(id);
        BizException.check(!runService.isRunning(id), "会话正在执行，请先停止");
        eventMapper.delete(new LambdaQueryWrapper<SessionEvent>().eq(SessionEvent::getSessionId, s.getId()));
        sessionMapper.deleteById(s.getId());
        return R.ok();
    }

    @Data
    public static class MessageReq {
        private String content;
        private List<String> fileIds;
        /** 发送前切换模型（会话级） */
        private String modelRef;
    }

    @PostMapping("/{id}/messages")
    @RequirePerm(Perm.SESSION_USE)
    public R<Map<String, Object>> send(@PathVariable String id, @RequestBody MessageReq req) {
        ChatSession s = load(id);
        BizException.check(!runService.isRunning(id), "会话正在执行中，请等待完成或先停止");
        if (req.modelRef != null && !req.modelRef.isBlank() && !req.modelRef.equals(s.getModelRef())) {
            modelService.resolve(s.getSpaceId(), req.modelRef);
            s.setModelRef(req.modelRef);
            sessionMapper.update(null, new LambdaUpdateWrapper<ChatSession>().eq(ChatSession::getId, s.getId())
                    .set(ChatSession::getModelRef, req.modelRef));
        }
        String trigger = "debug".equals(s.getSource()) ? "debug" : "web";
        runService.send(s, req.content, req.fileIds, trigger, UserContext.userId());
        return R.ok(Map.of("sessionId", s.getId()));
    }

    @PostMapping("/{id}/stop")
    @RequirePerm(Perm.SESSION_USE)
    public R<Void> stop(@PathVariable String id) {
        load(id);
        runService.stop(id);
        return R.ok();
    }

    @GetMapping("/{id}/events")
    @RequirePerm(Perm.AGENT_VIEW)
    public R<List<SessionEvent>> events(@PathVariable String id, @RequestParam(defaultValue = "0") int after) {
        load(id);
        return R.ok(eventMapper.selectList(new LambdaQueryWrapper<SessionEvent>()
                .eq(SessionEvent::getSessionId, id).gt(SessionEvent::getSeq, after).orderByAsc(SessionEvent::getSeq)));
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequirePerm(Perm.AGENT_VIEW)
    public SseEmitter stream(@PathVariable String id, @RequestParam(defaultValue = "0") int after) {
        ChatSession s = load(id);
        return openStream(s, after);
    }

    /** 先补发历史事件再订阅实时事件（供分享页复用） */
    public SseEmitter openStream(ChatSession s, int after) {
        SseEmitter e = hub.subscribe(s.getId());
        try {
            for (SessionEvent ev : eventMapper.selectList(new LambdaQueryWrapper<SessionEvent>()
                    .eq(SessionEvent::getSessionId, s.getId()).gt(SessionEvent::getSeq, after).orderByAsc(SessionEvent::getSeq))) {
                e.send(SseEmitter.event().name("event").data(com.cloudai.common.Json.write(ev)));
            }
            Map<String, Object> r = row(s);
            r.put("running", runService.isRunning(s.getId()));
            e.send(SseEmitter.event().name("session").data(com.cloudai.common.Json.write(r)));
        } catch (Exception ex) {
            e.completeWithError(ex);
        }
        return e;
    }

    @GetMapping("/{id}/artifacts")
    @RequirePerm(Perm.AGENT_VIEW)
    public R<List<FileInfo>> artifacts(@PathVariable String id) {
        load(id);
        return R.ok(fileMapper.selectList(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getSessionId, id).eq(FileInfo::getCategory, "artifact")
                .orderByAsc(FileInfo::getFileName).orderByDesc(FileInfo::getVersion)));
    }

    @GetMapping("/{id}/info")
    @RequirePerm(Perm.AGENT_VIEW)
    public R<Map<String, Object>> info(@PathVariable String id) {
        ChatSession s = load(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", row(s));

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("ref", s.getModelRef());
        int ctxWindow = 128000;
        try {
            ModelService.Resolved r = modelService.resolve(s.getSpaceId(), s.getModelRef());
            ModelDef d = r.model();
            ctxWindow = d.getContextWindow() == null ? ctxWindow : d.getContextWindow();
            model.put("name", d.getModelId());
            model.put("provider", r.provider().getProviderKey());
        } catch (Exception e) {
            model.put("name", s.getModelRef());
        }
        model.put("contextWindow", ctxWindow);
        long ctx = nz(s.getContextTokens());
        model.put("contextTokens", ctx);
        model.put("contextPercent", ctxWindow == 0 ? 0 : Math.round(ctx * 1000.0 / ctxWindow) / 10.0);
        out.put("model", model);

        long in = nz(s.getInputTokens()), outT = nz(s.getOutputTokens()), cr = nz(s.getCacheRead()), cw = nz(s.getCacheWrite());
        long total = in + outT + cr + cw;
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input", in);
        usage.put("output", outT);
        usage.put("cacheRead", cr);
        usage.put("cacheWrite", cw);
        usage.put("total", total);
        usage.put("cacheHit", in == 0 ? 0 : Math.round(cr * 100.0 / in));
        out.put("usage", usage);

        RuntimeEnv env = envMapper.selectOne(new LambdaQueryWrapper<RuntimeEnv>().eq(RuntimeEnv::getName, s.getRuntimeEnv())
                .and(x -> x.isNull(RuntimeEnv::getSpaceId).or().eq(RuntimeEnv::getSpaceId, s.getSpaceId())).last("limit 1"));
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("name", s.getRuntimeEnv());
        runtime.put("kind", s.getRuntimeKind());
        runtime.put("networkPolicy", env == null ? "default" : env.getNetworkPolicy());
        runtime.put("system", env != null && Boolean.TRUE.equals(env.getIsSystem()));
        Sandbox sb = sandboxMapper.selectOne(new LambdaQueryWrapper<Sandbox>().eq(Sandbox::getWorkDir, s.getWorkspaceDir())
                .orderByDesc(Sandbox::getCreatedAt).last("limit 1"));
        if (sb != null) {
            runtime.put("sandbox", Map.of("name", sb.getName(), "status", sb.getStatus(), "podName", sb.getPodName()));
        }
        out.put("runtime", runtime);
        out.put("times", Map.of("createdAt", s.getCreatedAt(), "updatedAt", s.getUpdatedAt(),
                "lastEventAt", s.getLastEventAt() == null ? s.getUpdatedAt() : s.getLastEventAt()));
        return R.ok(out);
    }

    // ------------------------------------------------------------------

    private ChatSession load(String id) {
        ChatSession s = sessionMapper.selectById(id);
        if (s == null || !UserContext.spaceId().equals(s.getSpaceId())) throw BizException.notFound("会话");
        return s;
    }

    public Map<String, Object> row(ChatSession s) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", s.getId());
        r.put("title", s.getTitle());
        r.put("source", s.getSource());
        r.put("sourceRef", s.getSourceRef());
        r.put("status", s.getStatus());
        r.put("agentId", s.getAgentId());
        r.put("agentVersion", s.getAgentVersion());
        r.put("modelRef", s.getModelRef());
        r.put("runtimeKind", s.getRuntimeKind());
        r.put("runtimeEnv", s.getRuntimeEnv());
        r.put("creator", spaceService.userBrief(s.getCreatorId()));
        r.put("createdAt", s.getCreatedAt());
        r.put("updatedAt", s.getUpdatedAt());
        r.put("lastEventAt", s.getLastEventAt());
        return r;
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }
}
