package com.cloudai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudai.common.BizException;
import com.cloudai.common.Json;
import com.cloudai.entity.Agent;
import com.cloudai.entity.ChatSession;
import com.cloudai.entity.RuntimeEntities.*;
import com.cloudai.mapper.Mappers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Agent 小队（专家池模式）：
 * 用户目标 → router Agent 选择专家（或向用户追问）→ 专家 Agent 执行 → 汇总产物。
 * 所有任务、运行记录、用户输入、事件都落库，任务看板据此展示。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamService {
    private final AgentTeamMapper teamMapper;
    private final TeamSessionMapper sessionMapper;
    private final TeamTaskMapper taskMapper;
    private final TeamRunMapper runMapper;
    private final TeamMessageMapper messageMapper;
    private final AgentMapper agentMapper;
    private final RunService runService;

    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "team-run");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    public TeamSession start(AgentTeam team, String goal, String name, String submitter) {
        BizException.check(team.getRoles() != null && team.getRoles().size() >= 2, "小队至少需要 1 个路由角色和 1 个专家角色");
        TeamSession ts = new TeamSession();
        ts.setId(Json.uuid());
        ts.setSpaceId(team.getSpaceId());
        ts.setTeamId(team.getId());
        ts.setName(name == null || name.isBlank() ? team.getName() : name);
        ts.setGoal(goal);
        ts.setSubmitter(submitter);
        ts.setStatus("running");
        ts.setArchived(false);
        ts.setState(Json.obj().put("pattern", team.getPattern()).put("round", 0));
        sessionMapper.insert(ts);

        TeamTask root = task(ts.getId(), null, goal.length() > 80 ? goal.substring(0, 80) + "…" : goal,
                "Expert pool pattern: team=" + team.getId(), null, null, "pending");
        JsonNode router = team.getRoles().get(0);
        TeamTask rt = task(ts.getId(), root.getId(), "router: select expert", "route request to appropriate expert for goal: " + goal,
                router.path("role").asText("router"), router.path("agentId").asText(null), "running");
        event(ts.getId(), rt.getId(), "小队开始执行，router 正在选择专家");
        pool.submit(() -> routeSafely(ts.getId(), rt.getId(), null));
        return ts;
    }

    /** 用户回答 router 的追问后继续路由 */
    public void answer(TeamSession ts, TeamMessage msg, String answer) {
        BizException.check("open".equals(msg.getStatus()), "该问题已回复");
        BizException.check("waiting_input".equals(ts.getStatus()), "任务当前不在等待输入状态");
        msg.setAnswer(answer);
        msg.setStatus("answered");
        messageMapper.updateById(msg);
        TeamTask rt = taskMapper.selectById(msg.getTaskId());
        rt.setStatus("running");
        taskMapper.updateById(rt);
        ts.setStatus("running");
        sessionMapper.updateById(ts);
        event(ts.getId(), rt.getId(), "用户已回复，router 继续路由");
        pool.submit(() -> routeSafely(ts.getId(), rt.getId(), answer));
    }

    public void terminate(TeamSession ts) {
        for (TeamRun r : runMapper.selectList(new LambdaQueryWrapper<TeamRun>().eq(TeamRun::getTeamSessionId, ts.getId()).eq(TeamRun::getStatus, "running"))) {
            if (r.getChildSessionId() != null) runService.stop(r.getChildSessionId());
        }
        for (TeamTask t : tasks(ts.getId())) {
            if (!Set.of("done", "failed").contains(t.getStatus())) {
                t.setStatus("failed");
                taskMapper.updateById(t);
            }
        }
        ts.setStatus("terminated");
        ts.setFinishedAt(LocalDateTime.now());
        sessionMapper.updateById(ts);
        event(ts.getId(), null, "已手动终止");
    }

    // ------------------------------------------------------------------

    private void routeSafely(String tsId, String routerTaskId, String userAnswer) {
        try {
            route(tsId, routerTaskId, userAnswer);
        } catch (Exception e) {
            log.warn("team route failed", e);
            fail(tsId, routerTaskId, e.getMessage());
        }
    }

    private void route(String tsId, String routerTaskId, String userAnswer) {
        TeamSession ts = sessionMapper.selectById(tsId);
        if (ts == null || "terminated".equals(ts.getStatus())) return;
        AgentTeam team = teamMapper.selectById(ts.getTeamId());
        TeamTask rt = taskMapper.selectById(routerTaskId);
        JsonNode roles = team.getRoles();
        JsonNode router = roles.get(0);

        StringBuilder experts = new StringBuilder();
        for (int i = 1; i < roles.size(); i++) {
            JsonNode r = roles.get(i);
            experts.append("- role=").append(r.path("role").asText()).append("（").append(r.path("label").asText(""))
                    .append("）：").append(r.path("description").asText("")).append('\n');
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("[Agent Team 路由] 你是小队「").append(team.getName()).append("」的 router，负责理解用户目标并分派给最合适的专家。\n\n")
                .append("用户目标：").append(ts.getGoal()).append("\n\n可用专家：\n").append(experts);
        List<TeamMessage> qa = messageMapper.selectList(new LambdaQueryWrapper<TeamMessage>().eq(TeamMessage::getTeamSessionId, tsId)
                .eq(TeamMessage::getKind, "user_input").eq(TeamMessage::getStatus, "answered").orderByAsc(TeamMessage::getId));
        if (!qa.isEmpty()) {
            prompt.append("\n已向用户确认的信息：\n");
            for (TeamMessage m : qa) prompt.append("- 问：").append(m.getContent()).append("\n  答：").append(m.getAnswer()).append('\n');
        }
        prompt.append("""

                请只输出一个 JSON 对象，不要输出其它内容：
                - 信息足够时：{"action":"route","role":"<专家 role>","task":"<交给专家的完整任务描述>","reason":"<一句话理由>"}
                - 信息不足时：{"action":"ask","question":"<需要用户补充的问题，可列出选项>"}
                """);

        ObjectNode input = Json.obj().put("task_id", routerTaskId).put("role", router.path("role").asText("router"));
        ObjectNode ctx = input.putObject("team_context");
        ctx.put("team_id", team.getId()).put("session_id", tsId).put("pattern_type", team.getPattern());
        ctx.set("role_bindings", roles);
        input.put("goal", ts.getGoal());
        if (userAnswer != null) input.put("user_answer", userAnswer);

        RunService.RunResult res = runAgent(ts, rt, router.path("agentId").asText(), router.path("role").asText("router"), prompt.toString(), input);
        if (terminated(tsId)) return;
        if (!"success".equals(res.status())) {
            fail(tsId, routerTaskId, "router 执行失败：" + res.error());
            return;
        }
        JsonNode decision = parseJson(res.finalText());
        String action = decision == null ? "ask" : decision.path("action").asText("ask");
        if ("route".equals(action)) {
            String role = decision.path("role").asText();
            JsonNode expert = null;
            for (int i = 1; i < roles.size(); i++) if (role.equals(roles.get(i).path("role").asText())) expert = roles.get(i);
            if (expert == null) {
                fail(tsId, routerTaskId, "router 选择了不存在的专家：" + role);
                return;
            }
            rt.setStatus("done");
            taskMapper.updateById(rt);
            event(tsId, rt.getId(), "router 选择专家 " + role + "：" + decision.path("reason").asText(""));
            String expertTask = decision.path("task").asText(ts.getGoal());
            TeamTask et = task(tsId, rt.getId(), role + ": " + (expertTask.length() > 60 ? expertTask.substring(0, 60) + "…" : expertTask),
                    expertTask, role, expert.path("agentId").asText(null), "running");
            ObjectNode ein = Json.obj().put("task_id", et.getId()).put("role", role).put("task", expertTask).put("goal", ts.getGoal());
            if (terminated(tsId)) return;
            RunService.RunResult er = runAgent(ts, et, expert.path("agentId").asText(), role, expertTask, ein);
            if (terminated(tsId)) return;
            if (!"success".equals(er.status())) {
                fail(tsId, et.getId(), "专家 " + role + " 执行失败：" + er.error());
                return;
            }
            et.setStatus("done");
            taskMapper.updateById(et);
            TeamTask root = taskMapper.selectById(rt.getParentId());
            if (root != null) {
                root.setStatus("done");
                taskMapper.updateById(root);
            }
            TeamSession fresh = sessionMapper.selectById(tsId);
            fresh.setStatus("completed");
            fresh.setFinishedAt(LocalDateTime.now());
            ObjectNode st = fresh.getState() == null ? Json.obj() : (ObjectNode) fresh.getState();
            st.put("result", RunService.truncate(er.finalText(), 20000));
            fresh.setState(st);
            sessionMapper.updateById(fresh);
            event(tsId, et.getId(), "专家完成任务，小队执行结束");
        } else {
            String question = decision == null ? Objects.toString(res.finalText(), "请补充更多信息") : decision.path("question").asText("请补充更多信息");
            TeamMessage m = new TeamMessage();
            m.setTeamSessionId(tsId);
            m.setKind("user_input");
            m.setTaskId(rt.getId());
            m.setContent(question);
            m.setStatus("open");
            messageMapper.insert(m);
            rt.setStatus("waiting_input");
            taskMapper.updateById(rt);
            TeamSession fresh = sessionMapper.selectById(tsId);
            fresh.setStatus("waiting_input");
            sessionMapper.updateById(fresh);
            event(tsId, rt.getId(), "router 需要用户补充信息");
        }
    }

    private boolean terminated(String tsId) {
        TeamSession t = sessionMapper.selectById(tsId);
        return t == null || "terminated".equals(t.getStatus());
    }

    private RunService.RunResult runAgent(TeamSession ts, TeamTask task, String agentId, String role, String prompt, ObjectNode input) {
        Agent agent = agentMapper.selectById(agentId);
        TeamRun run = new TeamRun();
        run.setTeamSessionId(ts.getId());
        run.setTaskId(task.getId());
        run.setRole(role);
        run.setAgentId(agentId);
        run.setInput(input.toPrettyString());
        run.setStatus("running");
        runMapper.insert(run);
        if (agent == null || !ts.getSpaceId().equals(agent.getSpaceId())) {
            run.setStatus("failed");
            run.setOutput("角色绑定的 Agent 不存在或不属于当前空间");
            runMapper.updateById(run);
            return new RunService.RunResult("failed", null, run.getOutput(), null, null);
        }
        long t0 = System.currentTimeMillis();
        ChatSession cs = runService.createSession(agent, new RunService.SessionOptions("[Team] " + ts.getName() + " · " + role,
                "team", ts.getId(), null, null, null, null));
        run.setChildSessionId(cs.getId());
        runMapper.updateById(run);
        RunService.RunResult r = runService.sendAndWait(cs, prompt, "team", null, Duration.ofMinutes(30));
        run.setStatus("success".equals(r.status()) ? "done" : "failed");
        run.setOutput("success".equals(r.status()) ? r.finalText() : r.error());
        run.setDurationMs(System.currentTimeMillis() - t0);
        runMapper.updateById(run);
        return r;
    }

    private void fail(String tsId, String taskId, String error) {
        if (taskId != null) {
            TeamTask t = taskMapper.selectById(taskId);
            if (t != null) {
                t.setStatus("failed");
                taskMapper.updateById(t);
            }
        }
        TeamSession ts = sessionMapper.selectById(tsId);
        if (ts != null && !"terminated".equals(ts.getStatus())) {
            ts.setStatus("failed");
            ts.setFinishedAt(LocalDateTime.now());
            sessionMapper.updateById(ts);
        }
        event(tsId, taskId, "失败：" + error);
    }

    private TeamTask task(String tsId, String parent, String title, String desc, String role, String agentId, String status) {
        TeamTask t = new TeamTask();
        t.setId(Json.uuid());
        t.setTeamSessionId(tsId);
        t.setParentId(parent);
        t.setTitle(title);
        t.setDescription(desc);
        t.setRole(role);
        t.setAgentId(agentId);
        t.setStatus(status);
        taskMapper.insert(t);
        return t;
    }

    private void event(String tsId, String taskId, String content) {
        TeamMessage m = new TeamMessage();
        m.setTeamSessionId(tsId);
        m.setKind("event");
        m.setTaskId(taskId);
        m.setContent(content);
        m.setStatus("answered");
        messageMapper.insert(m);
    }

    public List<TeamTask> tasks(String tsId) {
        return taskMapper.selectList(new LambdaQueryWrapper<TeamTask>().eq(TeamTask::getTeamSessionId, tsId).orderByAsc(TeamTask::getCreatedAt));
    }

    /** 从模型输出中提取第一个 JSON 对象 */
    static JsonNode parseJson(String text) {
        if (text == null) return null;
        int s = text.indexOf('{'), e = text.lastIndexOf('}');
        if (s < 0 || e <= s) return null;
        try {
            return Json.M.readTree(text.substring(s, e + 1));
        } catch (Exception ex) {
            return null;
        }
    }

    @SuppressWarnings("unused")
    private static ArrayNode arr() {
        return Json.arr();
    }
}
