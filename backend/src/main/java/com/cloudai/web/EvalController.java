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
import com.cloudai.entity.Agent;
import com.cloudai.entity.ChatSession;
import com.cloudai.entity.EvalEntities.EvalAnnotation;
import com.cloudai.entity.EvalEntities.EvalResult;
import com.cloudai.entity.EvalEntities.EvalRun;
import com.cloudai.entity.EvalEntities.EvalTask;
import com.cloudai.mapper.Mappers.AgentMapper;
import com.cloudai.mapper.Mappers.ChatSessionMapper;
import com.cloudai.mapper.Mappers.EvalAnnotationMapper;
import com.cloudai.mapper.Mappers.EvalResultMapper;
import com.cloudai.mapper.Mappers.EvalRunMapper;
import com.cloudai.mapper.Mappers.EvalTaskMapper;
import com.cloudai.service.EvalService;
import com.cloudai.service.ModelService;
import com.cloudai.service.ScheduleService;
import com.cloudai.service.SpaceService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Agent 评测：评测任务、执行批次、评测结果、候选执行记录 */
@RestController
@RequestMapping("/api/evals")
@RequiredArgsConstructor
public class EvalController {
    private final EvalTaskMapper taskMapper;
    private final EvalRunMapper runMapper;
    private final EvalResultMapper resultMapper;
    private final EvalAnnotationMapper annotationMapper;
    private final ChatSessionMapper sessionMapper;
    private final AgentMapper agentMapper;
    private final EvalService evalService;
    private final ModelService modelService;
    private final SpaceService spaceService;

    // ------------------------------------------------------------------ 任务

