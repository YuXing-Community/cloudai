package com.cloudai.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudai.auth.Perm;
import com.cloudai.auth.RequirePerm;
import com.cloudai.auth.UserContext;
import com.cloudai.common.BizException;
import com.cloudai.common.Json;
import com.cloudai.common.R;
import com.cloudai.entity.Agent;
import com.cloudai.entity.OpsEntities.FileInfo;
import com.cloudai.entity.RuntimeEntities.*;
import com.cloudai.mapper.Mappers.*;
import com.cloudai.service.RunService;
import com.cloudai.service.TeamService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Agent 小队与任务看板 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TeamController {
    private final AgentTeamMapper teamMapper;
    private final TeamSessionMapper sessionMapper;
    private final TeamTaskMapper taskMapper;
    private final TeamRunMapper runMapper;
    private final TeamMessageMapper messageMapper;
    private final AgentMapper agentMapper;
    private final FileInfoMapper fileMapper;
    private final TeamService teamService;
    private final RunService runService;

    // ================================================================ 小队

    @GetMapping("/teams")
    @RequirePerm(Perm.TEAM_VIEW)
    public R<List<Map<String, Object>>> teams() {
        String sid = UserContext.spaceId();
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentTeam t : teamMapper.selectList(new LambdaQueryWrapper<AgentTeam>().eq(AgentTeam::getSpaceId, sid).orderByDesc(AgentTeam::getCreatedAt))) {
            Map<String, Object> m = teamRow(t);
            m.put("runs", sessionMapper.selectCount(new LambdaQueryWrapper<TeamSession>().eq(TeamSession::getTeamId, t.getId())));
            out.add(m);
        }
        return R.ok(out);
    }

    private Map<String, Object> teamRow(AgentTeam t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("description", t.getDescription());
        m.put("pattern", t.getPattern());
        List<Map<String, Object>> roles = new ArrayList<>();
        if (t.getRoles() != null) for (JsonNode r : t.getRoles()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("role", r.path("role").asText());
            x.put("label", r.path("label").asText());
            x.put("description", r.path("description").asText());
            x.put("agentId", r.path("agentId").asText());
            Agent a = agentMapper.selectById(r.path("agentId").asText());
            x.put("agentName", a == null ? "已删除" : a.getName());
            x.put("agentAvatar", a == null ? null : a.getAvatar());
            roles.add(x);
        }
        m.put("roles", roles);
        m.put("createdAt", t.getCreatedAt());
        return m;
    }

    @Data
    public static class TeamReq {
        private String name;
        private String description;
        private String pattern;
        private JsonNode roles;
    }

    @PostMapping("/teams")
    @RequirePerm(Perm.TEAM_EDIT)
    public R<Map<String, Object>> create(@RequestBody TeamReq req) {
        AgentTeam t = new AgentTeam();
        t.setId(Json.uuid());
        t.setSpaceId(UserContext.spaceId());
        t.setCreatedBy(UserContext.userId());
        apply(t, req);
        teamMapper.insert(t);
        return R.ok(teamRow(t));
    }

    @PutMapping("/teams/{id}")
    @RequirePerm(Perm.TEAM_EDIT)
    public R<Map<String, Object>> update(@PathVariable String id, @RequestBody TeamReq req) {
        AgentTeam t = loadTeam(id);
        apply(t, req);
        teamMapper.updateById(t);
        return R.ok(teamRow(t));
    }

    @DeleteMapping("/teams/{id}")
    @RequirePerm(Perm.TEAM_EDIT)
    public R<Void> delete(@PathVariable String id) {
        teamMapper.deleteById(loadTeam(id).getId());
        return R.ok();
    }

    private void apply(AgentTeam t, TeamReq r) {
        BizException.check(r.name != null && !r.name.isBlank(), "请填写小队名称");
        BizException.check(r.roles != null && r.roles.isArray() && r.roles.size() >= 2, "至少需要 1 个路由角色和 1 个专家角色");
        Set<String> roleNames = new HashSet<>();
        for (JsonNode x : r.roles) {
            String role = x.path("role").asText();
            BizException.check(role.matches("^[a-zA-Z][a-zA-Z0-9_\\-]{0,31}$"), "角色标识须为字母开头的英文：" + role);
            BizException.check(roleNames.add(role), "角色重复：" + role);
            Agent a = agentMapper.selectById(x.path("agentId").asText());
            BizException.check(a != null && t.getSpaceId().equals(a.getSpaceId()), "角色 " + role + " 须绑定当前空间的 Agent");
        }
        t.setName(r.name.trim());
        t.setDescription(r.description);
        t.setPattern(r.pattern == null ? "expert_pool" : r.pattern);
        t.setRoles(r.roles);
    }

    @PostMapping("/teams/{id}/runs")
    @RequirePerm(Perm.TEAM_EDIT)
    public R<Map<String, Object>> run(@PathVariable String id, @RequestBody Map<String, String> body) {
        AgentTeam t = loadTeam(id);
        String goal = body.getOrDefault("goal", "").trim();
        BizException.check(!goal.isEmpty(), "请填写任务目标");
        TeamSession ts = teamService.start(t, goal, body.get("name"), UserContext.username());
        return R.ok(Map.of("id", ts.getId()));
    }

    // ================================================================ 任务看板

    @GetMapping("/team-sessions")
    @RequirePerm(Perm.TEAM_VIEW)
    public R<List<Map<String, Object>>> sessions(@RequestParam(required = false) String q, @RequestParam(defaultValue = "all") String status,
                                                 @RequestParam(required = false) String teamId) {
        var w = new LambdaQueryWrapper<TeamSession>().eq(TeamSession::getSpaceId, UserContext.spaceId()).eq(TeamSession::getArchived, false);
        if ("running".equals(status)) w.eq(TeamSession::getStatus, "running");
        if ("waiting_input".equals(status)) w.eq(TeamSession::getStatus, "waiting_input");
        if (teamId != null && !teamId.isBlank()) w.eq(TeamSession::getTeamId, teamId);
        if (q != null && !q.isBlank()) w.and(x -> x.like(TeamSession::getName, q).or().like(TeamSession::getGoal, q));
        w.orderByDesc(TeamSession::getCreatedAt);
        Map<String, AgentTeam> teams = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (TeamSession s : sessionMapper.selectList(w)) {
            AgentTeam t = teams.computeIfAbsent(s.getTeamId(), teamMapper::selectById);
            Map<String, Object> m = sessionRow(s);
            m.put("teamName", t == null ? "已删除" : t.getName());
            m.put("pattern", t == null ? "" : t.getPattern());
            out.add(m);
        }
        return R.ok(out);
    }

    private Map<String, Object> sessionRow(TeamSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("goal", s.getGoal());
        m.put("submitter", s.getSubmitter());
        m.put("status", s.getStatus());
        m.put("teamId", s.getTeamId());
        m.put("state", s.getState());
        m.put("createdAt", s.getCreatedAt());
        m.put("finishedAt", s.getFinishedAt());
        return m;
    }

    @GetMapping("/team-sessions/{id}")
    @RequirePerm(Perm.TEAM_VIEW)
    public R<Map<String, Object>> detail(@PathVariable String id) {
        TeamSession s = loadSession(id);
        AgentTeam t = teamMapper.selectById(s.getTeamId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", sessionRow(s));
        out.put("team", t == null ? null : teamRow(t));
        out.put("tasks", teamService.tasks(id));
        List<TeamRun> runs = runMapper.selectList(new LambdaQueryWrapper<TeamRun>().eq(TeamRun::getTeamSessionId, id).orderByAsc(TeamRun::getId));
        out.put("runs", runs);
        List<TeamMessage> msgs = messageMapper.selectList(new LambdaQueryWrapper<TeamMessage>().eq(TeamMessage::getTeamSessionId, id).orderByAsc(TeamMessage::getId));
        out.put("inputs", msgs.stream().filter(m -> "user_input".equals(m.getKind())).toList());
        out.put("messages", msgs.stream().filter(m -> "message".equals(m.getKind())).toList());
        out.put("events", msgs.stream().filter(m -> "event".equals(m.getKind())).toList());
        List<String> childIds = runs.stream().map(TeamRun::getChildSessionId).filter(Objects::nonNull).toList();
        out.put("artifacts", childIds.isEmpty() ? List.of() : fileMapper.selectList(new LambdaQueryWrapper<FileInfo>()
                .in(FileInfo::getSessionId, childIds).eq(FileInfo::getCategory, "artifact")));
        return R.ok(out);
    }

    @PostMapping("/team-sessions/{id}/inputs/{msgId}")
    @RequirePerm(Perm.TEAM_EDIT)
    public R<Void> answer(@PathVariable String id, @PathVariable Long msgId, @RequestBody Map<String, String> body) {
        TeamSession s = loadSession(id);
        TeamMessage m = messageMapper.selectById(msgId);
        BizException.check(m != null && id.equals(m.getTeamSessionId()), "问题不存在");
        String a = body.getOrDefault("answer", "").trim();
        BizException.check(!a.isEmpty(), "请填写回复");
        teamService.answer(s, m, a);
        return R.ok();
    }

    @PostMapping("/team-sessions/{id}/terminate")
    @RequirePerm(Perm.TEAM_EDIT)
    public R<Void> terminate(@PathVariable String id) {
        teamService.terminate(loadSession(id));
        return R.ok();
    }

    @PutMapping("/team-sessions/{id}/archive")
    @RequirePerm(Perm.TEAM_EDIT)
    public R<Void> archive(@PathVariable String id) {
        TeamSession s = loadSession(id);
        s.setArchived(true);
        sessionMapper.updateById(s);
        return R.ok();
    }

    private AgentTeam loadTeam(String id) {
        AgentTeam t = teamMapper.selectById(id);
        if (t == null || !UserContext.spaceId().equals(t.getSpaceId())) throw BizException.notFound("小队");
        return t;
    }

    private TeamSession loadSession(String id) {
        TeamSession s = sessionMapper.selectById(id);
        if (s == null || !UserContext.spaceId().equals(s.getSpaceId())) throw BizException.notFound("任务");
        return s;
    }

    @SuppressWarnings("unused")
    private RunService rs() {
        return runService;
    }
}
