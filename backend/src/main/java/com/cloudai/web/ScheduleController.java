package com.cloudai.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.auth.Perm;
import com.cloudai.auth.RequirePerm;
import com.cloudai.auth.UserContext;
import com.cloudai.common.BizException;
import com.cloudai.common.PageResult;
import com.cloudai.common.R;
import com.cloudai.entity.Agent;
import com.cloudai.entity.OpsEntities.MessageChannel;
import com.cloudai.entity.OpsEntities.ScheduleRun;
import com.cloudai.entity.OpsEntities.ScheduleTask;
import com.cloudai.entity.User;
import com.cloudai.mapper.CoreMappers.UserMapper;
import com.cloudai.mapper.Mappers.AgentMapper;
import com.cloudai.mapper.Mappers.MessageChannelMapper;
import com.cloudai.mapper.Mappers.ScheduleRunMapper;
import com.cloudai.mapper.Mappers.ScheduleTaskMapper;
import com.cloudai.service.ScheduleService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/** 定时任务：按 cron 周期创建 Agent Session，并自动发送任务说明执行 */
@RestController
@RequestMapping("/api/schedules")
@RequiredArgsConstructor
public class ScheduleController {
    private final ScheduleTaskMapper taskMapper;
    private final ScheduleRunMapper runMapper;
    private final AgentMapper agentMapper;
    private final MessageChannelMapper channelMapper;
    private final UserMapper userMapper;
    private final ScheduleService scheduleService;