    @GetMapping
    @RequirePerm(Perm.EVAL_VIEW)
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String q) {
        var w = new LambdaQueryWrapper<EvalTask>().eq(EvalTask::getSpaceId, UserContext.spaceId());
        if (q != null && !q.isBlank()) w.and(x -> x.like(EvalTask::getName, q).or().like(EvalTask::getDescription, q));
        w.orderByDesc(EvalTask::getId);
        Map<String, String> agentNames = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (EvalTask t : taskMapper.selectList(w)) {
            Map<String, Object> m = row(t, agentNames);
            m.putAll(stats(t.getId()));
            out.add(m);
        }
        return R.ok(out);
    }

    @GetMapping("/{id}")
    @RequirePerm(Perm.EVAL_VIEW)
    public R<Map<String, Object>> get(@PathVariable Long id) {
        EvalTask t = load(id);
        Map<String, Object> m = row(t, new HashMap<>());
        m.putAll(stats(id));
        m.put("creator", spaceService.userBrief(t.getCreatorId()));
        return R.ok(m);
    }

    private Map<String, Object> row(EvalTask t, Map<String, String> agentNames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("description", t.getDescription());
        m.put("agentIds", t.getAgentIds());
        List<Map<String, String>> agents = new ArrayList<>();
        for (String aid : EvalService.texts(t.getAgentIds())) {
            agents.add(Map.of("id", aid, "name", agentNames.computeIfAbsent(aid, k -> {
                Agent a = agentMapper.selectById(k);
                return a == null ? "已删除的 Agent" : a.getName();
            })));
        }
        m.put("agents", agents);
        m.put("judgeModelRef", t.getJudgeModelRef());
        m.put("replayModelRef", t.getReplayModelRef());
        m.put("filters", t.getFilters());
        m.put("criteria", t.getCriteria());
        m.put("passScore", t.getPassScore());
        m.put("skipEvaluated", t.getSkipEvaluated());
        m.put("triggerMode", t.getTriggerMode());
        m.put("cron", t.getCron());
        m.put("timezone", t.getTimezone());
        m.put("nextFireAt", t.getNextFireAt());
        m.put("realtimeSince", t.getRealtimeSince());
        m.put("enabled", t.getEnabled());
        m.put("lastStatus", t.getLastStatus());
        m.put("lastRunAt", t.getLastRunAt());
        m.put("createdAt", t.getCreatedAt());
        m.put("running", evalService.isRunning(t.getId()));
        return m;
    }

    /** 任务维度统计：每个会话取最新一次成功评测 */
    private Map<String, Object> stats(Long taskId) {
        List<EvalResult> rs = resultMapper.selectList(new LambdaQueryWrapper<EvalResult>().eq(EvalResult::getTaskId, taskId)
                .eq(EvalResult::getStatus, "success").select(EvalResult::getId, EvalResult::getSessionId, EvalResult::getScore, EvalResult::getPassed)
                .orderByDesc(EvalResult::getId));
        Map<String, EvalResult> latest = new LinkedHashMap<>();
        for (EvalResult r : rs) latest.putIfAbsent(r.getSessionId(), r);
        BigDecimal sum = BigDecimal.ZERO;
        int pass = 0;
        for (EvalResult r : latest.values()) {
            sum = sum.add(r.getScore() == null ? BigDecimal.ZERO : r.getScore());
            if (Boolean.TRUE.equals(r.getPassed())) pass++;
        }
        int n = latest.size();
        long annotated = n == 0 ? 0 : annotationMapper.selectCount(new LambdaQueryWrapper<EvalAnnotation>()
                .in(EvalAnnotation::getEvalResultId, latest.values().stream().map(EvalResult::getId).toList()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionCount", n);
        m.put("avgScore", n == 0 ? null : sum.divide(BigDecimal.valueOf(n), 1, RoundingMode.HALF_UP));
        m.put("passRate", n == 0 ? null : Math.round(pass * 1000.0 / n) / 10.0);
        m.put("annotatedCount", annotated);
        m.put("runCount", runMapper.selectCount(new LambdaQueryWrapper<EvalRun>().eq(EvalRun::getTaskId, taskId)));
        return m;
    }

    @Data
    public static class Req {
        private String name;
        private String description;
        private List<String> agentIds;
        private String judgeModelRef;
        private String replayModelRef;
        private JsonNode filters;
        private String criteria;
        private Integer passScore;
        private Boolean skipEvaluated;
        private String triggerMode;
        private String cron;
        private String timezone;
        private Boolean enabled;
    }

    @PostMapping
    @RequirePerm(Perm.EVAL_EDIT)
    public R<Map<String, Object>> create(@RequestBody Req req) {
        EvalTask t = new EvalTask();
        t.setSpaceId(UserContext.spaceId());
        t.setCreatorId(UserContext.userId());
        apply(t, req);
        taskMapper.insert(t);
        return R.ok(row(t, new HashMap<>()));
    }

    @PutMapping("/{id}")
    @RequirePerm(Perm.EVAL_EDIT)
    public R<Map<String, Object>> update(@PathVariable Long id, @RequestBody Req req) {
        EvalTask t = load(id);
        apply(t, req);
        taskMapper.updateById(t);
        return R.ok(row(t, new HashMap<>()));
    }

    private void apply(EvalTask t, Req r) {
        String sid = t.getSpaceId();
        BizException.check(r.name != null && !r.name.isBlank(), "请填写任务名称");
        BizException.check(r.agentIds != null && !r.agentIds.isEmpty(), "请至少选择一个 Agent");
        for (String aid : r.agentIds) {
            Agent a = agentMapper.selectById(aid);
            BizException.check(a != null && sid.equals(a.getSpaceId()), "请选择当前空间的 Agent");
        }
        BizException.check(r.judgeModelRef != null && !r.judgeModelRef.isBlank(), "请选择评测模型");
        modelService.resolve(sid, r.judgeModelRef);
        String replay = r.replayModelRef == null || r.replayModelRef.isBlank() ? null : r.replayModelRef;
        if (replay != null) modelService.resolve(sid, replay);
        String mode = r.triggerMode == null ? "manual" : r.triggerMode;
        BizException.check(List.of("manual", "schedule", "realtime").contains(mode), "触发方式无效");

        JsonNode f = r.filters == null || !r.filters.isObject() ? Json.obj() : r.filters;
        String tm = f.path("timeMode").asText("all");
        if ("range".equals(tm)) {
            LocalDateTime from = EvalService.time(f.path("from").asText(null)), to = EvalService.time(f.path("to").asText(null));
            BizException.check(from == null || to == null || !from.isAfter(to), "开始时间不能晚于结束时间");
        }

        t.setName(r.name.trim());
        t.setDescription(r.description);
        var ids = Json.arr();
        r.agentIds.forEach(ids::add);
        t.setAgentIds(ids);
        t.setJudgeModelRef(r.judgeModelRef);
        t.setReplayModelRef(replay);
        t.setFilters(f);
        t.setCriteria(r.criteria);
        t.setPassScore(r.passScore == null ? 60 : Math.max(0, Math.min(100, r.passScore)));
        t.setSkipEvaluated(r.skipEvaluated == null || r.skipEvaluated);
        t.setTimezone(r.timezone == null || r.timezone.isBlank() ? "Asia/Shanghai" : r.timezone);
        boolean wasRealtime = "realtime".equals(t.getTriggerMode()) && Boolean.TRUE.equals(t.getEnabled());
        t.setTriggerMode(mode);
        t.setEnabled(r.enabled == null || r.enabled);
        if ("schedule".equals(mode)) {
            BizException.check(r.cron != null && !r.cron.isBlank(), "请填写执行周期");
            t.setCron(r.cron.trim().replaceAll("\\s+", " "));
            ScheduleService.parse(t.getCron());
            t.setNextFireAt(evalService.nextFire(t, LocalDateTime.now()));
        } else {
            t.setCron(r.cron == null || r.cron.isBlank() ? null : r.cron.trim());
            t.setNextFireAt(null);
        }
        // 实时模式从开启时刻起评测新会话
        if ("realtime".equals(mode) && t.getEnabled() && (!wasRealtime || t.getRealtimeSince() == null)) t.setRealtimeSince(LocalDateTime.now());
        if (!"realtime".equals(mode)) t.setRealtimeSince(null);
    }

    @PutMapping("/{id}/enabled")
    @RequirePerm(Perm.EVAL_EDIT)
    public R<Void> enable(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        EvalTask t = load(id);
        boolean en = Boolean.TRUE.equals(body.get("enabled"));
        t.setEnabled(en);
        if ("schedule".equals(t.getTriggerMode())) t.setNextFireAt(en ? evalService.nextFire(t, LocalDateTime.now()) : null);
        if ("realtime".equals(t.getTriggerMode())) t.setRealtimeSince(en ? LocalDateTime.now() : null);
        taskMapper.updateById(t);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @RequirePerm(Perm.EVAL_EDIT)
    @Transactional
    public R<Void> delete(@PathVariable Long id) {
        EvalTask t = load(id);
        BizException.check(!evalService.isRunning(id), "任务正在执行中，请先停止");
        taskMapper.deleteById(t.getId());
        runMapper.delete(new LambdaQueryWrapper<EvalRun>().eq(EvalRun::getTaskId, id));
        resultMapper.delete(new LambdaQueryWrapper<EvalResult>().eq(EvalResult::getTaskId, id));
        return R.ok();
    }

    @PostMapping("/{id}/run")
    @RequirePerm(Perm.EVAL_EDIT)
    public R<EvalRun> run(@PathVariable Long id) {
        return R.ok(evalService.start(load(id), "manual", UserContext.userId(), null));
    }

    @GetMapping("/{id}/runs")
    @RequirePerm(Perm.EVAL_VIEW)
    public R<PageResult<EvalRun>> runs(@PathVariable Long id, @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "20") long size) {
        load(id);
        return R.ok(PageResult.of(runMapper.selectPage(new Page<>(page, Math.min(size, 100)),
                new LambdaQueryWrapper<EvalRun>().eq(EvalRun::getTaskId, id).orderByDesc(EvalRun::getId))));
    }

    @PostMapping("/runs/{runId}/stop")
    @RequirePerm(Perm.EVAL_EDIT)
    public R<Void> stop(@PathVariable Long runId) {
        EvalRun run = runMapper.selectById(runId);
        if (run == null || !UserContext.spaceId().equals(run.getSpaceId())) throw BizException.notFound("评测批次");
        evalService.stop(run);
        return R.ok();
    }

    // ------------------------------------------------------------------ 结果

    @GetMapping("/results")
    @RequirePerm(Perm.EVAL_VIEW)
    public R<PageResult<Map<String, Object>>> results(@RequestParam(required = false) Long taskId,
                                                      @RequestParam(required = false) Long runId,
                                                      @RequestParam(required = false) String agentId,
                                                      @RequestParam(required = false) String status,
                                                      @RequestParam(required = false) Boolean passed,
                                                      @RequestParam(required = false) BigDecimal minScore,
                                                      @RequestParam(required = false) BigDecimal maxScore,
                                                      @RequestParam(required = false) String annotated,
                                                      @RequestParam(required = false) String q,
                                                      @RequestParam(defaultValue = "1") long page,
                                                      @RequestParam(defaultValue = "20") long size) {
        var w = new LambdaQueryWrapper<EvalResult>().eq(EvalResult::getSpaceId, UserContext.spaceId());
        if (taskId != null) w.eq(EvalResult::getTaskId, taskId);
        if (runId != null) w.eq(EvalResult::getRunId, runId);
        if (agentId != null && !agentId.isBlank()) w.eq(EvalResult::getAgentId, agentId);
        if (status != null && !status.isBlank()) w.eq(EvalResult::getStatus, status);
        if (passed != null) w.eq(EvalResult::getPassed, passed);
        if (minScore != null) w.ge(EvalResult::getScore, minScore);
        if (maxScore != null) w.le(EvalResult::getScore, maxScore);
        if ("yes".equals(annotated)) w.apply("EXISTS (SELECT 1 FROM eval_annotation a WHERE a.eval_result_id = eval_result.id)");
        if ("no".equals(annotated)) w.apply("NOT EXISTS (SELECT 1 FROM eval_annotation a WHERE a.eval_result_id = eval_result.id)");
        if (q != null && !q.isBlank()) w.and(x -> x.likeRight(EvalResult::getSessionId, q.trim()).or().like(EvalResult::getSessionTitle, q.trim()));
        w.orderByDesc(EvalResult::getId);
        Page<EvalResult> p = resultMapper.selectPage(new Page<>(page, Math.min(size, 100)), w);
        Map<String, String> agentNames = new HashMap<>();
        Map<Long, String> taskNames = new HashMap<>();
        return R.ok(PageResult.of(p, r -> resultRow(r, agentNames, taskNames, false)));
    }

    @GetMapping("/results/{id}")
    @RequirePerm(Perm.EVAL_VIEW)
    public R<Map<String, Object>> result(@PathVariable Long id) {
        return R.ok(resultRow(loadResult(id), new HashMap<>(), new HashMap<>(), true));
    }

    @PostMapping("/results/{id}/rerun")
    @RequirePerm(Perm.EVAL_EDIT)
    public R<Void> rerun(@PathVariable Long id) {
        evalService.rerun(loadResult(id));
        return R.ok();
    }

    public Map<String, Object> resultRow(EvalResult r, Map<String, String> agentNames, Map<Long, String> taskNames, boolean full) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("taskId", r.getTaskId());
        m.put("taskName", taskNames.computeIfAbsent(r.getTaskId(), k -> {
            EvalTask t = taskMapper.selectById(k);
            return t == null ? "已删除的任务" : t.getName();
        }));
        m.put("runId", r.getRunId());
        m.put("sessionId", r.getSessionId());
        m.put("replaySessionId", r.getReplaySessionId());
        m.put("agentId", r.getAgentId());
        m.put("agentName", agentNames.computeIfAbsent(r.getAgentId(), k -> {
            Agent a = agentMapper.selectById(k);
            return a == null ? "已删除的 Agent" : a.getName();
        }));
        m.put("agentVersion", r.getAgentVersion());
        m.put("sessionTitle", r.getSessionTitle());
        m.put("sessionModelRef", r.getSessionModelRef());
        m.put("judgeModelRef", r.getJudgeModelRef());
        m.put("status", r.getStatus());
        m.put("score", r.getScore());
        m.put("passed", r.getPassed());
        m.put("dimensions", r.getDimensions());
        m.put("summary", r.getSummary());
        m.put("issueCount", r.getIssues() == null ? 0 : r.getIssues().size());
        m.put("error", r.getError());
        m.put("durationMs", r.getDurationMs());
        m.put("createdAt", r.getCreatedAt());
        m.put("finishedAt", r.getFinishedAt());
        List<EvalAnnotation> anns = annotationMapper.selectList(new LambdaQueryWrapper<EvalAnnotation>()
                .eq(EvalAnnotation::getEvalResultId, r.getId()).orderByDesc(EvalAnnotation::getUpdatedAt));
        m.put("annotationCount", anns.size());
        m.put("humanScore", anns.isEmpty() ? null : anns.get(0).getScore());
        m.put("humanLabel", anns.isEmpty() ? null : anns.get(0).getLabel());
        if (full) {
            m.put("issues", r.getIssues());
            m.put("suggestions", r.getSuggestions());
            m.put("improvedReply", r.getImprovedReply());
            m.put("judgeInputTokens", r.getJudgeInputTokens());
            m.put("judgeOutputTokens", r.getJudgeOutputTokens());
            m.put("sessionLastEventAt", r.getSessionLastEventAt());
        }
        return m;
    }

    // ------------------------------------------------------------------ 候选执行记录

    @Data
    public static class CandidateReq {
        private List<String> agentIds;
        private JsonNode filters;
        private Long taskId;
        private long page = 1;
        private long size = 10;
    }

    /** 预览任务会选中的执行记录（按时间、会话 id、会话内容模糊匹配） */
    @PostMapping("/candidates")
    @RequirePerm(Perm.EVAL_VIEW)
    public R<PageResult<Map<String, Object>>> candidates(@RequestBody CandidateReq req) {
        var ids = Json.arr();
        if (req.agentIds != null) req.agentIds.forEach(ids::add);
        var w = evalService.candidateQuery(UserContext.spaceId(), ids, req.filters);
        w.orderByDesc(ChatSession::getLastEventAt);
        Page<ChatSession> p = sessionMapper.selectPage(new Page<>(Math.max(1, req.page), Math.min(Math.max(1, req.size), 50)), w);
        Map<String, String> agentNames = new HashMap<>();
        return R.ok(PageResult.of(p, s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("title", s.getTitle());
            m.put("agentId", s.getAgentId());
            m.put("agentName", agentNames.computeIfAbsent(s.getAgentId(), k -> {
                Agent a = agentMapper.selectById(k);
                return a == null ? "已删除的 Agent" : a.getName();
            }));
            m.put("agentVersion", s.getAgentVersion());
            m.put("source", s.getSource());
            m.put("status", s.getStatus());
            m.put("modelRef", s.getModelRef());
            m.put("lastEventAt", s.getLastEventAt());
            m.put("createdAt", s.getCreatedAt());
            m.putAll(evalService.preview(s.getId()));
            if (req.taskId != null) {
                EvalResult last = resultMapper.selectOne(new LambdaQueryWrapper<EvalResult>().eq(EvalResult::getTaskId, req.taskId)
                        .eq(EvalResult::getSessionId, s.getId()).eq(EvalResult::getStatus, "success").orderByDesc(EvalResult::getId).last("limit 1"));
                m.put("lastScore", last == null ? null : last.getScore());
            }
            return m;
        }));
    }

    // ------------------------------------------------------------------ 辅助

    private EvalTask load(Long id) {
        EvalTask t = taskMapper.selectById(id);
        if (t == null || !UserContext.spaceId().equals(t.getSpaceId())) throw BizException.notFound("评测任务");
        return t;
    }

    private EvalResult loadResult(Long id) {
        EvalResult r = resultMapper.selectById(id);
        if (r == null || !UserContext.spaceId().equals(r.getSpaceId())) throw BizException.notFound("评测结果");
        return r;
    }
}
