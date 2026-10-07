package com.cloudai.web;

import com.cloudai.auth.Perm;
import com.cloudai.auth.RequirePerm;
import com.cloudai.auth.UserContext;
import com.cloudai.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.*;

/** 用量：数据来自 agent_run（每次 Agent 执行一行）与 chat_session */
@RestController
@RequestMapping("/api/usage")
@RequiredArgsConstructor
@RequirePerm(Perm.USAGE_VIEW)
public class UsageController {
    private final JdbcTemplate jdbc;

    @GetMapping("/summary")
    public R<Map<String, Object>> summary(@RequestParam(required = false) String agentId,
                                          @RequestParam(defaultValue = "day") String granularity,
                                          @RequestParam(defaultValue = "7d") String range,
                                          @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to) {
        String sid = UserContext.spaceId();
        LocalDate end = to != null && !to.isBlank() ? LocalDate.parse(to) : LocalDate.now();
        LocalDate start;
        if (from != null && !from.isBlank()) start = LocalDate.parse(from);
        else {
            int days = switch (range) {
                case "1d" -> 1;
                case "14d" -> 14;
                case "30d" -> 30;
                default -> 7;
            };
            start = end.minusDays(days - 1);
        }
        LocalDateTime s = start.atStartOfDay(), e = end.plusDays(1).atStartOfDay();
        boolean byAgent = agentId != null && !agentId.isBlank();
        String agentCond = byAgent ? " AND r.agent_id = ?" : "";
        List<Object> base = new ArrayList<>(List.of(sid, s, e));
        if (byAgent) base.add(agentId);
        Object[] args = base.toArray();

        // 汇总
        Map<String, Object> tot = jdbc.queryForMap("""
                SELECT COALESCE(SUM(r.input_tokens),0) inp, COALESCE(SUM(r.output_tokens),0) outp, COALESCE(SUM(r.cache_read),0) cache,
                       COALESCE(SUM(r.cost),0) cost, COUNT(*) runs,
                       SUM(CASE WHEN r.cost IS NULL THEN 1 ELSE 0 END) skipped_runs,
                       COUNT(DISTINCT CASE WHEN r.cost IS NULL THEN r.model_ref END) skipped_models,
                       SUM(CASE WHEN r.trigger_type IN ('web','debug','feishu','share','webhook') THEN 1 ELSE 0 END) user_turns,
                       COALESCE(SUM(JSON_LENGTH(COALESCE(r.skills_used, JSON_ARRAY()))),0) skill_uses
                FROM agent_run r WHERE r.space_id = ? AND r.started_at >= ? AND r.started_at < ?""" + agentCond, args);
        List<Object> sArgs = new ArrayList<>(List.of(sid, s, e));
        if (byAgent) sArgs.add(agentId);
        Long sessions = jdbc.queryForObject("SELECT COUNT(*) FROM chat_session r WHERE r.space_id = ? AND r.created_at >= ? AND r.created_at < ? AND r.source <> 'debug'"
                + agentCond, Long.class, sArgs.toArray());

        Map<String, Object> totals = new LinkedHashMap<>();
        long in = num(tot.get("inp")), out = num(tot.get("outp")), cache = num(tot.get("cache"));
        totals.put("cost", tot.get("cost") == null ? BigDecimal.ZERO : tot.get("cost"));
        totals.put("skippedModels", num(tot.get("skipped_models")));
        totals.put("skippedRuns", num(tot.get("skipped_runs")));
        totals.put("tokens", in + out);
        totals.put("inputTokens", in);
        totals.put("outputTokens", out);
        totals.put("cacheTokens", cache);
        totals.put("sessions", sessions == null ? 0 : sessions);
        totals.put("turns", num(tot.get("user_turns")));
        totals.put("runs", num(tot.get("runs")));
        totals.put("skillUses", num(tot.get("skill_uses")));

        // 每日序列
        List<Map<String, Object>> daily = jdbc.queryForList("""
                SELECT DATE(r.started_at) d, COALESCE(SUM(r.input_tokens + r.output_tokens),0) tokens, COALESCE(SUM(r.cost),0) cost,
                       SUM(CASE WHEN r.trigger_type IN ('web','debug','feishu','share','webhook') THEN 1 ELSE 0 END) turns
                FROM agent_run r WHERE r.space_id = ? AND r.started_at >= ? AND r.started_at < ?""" + agentCond + " GROUP BY DATE(r.started_at)", args);
        List<Map<String, Object>> sDaily = jdbc.queryForList("SELECT DATE(r.created_at) d, COUNT(*) n FROM chat_session r WHERE r.space_id = ? AND r.created_at >= ? AND r.created_at < ? AND r.source <> 'debug'"
                + agentCond + " GROUP BY DATE(r.created_at)", sArgs.toArray());
        Map<LocalDate, Map<String, Object>> byDay = new TreeMap<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", d.toString());
            m.put("tokens", 0L);
            m.put("cost", BigDecimal.ZERO);
            m.put("sessions", 0L);
            m.put("turns", 0L);
            byDay.put(d, m);
        }
        for (var r : daily) {
            var m = byDay.get(toDate(r.get("d")));
            if (m == null) continue;
            m.put("tokens", num(r.get("tokens")));
            m.put("cost", r.get("cost"));
            m.put("turns", num(r.get("turns")));
        }
        for (var r : sDaily) {
            var m = byDay.get(toDate(r.get("d")));
            if (m != null) m.put("sessions", num(r.get("n")));
        }
        List<Map<String, Object>> series = new ArrayList<>(byDay.values());
        if ("week".equals(granularity)) series = toWeeks(series);

