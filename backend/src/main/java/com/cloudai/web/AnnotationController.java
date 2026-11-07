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
import com.cloudai.entity.EvalEntities.EvalTask;
import com.cloudai.mapper.Mappers.*;
import com.cloudai.service.EvalService;
import com.cloudai.service.SpaceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 人工打标：两种模式
 * - eval：基于模型评测结果进行人工复核打标（可筛选低分/不及格等）
 * - session：直接基于会话内容打标
 * 打标结果形成人工审核表 eval_annotation，每个标注人对同一目标一条记录。
 */
@RestController
@RequestMapping("/api/annotations")
@RequiredArgsConstructor
public class AnnotationController {
    public static final Map<String, String> LABELS = Map.of("good", "优秀", "acceptable", "合格", "bad", "不合格");

    private final EvalAnnotationMapper annotationMapper;
    private final EvalResultMapper resultMapper;
    private final EvalTaskMapper taskMapper;
    private final ChatSessionMapper sessionMapper;
    private final AgentMapper agentMapper;
    private final EvalService evalService;
    private final SpaceService spaceService;

    // ------------------------------------------------------------------ 待打标列表

    @GetMapping("/queue")
    @RequirePerm(Perm.EVAL_VIEW)
    public R<PageResult<Map<String, Object>>> queue(@RequestParam(defaultValue = "eval") String mode,
                                                    @RequestParam(required = false) Long taskId,
                                                    @RequestParam(required = false) String agentId,
                                                    @RequestParam(required = false) Boolean passed,
                                                    @RequestParam(required = false) BigDecimal minScore,
                                                    @RequestParam(required = false) BigDecimal maxScore,
                                                    @RequestParam(defaultValue = "all") String annotated,
                                                    @RequestParam(required = false) String q,
                                                    @RequestParam(required = false) String from,
                                                    @RequestParam(required = false) String to,
                                                    @RequestParam(defaultValue = "1") long page,
                                                    @RequestParam(defaultValue = "20") long size) {
        String sid = UserContext.spaceId();
        Map<String, String> agentNames = new HashMap<>();
        Page<?> pg = new Page<>(page, Math.min(size, 100));
        if ("eval".equals(mode)) {
            var w = new LambdaQueryWrapper<EvalResult>().eq(EvalResult::getSpaceId, sid).eq(EvalResult::getStatus, "success");
            if (taskId != null) w.eq(EvalResult::getTaskId, taskId);
            if (agentId != null && !agentId.isBlank()) w.eq(EvalResult::getAgentId, agentId);
            if (passed != null) w.eq(EvalResult::getPassed, passed);
            if (minScore != null) w.ge(EvalResult::getScore, minScore);
            if (maxScore != null) w.le(EvalResult::getScore, maxScore);
            if (from != null && !from.isBlank()) w.ge(EvalResult::getSessionLastEventAt, EvalService.time(from));
            if (to != null && !to.isBlank()) w.le(EvalResult::getSessionLastEventAt, EvalService.time(to));
            if ("yes".equals(annotated)) w.apply("EXISTS (SELECT 1 FROM eval_annotation a WHERE a.eval_result_id = eval_result.id)");
            if ("no".equals(annotated)) w.apply("NOT EXISTS (SELECT 1 FROM eval_annotation a WHERE a.eval_result_id = eval_result.id)");
            if (q != null && !q.isBlank()) w.and(x -> x.likeRight(EvalResult::getSessionId, q.trim()).or().like(EvalResult::getSessionTitle, q.trim()));
            w.orderByDesc(EvalResult::getId);
            Map<Long, String> taskNames = new HashMap<>();
            Page<EvalResult> p = resultMapper.selectPage(new Page<>(pg.getCurrent(), pg.getSize()), w);
            return R.ok(PageResult.of(p, r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("mode", "eval");
                m.put("evalResultId", r.getId());
                m.put("sessionId", r.getSessionId());
                m.put("replaySessionId", r.getReplaySessionId());
                m.put("title", r.getSessionTitle());
                m.put("agentId", r.getAgentId());
                m.put("agentName", agentName(agentNames, r.getAgentId()));
                m.put("taskId", r.getTaskId());
                m.put("taskName", taskNames.computeIfAbsent(r.getTaskId(), k -> {
                    EvalTask t = taskMapper.selectById(k);
                    return t == null ? "已删除的任务" : t.getName();
                }));
                m.put("modelRef", r.getSessionModelRef());
                m.put("judgeModelRef", r.getJudgeModelRef());
                m.put("modelScore", r.getScore());
                m.put("passed", r.getPassed());
                m.put("summary", r.getSummary());
                m.put("time", r.getSessionLastEventAt());
                putAnnotations(m, annotationMapper.selectList(new LambdaQueryWrapper<EvalAnnotation>()
                        .eq(EvalAnnotation::getEvalResultId, r.getId()).orderByDesc(EvalAnnotation::getUpdatedAt)));
                return m;
            }));
        }

