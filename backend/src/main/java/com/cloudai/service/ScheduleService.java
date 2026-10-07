package com.cloudai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudai.common.BizException;
import com.cloudai.common.Crypto;
import com.cloudai.common.Json;
import com.cloudai.config.AppProperties;
import com.cloudai.entity.Agent;
import com.cloudai.entity.ChatSession;
import com.cloudai.entity.OpsEntities.MessageChannel;
import com.cloudai.entity.OpsEntities.ScheduleRun;
import com.cloudai.entity.OpsEntities.ScheduleTask;
import com.cloudai.mapper.Mappers.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 定时任务调度：每 15 秒扫描到期任务 → 创建新 Session 并发送任务说明 → 失败重试 → 通知消息接收方。
 * 同一任务上一次仍在执行时，本次记为“已跳过”。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService {
    private final ScheduleTaskMapper taskMapper;
    private final ScheduleRunMapper runMapper;
    private final AgentMapper agentMapper;
    private final MessageChannelMapper channelMapper;
    private final RunService runService;
    private final ExecutorClient executor;
    private final Crypto crypto;
    private final AppProperties props;

    private final ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "schedule-run");
        t.setDaemon(true);
        return t;
    });
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    /** 5 段 cron -> Spring 6 段 */
    public static CronExpression parse(String cron) {
        String c = cron == null ? "" : cron.trim().replaceAll("\\s+", " ");
        String[] parts = c.split(" ");
        BizException.check(parts.length == 5, "Cron 表达式须为 5 段：分 时 日 月 周");
        // 标准 cron 中日、周同时指定时为“或”，Spring 不支持，二者有一个为 * 时替换为 ?
        if (!"*".equals(parts[2]) && !"*".equals(parts[4])) {
            // 保持原样，Spring 按“且”处理
        }
        try {
            return CronExpression.parse("0 " + c);
        } catch (IllegalArgumentException e) {
            throw new BizException("Cron 表达式无效：" + e.getMessage());
        }
    }

    public List<LocalDateTime> preview(String cron, String tz, int n) {
        CronExpression ce = parse(cron);
        ZoneId zone = ZoneId.of(tz == null || tz.isBlank() ? props.getTimezone() : tz);
        List<LocalDateTime> out = new ArrayList<>();
        ZonedDateTime t = ZonedDateTime.now(zone);
        for (int i = 0; i < n; i++) {
            t = ce.next(t);
            if (t == null) break;
            out.add(t.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
        }
        return out;
    }

    public LocalDateTime next(ScheduleTask t, LocalDateTime after) {
        ZoneId zone = ZoneId.of(t.getTimezone() == null ? props.getTimezone() : t.getTimezone());
        ZonedDateTime base = after.atZone(ZoneId.systemDefault()).withZoneSameInstant(zone);
        ZonedDateTime n = parse(t.getCron()).next(base);
        return n == null ? null : n.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 20_000)
    public void tick() {
        LocalDateTime now = LocalDateTime.now();
        List<ScheduleTask> due = taskMapper.selectList(new LambdaQueryWrapper<ScheduleTask>()
                .eq(ScheduleTask::getEnabled, true).le(ScheduleTask::getNextFireAt, now));
        for (ScheduleTask t : due) {
            LocalDateTime planned = t.getNextFireAt();
            // 推进下一次触发时间（错过的周期只补最近一次）
            try {
                t.setNextFireAt(next(t, now));
            } catch (Exception e) {
                t.setEnabled(false);
                log.warn("schedule {} cron invalid, disabled", t.getId());
            }
            taskMapper.updateById(t);
            fire(t, planned, false);
        }
    }

    /** 触发一次执行（定时或手动） */
    public ScheduleRun fire(ScheduleTask t, LocalDateTime planned, boolean manual) {
        ScheduleRun run = new ScheduleRun();
        run.setTaskId(t.getId());
        run.setPlannedAt(planned == null ? LocalDateTime.now() : planned);
        run.setAttempt(1);
        run.setManual(manual);
        if (!inFlight.add(t.getId())) {
            run.setStatus("skipped");
            run.setError("previous scheduled run is still running，上一次执行尚未完成，本次跳过");
            run.setFinishedAt(LocalDateTime.now());
            runMapper.insert(run);
            t.setLastStatus("skipped");
            t.setLastRunAt(LocalDateTime.now());
            taskMapper.updateById(t);
            return run;
        }
        run.setStatus("running");
        runMapper.insert(run);
        t.setLastStatus("running");
        t.setLastRunAt(LocalDateTime.now());
        taskMapper.updateById(t);
        pool.submit(() -> {
            try {
                execute(t, run);
            } catch (Exception e) {
                log.error("schedule run {} failed", run.getId(), e);
                run.setStatus("failed");
                run.setError(e.getMessage());
                run.setFinishedAt(LocalDateTime.now());
                runMapper.updateById(run);
            } finally {
                inFlight.remove(t.getId());
            }
        });
        return run;
    }

    private void execute(ScheduleTask t, ScheduleRun run) {
        Agent agent = agentMapper.selectById(t.getAgentId());
        RunService.RunResult res = null;
        int max = Math.max(0, t.getRetryTimes() == null ? 0 : t.getRetryTimes()) + 1;
        for (int attempt = 1; attempt <= max; attempt++) {
            run.setAttempt(attempt);
            if (agent == null || !"available".equals(agent.getStatus())) {
                res = new RunService.RunResult("failed", null, "Agent 不存在或已归档", null, null);
                break;
            }
            String title = t.getName() + " · " + run.getPlannedAt().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
            try {
                ChatSession s = runService.createSession(agent, new RunService.SessionOptions(title, "schedule", String.valueOf(t.getId()),
                        t.getCreatorId(), null, t.getRuntimeEnv(), t.getAgentVersion()));
                run.setSessionId(s.getId());
                runMapper.updateById(run);
                res = runService.sendAndWait(s, t.getPrompt(), "schedule", t.getCreatorId(), Duration.ofMinutes(40));
            } catch (Exception e) {
                res = new RunService.RunResult("failed", null, e.getMessage(), run.getSessionId(), null);
            }
            if ("success".equals(res.status())) break;
            if (attempt < max) {
                try {
                    Thread.sleep(Math.min(60_000L * attempt, 300_000L));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        boolean ok = res != null && "success".equals(res.status());
        run.setStatus(ok ? "success" : "failed");
        run.setError(ok ? null : res == null ? "未知错误" : res.error());
        run.setFinishedAt(LocalDateTime.now());
        runMapper.updateById(run);
        ScheduleTask fresh = taskMapper.selectById(t.getId());
        if (fresh != null) {
            fresh.setLastStatus(run.getStatus());
            fresh.setLastRunAt(LocalDateTime.now());
            taskMapper.updateById(fresh);
        }
        notifyReceivers(t, run, res);
    }

    private void notifyReceivers(ScheduleTask t, ScheduleRun run, RunService.RunResult res) {
        if (t.getReceivers() == null || !t.getReceivers().isArray()) return;
        boolean ok = "success".equals(run.getStatus());
        String link = props.getPublicUrl().replaceAll("/+$", "") + "/sessions/" + run.getSessionId();
        for (JsonNode r : t.getReceivers()) {
            boolean send = ok ? r.path("notifyResult").asBoolean(true) : r.path("notifyFailure").asBoolean(false);
            if (!send) continue;
            try {
                MessageChannel ch = channelMapper.selectById(r.path("channelId").asText());
                if (ch == null || !t.getSpaceId().equals(ch.getSpaceId()) || !"feishu".equals(ch.getType()) || r.path("chatId").asText().isBlank()) continue;
                String text;
                String title;
                if (ok) {
                    title = "✅ " + t.getName();
                    String body = res == null || res.finalText() == null ? "（无文本输出）" : res.finalText();
                    text = RunService.truncate(body, 6000) + "\n\n[查看完整会话](" + link + ")";
                } else {
                    title = "❌ 定时任务执行失败：" + t.getName();
                    text = "**计划时间**：" + run.getPlannedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) +
                            "\n**错误**：" + RunService.truncate(run.getError(), 1000) + (run.getSessionId() == null ? "" : "\n\n[查看会话](" + link + ")");
                }
                executor.call("POST", "/feishu/send", Json.obj().put("app_id", ch.getAppId())
                        .put("app_secret", crypto.decrypt(ch.getAppSecretEnc())).put("chat_id", r.path("chatId").asText())
                        .put("title", title).put("text", text), 30);
            } catch (Exception e) {
                log.warn("notify receiver failed: {}", e.getMessage());
            }
        }
    }

    public boolean isRunning(Long taskId) {
        return inFlight.contains(taskId);
    }
}