    @GetMapping
    @RequirePerm(Perm.SCHEDULE_VIEW)
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String q, @RequestParam(defaultValue = "all") String status) {
        var w = new LambdaQueryWrapper<ScheduleTask>().eq(ScheduleTask::getSpaceId, UserContext.spaceId());
        if (q != null && !q.isBlank()) w.and(x -> x.like(ScheduleTask::getName, q).or().like(ScheduleTask::getPrompt, q));
        if ("enabled".equals(status)) w.eq(ScheduleTask::getEnabled, true);
        if ("disabled".equals(status)) w.eq(ScheduleTask::getEnabled, false);
        w.orderByAsc(ScheduleTask::getNextFireAt);
        Map<String, Agent> agents = new HashMap<>();
        Map<Long, String> users = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ScheduleTask t : taskMapper.selectList(w)) {
            Map<String, Object> m = row(t);
            Agent a = agents.computeIfAbsent(t.getAgentId(), agentMapper::selectById);
            m.put("agentName", a == null ? "已删除的 Agent" : a.getName());
            m.put("creator", users.computeIfAbsent(t.getCreatorId(), id -> {
                User u = userMapper.selectById(id);
                return u == null ? "" : u.getUsername();
            }));
            m.put("running", scheduleService.isRunning(t.getId()));
            out.add(m);
        }
        return R.ok(out);
    }

    private Map<String, Object> row(ScheduleTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("agentId", t.getAgentId());
        m.put("agentVersion", t.getAgentVersion());
        m.put("runtimeEnv", t.getRuntimeEnv());
        m.put("prompt", t.getPrompt());
        m.put("enabled", t.getEnabled());
        m.put("retryTimes", t.getRetryTimes());
        m.put("receivers", t.getReceivers());
        m.put("cronMode", t.getCronMode());
        m.put("cron", t.getCron());
        m.put("timezone", t.getTimezone());
        m.put("nextFireAt", t.getNextFireAt());
        m.put("lastStatus", t.getLastStatus());
        m.put("lastRunAt", t.getLastRunAt());
        m.put("createdAt", t.getCreatedAt());
        return m;
    }

    @Data
    public static class Req {
        private String name;
        private String agentId;
        private Integer agentVersion;
        private String runtimeEnv;
        private String prompt;
        private Boolean enabled;
        private Integer retryTimes;
        private JsonNode receivers;
        private String cronMode;
        private String cron;
        private String timezone;
    }

    @PostMapping
    @RequirePerm(Perm.SCHEDULE_EDIT)
    public R<Map<String, Object>> create(@RequestBody Req req) {
        ScheduleTask t = new ScheduleTask();
        t.setSpaceId(UserContext.spaceId());
        t.setCreatorId(UserContext.userId());
        apply(t, req);
        taskMapper.insert(t);
        return R.ok(row(t));
    }

    @PutMapping("/{id}")
    @RequirePerm(Perm.SCHEDULE_EDIT)
    public R<Map<String, Object>> update(@PathVariable Long id, @RequestBody Req req) {
        ScheduleTask t = load(id);
        apply(t, req);
        taskMapper.updateById(t);
        return R.ok(row(t));
    }

    private void apply(ScheduleTask t, Req r) {
        BizException.check(r.name != null && !r.name.isBlank(), "请填写任务名");
        BizException.check(r.prompt != null && !r.prompt.isBlank(), "请填写任务说明");
        Agent a = agentMapper.selectById(r.agentId);
        BizException.check(a != null && t.getSpaceId().equals(a.getSpaceId()), "请选择当前空间的 Agent");
        BizException.check(r.receivers == null || r.receivers.size() <= 10, "消息接收方最多 10 个");
        if (r.receivers != null) {
            for (JsonNode rc : r.receivers) {
                MessageChannel ch = channelMapper.selectById(rc.path("channelId").asText());
                BizException.check(ch != null && t.getSpaceId().equals(ch.getSpaceId()), "接收方的消息通道不存在或不属于当前空间");
                BizException.check(!rc.path("chatId").asText("").isBlank(), "请填写接收方 chat_id");
            }
        }
        t.setName(r.name.trim());
        t.setAgentId(r.agentId);
        t.setAgentVersion(r.agentVersion);
        t.setRuntimeEnv(r.runtimeEnv);
        t.setPrompt(r.prompt);
        t.setEnabled(r.enabled == null || r.enabled);
        t.setRetryTimes(r.retryTimes == null ? 0 : Math.max(0, Math.min(5, r.retryTimes)));
        t.setReceivers(r.receivers);
        t.setCronMode(r.cronMode == null ? "cron" : r.cronMode);
        t.setTimezone(r.timezone == null || r.timezone.isBlank() ? "Asia/Shanghai" : r.timezone);
        t.setCron(r.cron == null ? "" : r.cron.trim().replaceAll("\\s+", " "));
        ScheduleService.parse(t.getCron());
        t.setNextFireAt(scheduleService.next(t, LocalDateTime.now()));
    }

    @PutMapping("/{id}/enabled")
    @RequirePerm(Perm.SCHEDULE_EDIT)
    public R<Void> enable(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        ScheduleTask t = load(id);
        t.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        if (t.getEnabled()) t.setNextFireAt(scheduleService.next(t, LocalDateTime.now()));
        taskMapper.updateById(t);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @RequirePerm(Perm.SCHEDULE_EDIT)
    public R<Void> delete(@PathVariable Long id) {
        ScheduleTask t = load(id);
        runMapper.delete(new LambdaQueryWrapper<ScheduleRun>().eq(ScheduleRun::getTaskId, t.getId()));
        taskMapper.deleteById(t.getId());
        return R.ok();
    }

    @PostMapping("/{id}/trigger")
    @RequirePerm(Perm.SCHEDULE_EDIT)
    public R<ScheduleRun> trigger(@PathVariable Long id) {
        return R.ok(scheduleService.fire(load(id), LocalDateTime.now(), true));
    }

    @GetMapping("/{id}/runs")
    @RequirePerm(Perm.SCHEDULE_VIEW)
    public R<PageResult<ScheduleRun>> runs(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean onlyUnfinished,
                                           @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size) {
        load(id);
        var w = new LambdaQueryWrapper<ScheduleRun>().eq(ScheduleRun::getTaskId, id);
        if (onlyUnfinished) w.ne(ScheduleRun::getStatus, "success");
        w.orderByDesc(ScheduleRun::getPlannedAt).orderByDesc(ScheduleRun::getId);
        return R.ok(PageResult.of(runMapper.selectPage(new Page<>(page, size), w)));
    }

    @PostMapping("/preview")
    public R<List<LocalDateTime>> preview(@RequestBody Map<String, String> body) {
        return R.ok(scheduleService.preview(body.get("cron"), body.get("timezone"), 3));
    }

    private ScheduleTask load(Long id) {
        ScheduleTask t = taskMapper.selectById(id);
        if (t == null || !UserContext.spaceId().equals(t.getSpaceId())) throw BizException.notFound("定时任务");
        return t;
    }
}