        // Agent × 模型 排行
        List<Map<String, Object>> am = jdbc.queryForList("""
                SELECT r.agent_id, a.name, a.avatar, r.model_ref, COALESCE(SUM(r.input_tokens + r.output_tokens),0) tokens,
                       SUM(r.cost) cost, SUM(CASE WHEN r.cost IS NULL THEN 1 ELSE 0 END) skipped,
                       COUNT(DISTINCT r.session_id) sessions, COUNT(*) turns
                FROM agent_run r LEFT JOIN agent a ON a.id = r.agent_id
                WHERE r.space_id = ? AND r.started_at >= ? AND r.started_at < ?""" + agentCond + " GROUP BY r.agent_id, a.name, a.avatar, r.model_ref", args);
        Map<String, Map<String, Object>> agents = new LinkedHashMap<>();
        for (var r : am) {
            String aid = String.valueOf(r.get("agent_id"));
            var g = agents.computeIfAbsent(aid, k -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("agentId", aid);
                x.put("name", r.get("name") == null ? "已删除的 Agent" : r.get("name"));
                x.put("avatar", r.get("avatar"));
                x.put("tokens", 0L);
                x.put("cost", BigDecimal.ZERO);
                x.put("skipped", false);
                x.put("sessions", 0L);
                x.put("turns", 0L);
                x.put("models", new ArrayList<Map<String, Object>>());
                return x;
            });
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("model", r.get("model_ref"));
            mm.put("tokens", num(r.get("tokens")));
            mm.put("cost", r.get("cost"));
            mm.put("skipped", num(r.get("skipped")) > 0);
            mm.put("sessions", num(r.get("sessions")));
            mm.put("turns", num(r.get("turns")));
            @SuppressWarnings("unchecked") var list = (List<Map<String, Object>>) g.get("models");
            list.add(mm);
            g.put("tokens", (long) g.get("tokens") + num(r.get("tokens")));
            if (r.get("cost") != null) g.put("cost", ((BigDecimal) g.get("cost")).add(new BigDecimal(String.valueOf(r.get("cost")))));
            if (num(r.get("skipped")) > 0) g.put("skipped", true);
            g.put("sessions", (long) g.get("sessions") + num(r.get("sessions")));
            g.put("turns", (long) g.get("turns") + num(r.get("turns")));
        }
        List<Map<String, Object>> agentRank = new ArrayList<>(agents.values());
        agentRank.sort((x, y) -> Long.compare((long) y.get("tokens"), (long) x.get("tokens")));
        for (var g : agentRank) g.put("modelCount", ((List<?>) g.get("models")).size());

        // Skill 排行
        List<Map<String, Object>> skillRank = jdbc.queryForList("""
                SELECT j.name, COUNT(*) n FROM agent_run r,
                  JSON_TABLE(COALESCE(r.skills_used, JSON_ARRAY()), '$[*]' COLUMNS (name VARCHAR(128) PATH '$')) j
                WHERE r.space_id = ? AND r.started_at >= ? AND r.started_at < ?""" + agentCond + " GROUP BY j.name ORDER BY n DESC LIMIT 20", args);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("from", start.toString());
        res.put("to", end.toString());
        res.put("queriedAt", LocalDateTime.now());
        res.put("totals", totals);
        res.put("series", series);
        res.put("agentRank", agentRank);
        res.put("skillRank", skillRank);
        return R.ok(res);
    }

    private List<Map<String, Object>> toWeeks(List<Map<String, Object>> days) {
        Map<String, Map<String, Object>> w = new LinkedHashMap<>();
        WeekFields wf = WeekFields.ISO;
        for (var d : days) {
            LocalDate date = LocalDate.parse((String) d.get("date"));
            LocalDate monday = date.minusDays(date.get(wf.dayOfWeek()) - 1L);
            var m = w.computeIfAbsent(monday.toString(), k -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("date", k);
                x.put("tokens", 0L);
                x.put("cost", BigDecimal.ZERO);
                x.put("sessions", 0L);
                x.put("turns", 0L);
                return x;
            });
            m.put("tokens", (long) m.get("tokens") + num(d.get("tokens")));
            m.put("cost", ((BigDecimal) m.get("cost")).add(new BigDecimal(String.valueOf(d.get("cost")))));
            m.put("sessions", (long) m.get("sessions") + num(d.get("sessions")));
            m.put("turns", (long) m.get("turns") + num(d.get("turns")));
        }
        return new ArrayList<>(w.values());
    }

    private static LocalDate toDate(Object o) {
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof LocalDate d) return d;
        return LocalDate.parse(String.valueOf(o).substring(0, 10));
    }

    private static long num(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.longValue();
        try {
            return new BigDecimal(String.valueOf(o)).longValue();
        } catch (Exception e) {
            return 0;
        }
    }

    @SuppressWarnings("unused")
    private static long daysBetween(LocalDate a, LocalDate b) {
        return ChronoUnit.DAYS.between(a, b);
    }
}