        // session 模式：直接基于会话
        ObjectNode f = Json.obj();
        if ((from != null && !from.isBlank()) || (to != null && !to.isBlank())) {
            f.put("timeMode", "range");
            if (from != null) f.put("from", from);
            if (to != null) f.put("to", to);
        }
        var w = evalService.candidateQuery(sid, agentId == null || agentId.isBlank() ? Json.arr() : Json.arr().add(agentId), f);
        if (q != null && !q.isBlank()) {
            String kw = q.trim();
            w.and(x -> x.likeRight(ChatSession::getId, kw).or().like(ChatSession::getTitle, kw).or().apply(
                    "EXISTS (SELECT 1 FROM session_event e WHERE e.session_id = chat_session.id AND e.type IN ('input','agent') "
                            + "AND e.content LIKE CONCAT('%', {0}, '%'))", kw));
        }
        if ("yes".equals(annotated)) w.apply("EXISTS (SELECT 1 FROM eval_annotation a WHERE a.mode = 'session' AND a.session_id = chat_session.id)");
        if ("no".equals(annotated)) w.apply("NOT EXISTS (SELECT 1 FROM eval_annotation a WHERE a.mode = 'session' AND a.session_id = chat_session.id)");
        w.orderByDesc(ChatSession::getLastEventAt);
        Page<ChatSession> p = sessionMapper.selectPage(new Page<>(pg.getCurrent(), pg.getSize()), w);
        return R.ok(PageResult.of(p, s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mode", "session");
            m.put("sessionId", s.getId());
            m.put("title", s.getTitle());
            m.put("agentId", s.getAgentId());
            m.put("agentName", agentName(agentNames, s.getAgentId()));
            m.put("modelRef", s.getModelRef());
            m.put("source", s.getSource());
            m.put("status", s.getStatus());
            m.put("time", s.getLastEventAt());
            m.putAll(evalService.preview(s.getId()));
            putAnnotations(m, annotationMapper.selectList(new LambdaQueryWrapper<EvalAnnotation>()
                    .eq(EvalAnnotation::getMode, "session").eq(EvalAnnotation::getSessionId, s.getId())
                    .orderByDesc(EvalAnnotation::getUpdatedAt)));
            return m;
        }));
    }

    private void putAnnotations(Map<String, Object> m, List<EvalAnnotation> anns) {
        Long me = UserContext.userId();
        EvalAnnotation mine = anns.stream().filter(a -> me.equals(a.getAnnotatorId())).findFirst().orElse(null);
        m.put("annotationCount", anns.size());
        m.put("myAnnotation", mine == null ? null : brief(mine));
        m.put("latestAnnotation", anns.isEmpty() ? null : brief(anns.get(0)));
    }

    private Map<String, Object> brief(EvalAnnotation a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("score", a.getScore());
        m.put("label", a.getLabel());
        m.put("annotatorId", a.getAnnotatorId());
        m.put("updatedAt", a.getUpdatedAt());
        return m;
    }

    // ------------------------------------------------------------------ 单个目标的打标

    /** 目标的全部标注（含我的） */
    @GetMapping("/target")
    @RequirePerm(Perm.EVAL_VIEW)
    public R<Map<String, Object>> target(@RequestParam String mode, @RequestParam(required = false) Long evalResultId,
                                         @RequestParam(required = false) String sessionId) {
        var w = targetQuery(mode, evalResultId, sessionId);
        List<Map<String, Object>> all = new ArrayList<>();
        Map<String, Object> mine = null;
        for (EvalAnnotation a : annotationMapper.selectList(w.orderByDesc(EvalAnnotation::getUpdatedAt))) {
            Map<String, Object> row = row(a, new HashMap<>(), false);
            all.add(row);
            if (UserContext.userId().equals(a.getAnnotatorId())) mine = row;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mine", mine);
        out.put("all", all);
        return R.ok(out);
    }

    private LambdaQueryWrapper<EvalAnnotation> targetQuery(String mode, Long evalResultId, String sessionId) {
        var w = new LambdaQueryWrapper<EvalAnnotation>().eq(EvalAnnotation::getSpaceId, UserContext.spaceId()).eq(EvalAnnotation::getMode, mode);
        if ("eval".equals(mode)) {
            BizException.check(evalResultId != null, "缺少评测结果 id");
            w.eq(EvalAnnotation::getEvalResultId, evalResultId);
        } else {
            BizException.check(sessionId != null && !sessionId.isBlank(), "缺少会话 id");
            w.eq(EvalAnnotation::getSessionId, sessionId);
        }
        return w;
    }

    @Data
    public static class SaveReq {
        private String mode;
        private Long evalResultId;
        private String sessionId;
        private Integer score;
        private String label;
        private List<String> tags;
        private Boolean agreeWithModel;
        private String comment;
        private String correctedReply;
    }

    /** 保存我的标注（同一目标每人一条，重复提交即更新） */
    @PostMapping
    @RequirePerm(Perm.EVAL_ANNOTATE)
    public R<Map<String, Object>> save(@RequestBody SaveReq req) {
        String sid = UserContext.spaceId();
        BizException.check("eval".equals(req.mode) || "session".equals(req.mode), "打标模式无效");
        String sessionId = req.sessionId;
        String agentId;
        if ("eval".equals(req.mode)) {
            EvalResult r = req.evalResultId == null ? null : resultMapper.selectById(req.evalResultId);
            if (r == null || !sid.equals(r.getSpaceId())) throw BizException.notFound("评测结果");
            BizException.check("success".equals(r.getStatus()), "只能对评测成功的结果打标");
            sessionId = r.getSessionId();
            agentId = r.getAgentId();
        } else {
            ChatSession s = sessionId == null ? null : sessionMapper.selectById(sessionId);
            if (s == null || !sid.equals(s.getSpaceId())) throw BizException.notFound("会话");
            agentId = s.getAgentId();
        }
        BizException.check(req.score != null || req.label != null, "请至少填写评分或结论");
        BizException.check(req.score == null || (req.score >= 0 && req.score <= 100), "评分须在 0-100 之间");
        BizException.check(req.label == null || LABELS.containsKey(req.label), "结论无效");
        BizException.check(req.tags == null || req.tags.size() <= 20, "标签最多 20 个");

        EvalAnnotation a = annotationMapper.selectOne(targetQuery(req.mode, req.evalResultId, sessionId)
                .eq(EvalAnnotation::getAnnotatorId, UserContext.userId()).last("limit 1"));
        boolean create = a == null;
        if (create) {
            a = new EvalAnnotation();
            a.setSpaceId(sid);
            a.setMode(req.mode);
            a.setEvalResultId("eval".equals(req.mode) ? req.evalResultId : null);
            a.setSessionId(sessionId);
            a.setAgentId(agentId);
            a.setAnnotatorId(UserContext.userId());
        }
        a.setScore(req.score);
        a.setLabel(req.label);
        var tags = Json.arr();
        if (req.tags != null) req.tags.stream().filter(t -> t != null && !t.isBlank()).map(t -> t.trim().length() > 32 ? t.trim().substring(0, 32) : t.trim())
                .distinct().forEach(tags::add);
        a.setTags(tags);
        a.setAgreeWithModel("eval".equals(req.mode) ? req.agreeWithModel : null);
        a.setComment(req.comment);
        a.setCorrectedReply(req.correctedReply == null || req.correctedReply.isBlank() ? null : req.correctedReply);
        if (create) annotationMapper.insert(a);
        else annotationMapper.updateById(a);
        return R.ok(row(annotationMapper.selectById(a.getId()), new HashMap<>(), false));
    }

    @DeleteMapping("/{id}")
    @RequirePerm(Perm.EVAL_ANNOTATE)
    public R<Void> delete(@PathVariable Long id) {
        EvalAnnotation a = annotationMapper.selectById(id);
        if (a == null || !UserContext.spaceId().equals(a.getSpaceId())) throw BizException.notFound("标注");
        BizException.check(UserContext.userId().equals(a.getAnnotatorId()) || UserContext.has(Perm.EVAL_EDIT), "只能删除自己的标注");
        annotationMapper.deleteById(id);
        return R.ok();
    }

    // ------------------------------------------------------------------ 人工审核表

    private LambdaQueryWrapper<EvalAnnotation> listQuery(String mode, String agentId, String label, Long taskId, String q) {
        var w = new LambdaQueryWrapper<EvalAnnotation>().eq(EvalAnnotation::getSpaceId, UserContext.spaceId());
        if (mode != null && !mode.isBlank()) w.eq(EvalAnnotation::getMode, mode);
        if (agentId != null && !agentId.isBlank()) w.eq(EvalAnnotation::getAgentId, agentId);
        if (label != null && !label.isBlank()) w.eq(EvalAnnotation::getLabel, label);
        if (taskId != null) w.apply("eval_result_id IN (SELECT r.id FROM eval_result r WHERE r.task_id = {0})", taskId);
        if (q != null && !q.isBlank()) w.and(x -> x.likeRight(EvalAnnotation::getSessionId, q.trim()).or().like(EvalAnnotation::getComment, q.trim()));
        return w.orderByDesc(EvalAnnotation::getUpdatedAt);
    }

    @GetMapping
    @RequirePerm(Perm.EVAL_VIEW)
    public R<PageResult<Map<String, Object>>> list(@RequestParam(required = false) String mode,
                                                   @RequestParam(required = false) String agentId,
                                                   @RequestParam(required = false) String label,
                                                   @RequestParam(required = false) Long taskId,
                                                   @RequestParam(required = false) String q,
                                                   @RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "20") long size) {
        Page<EvalAnnotation> p = annotationMapper.selectPage(new Page<>(page, Math.min(size, 100)), listQuery(mode, agentId, label, taskId, q));
        Map<String, Object> cache = new HashMap<>();
        return R.ok(PageResult.of(p, a -> row(a, cache, true)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> row(EvalAnnotation a, Map<String, Object> cache, boolean withTarget) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("mode", a.getMode());
        m.put("evalResultId", a.getEvalResultId());
        m.put("sessionId", a.getSessionId());
        m.put("agentId", a.getAgentId());
        m.put("score", a.getScore());
        m.put("label", a.getLabel());
        m.put("tags", a.getTags());
        m.put("agreeWithModel", a.getAgreeWithModel());
        m.put("comment", a.getComment());
        m.put("correctedReply", a.getCorrectedReply());
        m.put("annotator", cache.computeIfAbsent("u" + a.getAnnotatorId(), k -> spaceService.userBrief(a.getAnnotatorId())));
        m.put("mine", UserContext.userId().equals(a.getAnnotatorId()));
        m.put("createdAt", a.getCreatedAt());
        m.put("updatedAt", a.getUpdatedAt());
        if (withTarget) {
            m.put("agentName", a.getAgentId() == null ? null : cache.computeIfAbsent("a" + a.getAgentId(), k -> {
                Agent ag = agentMapper.selectById(a.getAgentId());
                return ag == null ? "已删除的 Agent" : ag.getName();
            }));
            Map<String, Object> sess = (Map<String, Object>) cache.computeIfAbsent("s" + a.getSessionId(), k -> {
                ChatSession s = sessionMapper.selectById(a.getSessionId());
                Map<String, Object> x = new HashMap<>();
                x.put("title", s == null ? "已删除的会话" : s.getTitle());
                x.put("modelRef", s == null ? null : s.getModelRef());
                return x;
            });
            m.put("title", sess.get("title"));
            m.put("modelRef", sess.get("modelRef"));
            if (a.getEvalResultId() != null) {
                EvalResult r = resultMapper.selectById(a.getEvalResultId());
                m.put("modelScore", r == null ? null : r.getScore());
                m.put("taskId", r == null ? null : r.getTaskId());
                m.put("judgeModelRef", r == null ? null : r.getJudgeModelRef());
            }
        }
        return m;
    }

    /** 审核统计：数量、结论分布、平均分、与模型评测的一致率（人工分与模型分相差 ≤15 分视为一致） */
    @GetMapping("/stats")
    @RequirePerm(Perm.EVAL_VIEW)
    public R<Map<String, Object>> stats(@RequestParam(required = false) String mode) {
        List<EvalAnnotation> all = annotationMapper.selectList(listQuery(mode, null, null, null, null));
        Map<String, Integer> labels = new LinkedHashMap<>();
        LABELS.keySet().forEach(k -> labels.put(k, 0));
        double sum = 0;
        int scored = 0, compared = 0, agree = 0;
        for (EvalAnnotation a : all) {
            if (a.getLabel() != null) labels.merge(a.getLabel(), 1, Integer::sum);
            if (a.getScore() != null) {
                sum += a.getScore();
                scored++;
            }
            if (a.getEvalResultId() != null) {
                EvalResult r = resultMapper.selectById(a.getEvalResultId());
                if (r != null && r.getScore() != null) {
                    if (a.getAgreeWithModel() != null) {
                        compared++;
                        if (a.getAgreeWithModel()) agree++;
                    } else if (a.getScore() != null) {
                        compared++;
                        if (Math.abs(r.getScore().doubleValue() - a.getScore()) <= 15) agree++;
                    }
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", all.size());
        m.put("labels", labels);
        m.put("avgScore", scored == 0 ? null : Math.round(sum * 10 / scored) / 10.0);
        m.put("agreementRate", compared == 0 ? null : Math.round(agree * 1000.0 / compared) / 10.0);
        m.put("comparedCount", compared);
        return R.ok(m);
    }

    /** 导出人工审核表 CSV（Excel 可直接打开） */
    @GetMapping("/export")
    @RequirePerm(Perm.EVAL_VIEW)
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String mode, @RequestParam(required = false) String agentId,
                                         @RequestParam(required = false) String label, @RequestParam(required = false) Long taskId,
                                         @RequestParam(required = false) String q) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("标注ID,打标模式,会话ID,会话标题,Agent,执行模型,评测任务ID,评测模型,模型评分,人工评分,人工结论,认可模型结论,问题标签,人工意见,修正回复,标注人,标注时间\n");
        Map<String, Object> cache = new HashMap<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for (EvalAnnotation a : annotationMapper.selectList(listQuery(mode, agentId, label, taskId, q).last("limit 10000"))) {
            Map<String, Object> r = row(a, cache, true);
            List<String> tags = new ArrayList<>();
            if (a.getTags() != null) a.getTags().forEach(t -> tags.add(t.asText()));
            Object annotator = r.get("annotator");
            String who = annotator instanceof Map<?, ?> u ? String.valueOf(((Map<?, ?>) u).get("username")) : "";
            sb.append(String.join(",", List.of(
                    csv(a.getId()), csv("eval".equals(a.getMode()) ? "基于模型评测" : "直接基于会话"), csv(a.getSessionId()), csv(r.get("title")),
                    csv(r.get("agentName")), csv(r.get("modelRef")), csv(r.get("taskId")), csv(r.get("judgeModelRef")), csv(r.get("modelScore")),
                    csv(a.getScore()), csv(a.getLabel() == null ? null : LABELS.get(a.getLabel())),
                    csv(a.getAgreeWithModel() == null ? null : a.getAgreeWithModel() ? "是" : "否"), csv(String.join("、", tags)),
                    csv(a.getComment()), csv(a.getCorrectedReply()), csv(who),
                    csv(a.getUpdatedAt() == null ? null : a.getUpdatedAt().format(fmt))))).append("\n");
        }
        String fn = URLEncoder.encode("人工审核表-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".csv", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fn)
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String csv(Object v) {
        if (v == null) return "";
        String s = v instanceof JsonNode n ? n.asText() : String.valueOf(v);
        // 防止 Excel 把内容当公式执行
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !(v instanceof Number)) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private String agentName(Map<String, String> cache, String id) {
        return cache.computeIfAbsent(id, k -> {
            Agent a = agentMapper.selectById(k);
            return a == null ? "已删除的 Agent" : a.getName();
        });
    }
}
