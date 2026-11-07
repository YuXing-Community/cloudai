package com.cloudai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cloudai.common.BizException;
import com.cloudai.common.Json;
import com.cloudai.config.AppProperties;
import com.cloudai.entity.Agent;
import com.cloudai.entity.ChatSession;
import com.cloudai.entity.EvalEntities.EvalResult;
import com.cloudai.entity.EvalEntities.EvalRun;
import com.cloudai.entity.EvalEntities.EvalTask;
import com.cloudai.entity.SessionEvent;
import com.cloudai.mapper.Mappers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * Agent 评测：按任务筛选会话（执行记录）→ 可选用指定模型重放执行 → 评测模型读取完整执行轨迹打分并给出修改意见 → 结果按会话落表。
 * 触发方式：手动、定时（cron）、实时（会话执行完成后自动评测）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalService {
    /** 评测会话的 source，不进入候选、不出现在默认会话列表 */
    public static final String EVAL_SOURCE = "eval";
    /** 可评测的会话状态（idle 没有消息、running 尚未结束） */
    public static final List<String> FINISHED = List.of("completed", "failed", "stopped");
    private static final int MAX_SESSIONS_PER_RUN = 500;
    private static final int MAX_REPLAY_INPUTS = 20;
    private static final int TRANSCRIPT_LIMIT = 60_000;

    /** 维度与权重（综合得分按权重计算，保证口径一致） */
    public static final List<String[]> DIMENSIONS = List.of(
            new String[]{"task_completion", "任务完成度", "30", "是否理解并完成了用户的真实诉求"},
            new String[]{"accuracy", "准确性", "25", "事实、数据、结论是否正确，有无幻觉或与工具结果矛盾"},
            new String[]{"tool_usage", "工具使用", "15", "工具选择、参数、调用次数是否合理，是否正确利用了工具结果；无需工具且未使用时给满分"},
            new String[]{"instruction_following", "指令遵循", "15", "是否遵守 Agent 设定中的角色、流程、边界与输出格式要求"},
            new String[]{"response_quality", "回复质量", "15", "结构清晰、表达准确、简洁有用、语气得体"});

    private final EvalTaskMapper taskMapper;
    private final EvalRunMapper runMapper;
    private final EvalResultMapper resultMapper;
    private final ChatSessionMapper sessionMapper;
    private final SessionEventMapper eventMapper;
    private final AgentMapper agentMapper;
    private final AgentResolver resolver;
    private final ModelService modelService;
    private final ExecutorClient executor;
    private final RunService runService;
    private final AppProperties props;

    /** 批次协调线程 */
    private final ExecutorService runPool = Executors.newFixedThreadPool(4, r -> daemon(r, "eval-run"));
    /** 单会话评测线程（评测模型调用 / 重放执行） */
    private final ExecutorService itemPool = Executors.newFixedThreadPool(6, r -> daemon(r, "eval-item"));
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<Long> stopping = ConcurrentHashMap.newKeySet();

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    @PreDestroy
    void shutdown() {
        runPool.shutdownNow();
        itemPool.shutdownNow();
    }

    /** 服务重启后，未完成的批次与结果标记为中断 */
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        resultMapper.update(null, new LambdaUpdateWrapper<EvalResult>().in(EvalResult::getStatus, "pending", "running")
                .set(EvalResult::getStatus, "failed").set(EvalResult::getError, "服务重启，评测中断")
                .set(EvalResult::getFinishedAt, LocalDateTime.now()));
        runMapper.update(null, new LambdaUpdateWrapper<EvalRun>().eq(EvalRun::getStatus, "running")
                .set(EvalRun::getStatus, "failed").set(EvalRun::getError, "服务重启，评测中断")
                .set(EvalRun::getFinishedAt, LocalDateTime.now()));
    }

    // ------------------------------------------------------------------ 候选会话（执行记录）

    /**
     * 按 Agent 与筛选条件构造会话查询。
     * filters: {timeMode: all/range/recent, from, to, recentHours, sessionIds:[], keyword, sources:[], statuses:[]}
     */
    public LambdaQueryWrapper<ChatSession> candidateQuery(String spaceId, JsonNode agentIds, JsonNode f) {
        var w = new LambdaQueryWrapper<ChatSession>().eq(ChatSession::getSpaceId, spaceId).ne(ChatSession::getSource, EVAL_SOURCE);
        List<String> agents = texts(agentIds);
        if (!agents.isEmpty()) w.in(ChatSession::getAgentId, agents);
        f = f == null ? Json.obj() : f;

        List<String> statuses = texts(f.path("statuses")).stream().filter(FINISHED::contains).toList();
        w.in(ChatSession::getStatus, statuses.isEmpty() ? FINISHED : statuses);
        List<String> sources = texts(f.path("sources"));
        if (!sources.isEmpty()) w.in(ChatSession::getSource, sources);

        switch (f.path("timeMode").asText("all")) {
            case "range" -> {
                LocalDateTime from = time(f.path("from").asText(null)), to = time(f.path("to").asText(null));
                if (from != null) w.ge(ChatSession::getLastEventAt, from);
                if (to != null) w.le(ChatSession::getLastEventAt, to);
            }
            case "recent" -> w.ge(ChatSession::getLastEventAt, LocalDateTime.now().minusHours(Math.max(1, f.path("recentHours").asInt(24))));
            default -> { }
        }
        List<String> ids = texts(f.path("sessionIds")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (!ids.isEmpty()) w.and(x -> {
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) x.or();
                x.likeRight(ChatSession::getId, ids.get(i));
            }
        });
        String kw = f.path("keyword").asText("").trim();
        if (!kw.isEmpty()) w.and(x -> x.like(ChatSession::getTitle, kw).or().apply(
                "EXISTS (SELECT 1 FROM session_event e WHERE e.session_id = chat_session.id AND e.type IN ('input','agent') "
                        + "AND e.content LIKE CONCAT('%', {0}, '%'))", kw));
        return w;
    }

    /** 会话首条用户输入与最后一条回复（列表预览） */
    public Map<String, String> preview(String sessionId) {
        SessionEvent first = eventMapper.selectOne(new LambdaQueryWrapper<SessionEvent>().eq(SessionEvent::getSessionId, sessionId)
                .eq(SessionEvent::getType, "input").orderByAsc(SessionEvent::getSeq).last("limit 1"));
        SessionEvent last = eventMapper.selectOne(new LambdaQueryWrapper<SessionEvent>().eq(SessionEvent::getSessionId, sessionId)
                .eq(SessionEvent::getType, "agent").orderByDesc(SessionEvent::getSeq).last("limit 1"));
        Map<String, String> m = new HashMap<>();
        m.put("input", first == null ? null : RunService.truncate(first.getContent(), 200));
        m.put("reply", last == null ? null : RunService.truncate(last.getContent(), 200));
        return m;
    }

    // ------------------------------------------------------------------ 执行

    public boolean isRunning(Long taskId) {
        return inFlight.contains(taskId);
    }

    /**
     * 启动一次评测批次。
     * @param sessionIds 指定会话（实时模式）；为 null 时按任务筛选条件取会话
     */
    public EvalRun start(EvalTask t, String trigger, Long userId, List<String> sessionIds) {
        if (!inFlight.add(t.getId())) throw new BizException("该评测任务正在执行中，请等待完成");
        try {
            List<ChatSession> sessions;
            if (sessionIds != null) {
                sessions = sessionIds.isEmpty() ? List.of() : sessionMapper.selectBatchIds(sessionIds);
            } else {
                var w = candidateQuery(t.getSpaceId(), t.getAgentIds(), t.getFilters());
                if (Boolean.TRUE.equals(t.getSkipEvaluated())) w.apply(notEvaluatedSql(), t.getId());
                w.orderByDesc(ChatSession::getLastEventAt).last("limit " + MAX_SESSIONS_PER_RUN);
                sessions = sessionMapper.selectList(w);
            }
            sessions = sessions.stream().filter(s -> !runService.isRunning(s.getId())).toList();

            EvalRun run = new EvalRun();
            if (sessions.isEmpty()) {
                // 没有需要评测的会话：不落批次，避免定时任务每次触发都产生空记录
                inFlight.remove(t.getId());
                run.setTaskId(t.getId());
                run.setTriggerType(trigger);
                run.setStatus("skipped");
                run.setTotal(0);
                return run;
            }
            run.setTaskId(t.getId());
            run.setSpaceId(t.getSpaceId());
            run.setTriggerType(trigger);
            run.setStatus("running");
            run.setTotal(sessions.size());
            run.setDone(0);
            run.setFailed(0);
            run.setPassCount(0);
            run.setCreatorId(userId);
            run.setStartedAt(LocalDateTime.now());
            runMapper.insert(run);

            List<EvalResult> items = new ArrayList<>();
            for (ChatSession s : sessions) {
                EvalResult r = new EvalResult();
                r.setSpaceId(t.getSpaceId());
                r.setTaskId(t.getId());
                r.setRunId(run.getId());
                r.setSessionId(s.getId());
                r.setAgentId(s.getAgentId());
                r.setAgentVersion(s.getAgentVersion());
                r.setSessionTitle(s.getTitle());
                r.setSessionModelRef(s.getModelRef());
                r.setJudgeModelRef(t.getJudgeModelRef());
                r.setSessionLastEventAt(s.getLastEventAt());
                r.setStatus("pending");
                r.setJudgeInputTokens(0);
                r.setJudgeOutputTokens(0);
                r.setDurationMs(0L);
                resultMapper.insert(r);
                items.add(r);
            }
            t.setLastStatus("running");
            t.setLastRunAt(LocalDateTime.now());
            taskMapper.update(null, new LambdaUpdateWrapper<EvalTask>().eq(EvalTask::getId, t.getId())
                    .set(EvalTask::getLastStatus, "running").set(EvalTask::getLastRunAt, t.getLastRunAt()));
            runPool.submit(() -> execute(t, run, items));
            return run;
        } catch (RuntimeException e) {
            inFlight.remove(t.getId());
            throw e;
        }
    }

    public void stop(EvalRun run) {
        if ("running".equals(run.getStatus())) stopping.add(run.getId());
    }

    private void execute(EvalTask t, EvalRun run, List<EvalResult> items) {
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (EvalResult r : items) {
                fs.add(itemPool.submit(() -> {
                    if (stopping.contains(run.getId())) {
                        fail(r, "评测已停止");
                        return;
                    }
                    evaluate(t, r);
                }));
            }
            for (Future<?> f : fs) {
                try {
                    f.get();
                } catch (Exception e) {
                    log.warn("eval item failed: {}", e.getMessage());
                }
            }
            finish(run, stopping.contains(run.getId()) ? "stopped" : null);
        } catch (Exception e) {
            log.error("eval run {} failed", run.getId(), e);
            run.setError(e.getMessage());
            finish(run, "failed");
        } finally {
            stopping.remove(run.getId());
            inFlight.remove(t.getId());
        }
    }

    /** 汇总批次统计并回写任务状态 */
    public void finish(EvalRun run, String forceStatus) {
        List<EvalResult> rs = resultMapper.selectList(new LambdaQueryWrapper<EvalResult>().eq(EvalResult::getRunId, run.getId()));
        int done = 0, failed = 0, pass = 0;
        BigDecimal sum = BigDecimal.ZERO;
        for (EvalResult r : rs) {
            if ("success".equals(r.getStatus())) {
                done++;
                sum = sum.add(r.getScore() == null ? BigDecimal.ZERO : r.getScore());
                if (Boolean.TRUE.equals(r.getPassed())) pass++;
            } else if ("failed".equals(r.getStatus())) failed++;
        }
        run.setTotal(rs.size());
        run.setDone(done);
        run.setFailed(failed);
        run.setPassCount(pass);
        run.setAvgScore(done == 0 ? null : sum.divide(BigDecimal.valueOf(done), 2, RoundingMode.HALF_UP));
        String status = forceStatus != null ? forceStatus : failed == 0 ? "success" : done == 0 ? "failed" : "partial";
        run.setStatus(status);
        run.setFinishedAt(LocalDateTime.now());
        runMapper.updateById(run);
        taskMapper.update(null, new LambdaUpdateWrapper<EvalTask>().eq(EvalTask::getId, run.getTaskId())
                .set(EvalTask::getLastStatus, status).set(EvalTask::getLastRunAt, LocalDateTime.now()));
    }

    /** 重新评测单条结果（原地覆盖） */
    public void rerun(EvalResult r) {
        BizException.check(!"running".equals(r.getStatus()) && !"pending".equals(r.getStatus()), "该结果正在评测中");
        EvalTask t = taskMapper.selectById(r.getTaskId());
        if (t == null) throw BizException.notFound("评测任务");
        ChatSession s = sessionMapper.selectById(r.getSessionId());
        if (s == null) throw BizException.notFound("会话");
        r.setStatus("pending");
        r.setJudgeModelRef(t.getJudgeModelRef());
        r.setSessionLastEventAt(s.getLastEventAt());
        r.setError(null);
        resultMapper.updateById(r);
        itemPool.submit(() -> {
            evaluate(t, r);
            EvalRun run = runMapper.selectById(r.getRunId());
            if (run != null && !"running".equals(run.getStatus())) finish(run, null);
        });
    }

    /** 评测单个会话，结果落表 */
    private void evaluate(EvalTask t, EvalResult r) {
        long t0 = System.currentTimeMillis();
        r.setStatus("running");
        resultMapper.update(null, new LambdaUpdateWrapper<EvalResult>().eq(EvalResult::getId, r.getId()).set(EvalResult::getStatus, "running"));
        try {
            ChatSession s = sessionMapper.selectById(r.getSessionId());
            if (s == null) throw new BizException("会话已删除");
            Agent agent = agentMapper.selectById(s.getAgentId());
            if (agent == null) throw new BizException("Agent 已删除");

            String target = s.getId();
            String reference = null;
            if (t.getReplayModelRef() != null && !t.getReplayModelRef().isBlank()) {
                ChatSession rs = replay(t, s, agent);
                r.setReplaySessionId(rs.getId());
                resultMapper.update(null, new LambdaUpdateWrapper<EvalResult>().eq(EvalResult::getId, r.getId())
                        .set(EvalResult::getReplaySessionId, rs.getId()));
                target = rs.getId();
                reference = finalReplies(s.getId());
            }

            List<SessionEvent> events = eventMapper.selectList(new LambdaQueryWrapper<SessionEvent>()
                    .eq(SessionEvent::getSessionId, target).orderByAsc(SessionEvent::getSeq));
            BizException.check(events.stream().anyMatch(e -> "input".equals(e.getType())), "会话没有用户输入，无法评测");
            ChatSession ts = target.equals(s.getId()) ? s : sessionMapper.selectById(target);
            JsonNode judged = judge(t, agent, ts, events, reference);

            ArrayNode dims = Json.arr();
            Map<String, JsonNode> byKey = new HashMap<>();
            for (JsonNode d : judged.path("dimensions")) byKey.put(d.path("key").asText(), d);
            double weighted = 0;
            int weightSum = 0;
            for (String[] def : DIMENSIONS) {
                JsonNode d = byKey.get(def[0]);
                if (d == null || !d.path("score").isNumber()) continue;
                double sc = clamp(d.path("score").asDouble());
                dims.add(Json.obj().put("key", def[0]).put("name", def[1]).put("weight", Integer.parseInt(def[2]))
                        .put("score", sc).put("comment", d.path("comment").asText("")));
                weighted += sc * Integer.parseInt(def[2]);
                weightSum += Integer.parseInt(def[2]);
            }
            double score = weightSum > 0 ? weighted / weightSum : clamp(judged.path("score").asDouble(0));
            ArrayNode suggestions = Json.arr();
            for (JsonNode x : judged.path("suggestions")) if (!x.asText("").isBlank()) suggestions.add(x.asText());
            ArrayNode issues = Json.arr();
            for (JsonNode x : judged.path("issues")) {
                if (x.isTextual()) issues.add(Json.obj().put("severity", "medium").put("step", "").put("description", x.asText()));
                else if (x.isObject()) issues.add(Json.obj().put("severity", x.path("severity").asText("medium"))
                        .put("step", x.path("step").asText("")).put("description", x.path("description").asText("")));
            }

            r.setScore(BigDecimal.valueOf(score).setScale(1, RoundingMode.HALF_UP));
            r.setPassed(score >= (t.getPassScore() == null ? 60 : t.getPassScore()));
            r.setDimensions(dims);
            r.setSummary(judged.path("summary").asText(""));
            r.setIssues(issues);
            r.setSuggestions(suggestions);
            String improved = judged.path("improved_reply").asText("");
            r.setImprovedReply(improved.isBlank() ? null : improved);
            r.setJudgeInputTokens(judged.path("_usage").path("input_tokens").asInt());
            r.setJudgeOutputTokens(judged.path("_usage").path("output_tokens").asInt());
            r.setStatus("success");
            r.setError(null);
        } catch (Exception e) {
            log.warn("eval result {} failed: {}", r.getId(), e.getMessage());
            r.setStatus("failed");
            r.setError(e.getMessage() == null ? e.toString() : e.getMessage());
        }
        r.setDurationMs(System.currentTimeMillis() - t0);
        r.setFinishedAt(LocalDateTime.now());
        resultMapper.updateById(r);
    }

    private void fail(EvalResult r, String error) {
        r.setStatus("failed");
        r.setError(error);
        r.setFinishedAt(LocalDateTime.now());
        resultMapper.updateById(r);
    }

    /** 用指定模型把原会话的用户输入按顺序重新执行一遍，生成新会话（source=eval） */
    private ChatSession replay(EvalTask t, ChatSession orig, Agent agent) {
        List<SessionEvent> inputs = eventMapper.selectList(new LambdaQueryWrapper<SessionEvent>()
                .eq(SessionEvent::getSessionId, orig.getId()).eq(SessionEvent::getType, "input").orderByAsc(SessionEvent::getSeq));
        BizException.check(!inputs.isEmpty(), "会话没有用户输入，无法重放");
        ChatSession ns = runService.createSession(agent, new RunService.SessionOptions(
                "[评测重放] " + orig.getTitle(), EVAL_SOURCE, "eval:" + t.getId() + ":" + orig.getId(), t.getCreatorId(),
                t.getReplayModelRef(), orig.getRuntimeEnv(), orig.getAgentVersion()));
        for (SessionEvent in : inputs.subList(0, Math.min(inputs.size(), MAX_REPLAY_INPUTS))) {
            RunService.RunResult rr = runService.sendAndWait(ns, in.getContent(), EVAL_SOURCE, t.getCreatorId(), Duration.ofMinutes(15));
            if (!"success".equals(rr.status()) && rr.error() != null && rr.error().contains("执行超时")) break;
        }
        return ns;
    }

    private String finalReplies(String sessionId) {
        List<SessionEvent> evs = eventMapper.selectList(new LambdaQueryWrapper<SessionEvent>()
                .eq(SessionEvent::getSessionId, sessionId).in(SessionEvent::getType, "input", "agent").orderByAsc(SessionEvent::getSeq));
        StringBuilder sb = new StringBuilder();
        for (SessionEvent e : evs) {
            sb.append("input".equals(e.getType()) ? "【用户】" : "【原回复】").append(RunService.truncate(e.getContent(), 3000)).append("\n\n");
        }
        return RunService.truncate(sb.toString(), 15_000);
    }

    // ------------------------------------------------------------------ 评测模型

    private JsonNode judge(EvalTask t, Agent agent, ChatSession s, List<SessionEvent> events, String reference) {
        JsonNode cfg = resolver.config(agent, s.getAgentVersion());
        StringBuilder sys = new StringBuilder("""
                你是严格、客观的 AI Agent 质量评测专家。你会收到一个 Agent 的设定（系统提示词）以及一次完整会话的执行轨迹\
                （用户输入、模型思考、工具调用及结果、Agent 回复、错误）。请逐步检查 Agent 的执行过程与最终回复，按以下维度分别打 0-100 分：
                """);
        for (String[] d : DIMENSIONS) sys.append("- ").append(d[0]).append("（").append(d[1]).append("，权重 ").append(d[2]).append("%）：").append(d[3]).append("\n");
        sys.append("""

                评分要求：90+ 优秀，可直接交付；75-89 良好，有小瑕疵；60-74 及格，有明显问题；60 以下不合格。必须基于轨迹中的证据打分，不要臆测。
                修改意见（suggestions）必须具体指向回复内容本身：哪里说错/缺了什么/应该怎么改，避免空泛建议。
                """);
        if (t.getCriteria() != null && !t.getCriteria().isBlank()) {
            sys.append("\n本任务额外的评测标准（必须纳入评分）：\n").append(t.getCriteria().trim()).append("\n");
        }
        sys.append("""

                只输出一个 JSON 对象，不要输出任何其它内容，不要使用 Markdown 代码块。JSON 结构：
                {"dimensions":[{"key":"task_completion","score":0-100,"comment":"一句话评分依据"}, ...共 5 项，key 与上面一致],
                 "score":0-100,
                 "summary":"2-4 句评测结论",
                 "issues":[{"severity":"high|medium|low","step":"出问题的环节，如 第2轮回复 / 工具 Bash","description":"问题描述"}],
                 "suggestions":["针对模型回复内容的具体修改意见"],
                 "improved_reply":"按修改意见改写后、Agent 最后一轮应给出的完整回复（Markdown）；原回复已足够好时返回空字符串"}
                """);

        StringBuilder u = new StringBuilder();
        u.append("# Agent 设定\n名称：").append(agent.getName()).append("\n");
        if (agent.getDescription() != null) u.append("描述：").append(agent.getDescription()).append("\n");
        u.append("系统提示词：\n").append(RunService.truncate(cfg.path("prompt").asText(""), 6000)).append("\n\n");
        u.append("# 会话信息\n会话 ID：").append(s.getId()).append("\n执行模型：").append(s.getModelRef())
                .append("\n会话状态：").append(s.getStatus()).append("\n\n");
        u.append("# 执行轨迹\n").append(transcript(events)).append("\n");
        if (reference != null) {
            u.append("\n# 原会话内容（参考）\n以上轨迹是用另一模型重新执行同样用户输入得到的结果。以下是原会话的问答，供对比参考，评分对象是上面的重放轨迹：\n")
                    .append(reference).append("\n");
        }

        int maxTokens = 8192;
        try {
            Integer mt = modelService.resolve(t.getSpaceId(), t.getJudgeModelRef()).model().getMaxTokens();
            if (mt != null && mt > 0) maxTokens = Math.min(mt, 16384);
        } catch (BizException e) {
            throw new BizException("评测模型不可用：" + e.getMessage());
        }
        ObjectNode call = Json.obj();
        call.set("model", modelService.toExecutorSpec(modelService.resolve(t.getSpaceId(), t.getJudgeModelRef())));
        call.put("system", sys.toString());
        call.put("max_tokens", maxTokens);
        call.put("timeout", 300);
        ArrayNode msgs = Json.arr().add(Json.obj().put("role", "user").put("content", u.toString()));
        call.set("messages", msgs);

        int in = 0, out = 0;
        for (int attempt = 1; attempt <= 2; attempt++) {
            JsonNode res = executor.call("POST", "/llm/complete", call, 320);
            in += res.path("usage").path("input_tokens").asInt();
            out += res.path("usage").path("output_tokens").asInt();
            String text = res.path("text").asText("");
            JsonNode parsed = parseJson(text);
            if (parsed != null && parsed.has("dimensions")) {
                ((ObjectNode) parsed).set("_usage", Json.obj().put("input_tokens", in).put("output_tokens", out));
                return parsed;
            }
            msgs.add(Json.obj().put("role", "assistant").put("content", RunService.truncate(text, 4000)));
            msgs.add(Json.obj().put("role", "user").put("content", "你的输出不是有效的 JSON。请严格按要求的 JSON 结构重新输出，只输出 JSON。"));
        }
        throw new BizException("评测模型没有返回有效的 JSON");
    }

    private static JsonNode parseJson(String text) {
        if (text == null) return null;
        int s = text.indexOf('{'), e = text.lastIndexOf('}');
        if (s < 0 || e <= s) return null;
        try {
            JsonNode n = Json.M.readTree(text.substring(s, e + 1));
            return n != null && n.isObject() ? n : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /** 执行轨迹文本：用户输入 / 思考 / 工具调用与结果 / 回复 / 错误；超长时保留开头与结尾 */
    static String transcript(List<SessionEvent> events) {
        List<String> parts = new ArrayList<>();
        int i = 0;
        for (SessionEvent e : events) {
            String body = switch (e.getType()) {
                case "input" -> "用户输入：\n" + RunService.truncate(e.getContent(), 4000);
                case "agent" -> "Agent 回复：\n" + RunService.truncate(e.getContent(), 8000);
                case "thinking" -> "模型思考：\n" + RunService.truncate(e.getContent(), 1500);
                case "tool" -> "工具调用 " + e.getName() + "（" + e.getStatus() + "，" + e.getDurationMs() + "ms）\n参数：" + RunService.truncate(e.getContent(), 1500)
                        + "\n结果：" + RunService.truncate(e.getResult(), 3000);
                case "error" -> "错误：" + RunService.truncate(e.getContent(), 1500);
                default -> null;
            };
            if (body != null) parts.add("[" + (++i) + "] " + body);
        }
        String all = String.join("\n\n", parts);
        if (all.length() <= TRANSCRIPT_LIMIT) return all;
        // 保留开头 1/3 与结尾 2/3（最终回复在结尾）
        int head = TRANSCRIPT_LIMIT / 3, tail = TRANSCRIPT_LIMIT - head;
        return all.substring(0, head) + "\n\n……（中间 " + (all.length() - TRANSCRIPT_LIMIT) + " 字已省略）……\n\n" + all.substring(all.length() - tail);
    }

    // ------------------------------------------------------------------ 调度：定时 + 实时

    public LocalDateTime nextFire(EvalTask t, LocalDateTime after) {
        ZoneId zone = ZoneId.of(t.getTimezone() == null ? props.getTimezone() : t.getTimezone());
        ZonedDateTime n = ScheduleService.parse(t.getCron()).next(after.atZone(ZoneId.systemDefault()).withZoneSameInstant(zone));
        return n == null ? null : n.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    /** 本任务已成功评测过、且之后没有新消息的会话 */
    private static String notEvaluatedSql() {
        return "NOT EXISTS (SELECT 1 FROM eval_result r WHERE r.task_id = {0} AND r.session_id = chat_session.id "
                + "AND r.status = 'success' AND r.session_last_event_at >= chat_session.last_event_at)";
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 25_000)
    public void tick() {
        LocalDateTime now = LocalDateTime.now();
        for (EvalTask t : taskMapper.selectList(new LambdaQueryWrapper<EvalTask>().eq(EvalTask::getEnabled, true)
                .eq(EvalTask::getTriggerMode, "schedule").le(EvalTask::getNextFireAt, now))) {
            try {
                t.setNextFireAt(nextFire(t, now));
            } catch (Exception e) {
                t.setEnabled(false);
                log.warn("eval task {} cron invalid, disabled", t.getId());
            }
            taskMapper.updateById(t);
            if (isRunning(t.getId())) continue; // 上一批次未结束，本次跳过
            try {
                start(t, "schedule", t.getCreatorId(), null);
            } catch (Exception e) {
                log.warn("eval schedule {} start failed: {}", t.getId(), e.getMessage());
            }
        }

        for (EvalTask t : taskMapper.selectList(new LambdaQueryWrapper<EvalTask>().eq(EvalTask::getEnabled, true)
                .eq(EvalTask::getTriggerMode, "realtime"))) {
            if (isRunning(t.getId()) || t.getRealtimeSince() == null) continue;
            try {
                // 实时模式：开启之后新产生/更新的会话，执行结束后自动评测；评测过的会话有新消息时重新评测
                var w = candidateQuery(t.getSpaceId(), t.getAgentIds(), realtimeFilters(t.getFilters()));
                w.ge(ChatSession::getLastEventAt, t.getRealtimeSince())
                        .apply("NOT EXISTS (SELECT 1 FROM eval_result r WHERE r.task_id = {0} AND r.session_id = chat_session.id "
                                + "AND r.session_last_event_at >= chat_session.last_event_at)", t.getId())
                        .select(ChatSession::getId).orderByAsc(ChatSession::getLastEventAt).last("limit 50");
                List<String> ids = sessionMapper.selectList(w).stream().map(ChatSession::getId)
                        .filter(id -> !runService.isRunning(id)).toList();
                if (!ids.isEmpty()) start(t, "realtime", t.getCreatorId(), ids);
            } catch (Exception e) {
                log.warn("eval realtime {} failed: {}", t.getId(), e.getMessage());
            }
        }
    }

    /** 实时模式只用 Agent / 来源 / 状态 / 关键词条件，时间窗口由开启时间决定 */
    private static JsonNode realtimeFilters(JsonNode f) {
        ObjectNode n = Json.obj();
        if (f == null) return n;
        for (String k : new String[]{"sources", "statuses", "keyword"}) if (f.has(k)) n.set(k, f.get(k));
        return n;
    }

    // ------------------------------------------------------------------ 工具

    private static double clamp(double v) {
        return Math.max(0, Math.min(100, v));
    }

    public static List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) for (JsonNode n : arr) if (!n.asText("").isBlank()) out.add(n.asText());
        return out;
    }

    private static final DateTimeFormatter[] TIME_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME};

    public static LocalDateTime time(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        if (v.length() == 10) v += " 00:00:00";
        for (DateTimeFormatter f : TIME_FORMATS) {
            try {
                return LocalDateTime.parse(v, f);
            } catch (Exception ignored) {
            }
        }
        throw new BizException("时间格式无效：" + s);
    }
}
