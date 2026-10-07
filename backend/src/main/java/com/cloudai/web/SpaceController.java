package com.cloudai.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudai.auth.AuthInterceptor;
import com.cloudai.auth.Perm;
import com.cloudai.auth.RequirePerm;
import com.cloudai.auth.UserContext;
import com.cloudai.common.BizException;
import com.cloudai.common.Json;
import com.cloudai.common.R;
import com.cloudai.entity.OpsEntities.MessageChannel;
import com.cloudai.entity.Space;
import com.cloudai.entity.SpaceMember;
import com.cloudai.entity.SpaceRole;
import com.cloudai.entity.User;
import com.cloudai.mapper.CoreMappers.SpaceMapper;
import com.cloudai.mapper.CoreMappers.SpaceMemberMapper;
import com.cloudai.mapper.CoreMappers.SpaceRoleMapper;
import com.cloudai.mapper.CoreMappers.UserMapper;
import com.cloudai.mapper.Mappers.MessageChannelMapper;
import com.cloudai.service.ChannelService;
import com.cloudai.service.SpaceService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 空间、成员、角色 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SpaceController {
    private final SpaceMapper spaceMapper;
    private final SpaceMemberMapper memberMapper;
    private final SpaceRoleMapper roleMapper;
    private final UserMapper userMapper;
    private final SpaceService spaceService;
    private final AuthInterceptor authInterceptor;
    private final JdbcTemplate jdbc;
    private final MessageChannelMapper channelMapper;
    private final ChannelService channelService;

    // ---------------- 空间 ----------------

    @GetMapping("/spaces")
    public R<List<Map<String, Object>>> mySpaces() {
        long uid = UserContext.userId();
        List<SpaceMember> ms = memberMapper.selectList(new LambdaQueryWrapper<SpaceMember>().eq(SpaceMember::getUserId, uid));
        List<Map<String, Object>> out = new ArrayList<>();
        for (SpaceMember m : ms) {
            Space s = spaceMapper.selectById(m.getSpaceId());
            if (s == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.getId());
            row.put("name", s.getName());
            row.put("description", s.getDescription());
            row.put("type", s.getType());
            row.put("roleCode", m.getRoleCode());
            row.put("memberCount", spaceService.memberCount(s.getId()));
            row.put("createdAt", s.getCreatedAt());
            out.add(row);
        }
        out.sort(Comparator.comparing(r -> String.valueOf(r.get("createdAt"))));
        return R.ok(out);
    }

    @Data
    public static class SpaceReq {
        @NotBlank @Size(max = 64) private String name;
        @Size(max = 512) private String description;
    }

    @PostMapping("/spaces")
    public R<Space> create(@Valid @RequestBody SpaceReq req) {
        return R.ok(spaceService.createSpace(UserContext.userId(), req.name, req.description, "team"));
    }

    /** 当前空间详情 + 我的角色与权限 */
    @GetMapping("/space")
    public R<Map<String, Object>> current() {
        Space s = spaceService.current();
        var ctx = UserContext.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("space", s);
        out.put("roleCode", ctx.getRoleCode());
        out.put("perms", ctx.getPerms());
        out.put("isSystemAdmin", ctx.isAdmin());
        return R.ok(out);
    }

    @PutMapping("/space")
    @RequirePerm(Perm.SPACE_EDIT)
    public R<Space> update(@Valid @RequestBody SpaceReq req) {
        Space s = spaceService.current();
        s.setName(req.name);
        s.setDescription(req.description);
        spaceMapper.updateById(s);
        return R.ok(s);
    }

    /** 自定义运行环境开关：仅系统管理员可修改 */
    @PutMapping("/space/custom-env")
    public R<Space> toggleCustomEnv(@RequestBody Map<String, Boolean> body) {
        if (!UserContext.get().isAdmin()) throw new BizException(403, "仅系统管理员可修改");
        Space s = spaceService.current();
        s.setCustomEnvEnabled(Boolean.TRUE.equals(body.get("enabled")));
        spaceMapper.updateById(s);
        return R.ok(s);
    }

    /** 删除空间及其下所有资源（不可恢复） */
    @DeleteMapping("/space")
    @RequirePerm(Perm.SPACE_DELETE)
    @Transactional
    public R<Void> delete(@RequestParam String confirmName) {
        Space s = spaceService.current();
        BizException.check(s.getName().equals(confirmName), "空间名称不匹配");
        long mySpaces = memberMapper.selectCount(new LambdaQueryWrapper<SpaceMember>().eq(SpaceMember::getUserId, UserContext.userId()));
        BizException.check(mySpaces > 1, "这是你唯一的空间，不能删除");
        String id = s.getId();
        // 先断开飞书长连接，再清理子表（无 space_id 的明细表按父表关联删除），最后清理带 space_id 的主表
        for (MessageChannel ch : channelMapper.selectList(new LambdaQueryWrapper<MessageChannel>().eq(MessageChannel::getSpaceId, id))) {
            channelService.stop(ch);
        }
        String[][] children = {
                {"model_def", "provider_id", "model_provider"},
                {"agent_version", "agent_id", "agent"},
                {"agent_favorite", "agent_id", "agent"},
                {"agent_grant", "agent_id", "agent"},
                {"session_event", "session_id", "chat_session"},
                {"skill_version", "skill_id", "skill"},
                {"tool_item", "plugin_id", "tool_plugin"},
                {"schedule_run", "task_id", "schedule_task"},
                {"memory_doc", "store_id", "memory_store"},
                {"team_task", "team_session_id", "team_session"},
                {"team_run", "team_session_id", "team_session"},
                {"team_message", "team_session_id", "team_session"},
        };
        for (String[] c : children) {
            jdbc.update("DELETE FROM " + c[0] + " WHERE " + c[1] + " IN (SELECT id FROM " + c[2] + " WHERE space_id = ?)", id);
        }
        for (String table : List.of("agent", "chat_session", "agent_run", "skill", "tool_plugin", "credential", "hook",
                "schedule_task", "message_channel", "file_info", "memory_store", "model_provider", "sandbox",
                "agent_team", "team_session", "runtime_env", "space_default_env", "space_member", "space_role")) {
            jdbc.update("DELETE FROM " + table + " WHERE space_id = ?", id);
        }
        spaceMapper.deleteById(id);
        return R.ok();
    }

    // ---------------- 成员 ----------------

    @GetMapping("/members")
    public R<Map<String, Object>> members(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String role) {
        String sid = UserContext.spaceId();
        List<SpaceMember> all = memberMapper.selectList(new LambdaQueryWrapper<SpaceMember>()
                .eq(SpaceMember::getSpaceId, sid).orderByAsc(SpaceMember::getJoinedAt));
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("all", (long) all.size());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SpaceMember m : all) {
            counts.merge(m.getRoleCode(), 1L, Long::sum);
            User u = userMapper.selectById(m.getUserId());
            if (u == null) continue;
            if (!role.isBlank() && !role.equals(m.getRoleCode())) continue;
            if (!q.isBlank() && !(u.getUsername().contains(q) || u.getDisplayName().contains(q) || u.getEmail().contains(q))) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", m.getId());
            r.put("userId", u.getId());
            r.put("username", u.getUsername());
            r.put("displayName", u.getDisplayName());
            r.put("email", u.getEmail());
            r.put("avatar", u.getAvatar());
            r.put("roleCode", m.getRoleCode());
            r.put("joinedAt", m.getJoinedAt());
            rows.add(r);
        }
        return R.ok(Map.of("records", rows, "counts", counts));
    }

    @Data
    public static class AddMemberReq {
        private List<Long> userIds;
        @NotBlank private String roleCode;
    }

    @PostMapping("/members")
    @RequirePerm(Perm.MEMBER_MANAGE)
    public R<Void> addMembers(@Valid @RequestBody AddMemberReq req) {
        String sid = UserContext.spaceId();
        checkAssignableRole(sid, req.roleCode);
        for (Long uid : req.userIds) {
            if (userMapper.selectById(uid) == null) continue;
            boolean exists = memberMapper.selectCount(new LambdaQueryWrapper<SpaceMember>()
                    .eq(SpaceMember::getSpaceId, sid).eq(SpaceMember::getUserId, uid)) > 0;
            if (exists) continue;
            SpaceMember m = new SpaceMember();
            m.setSpaceId(sid);
            m.setUserId(uid);
            m.setRoleCode(req.roleCode);
            memberMapper.insert(m);
        }
        return R.ok();
    }

    @PutMapping("/members/{id}")
    @RequirePerm(Perm.MEMBER_MANAGE)
    public R<Void> changeRole(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String sid = UserContext.spaceId();
        SpaceMember m = memberMapper.selectById(id);
        if (m == null || !sid.equals(m.getSpaceId())) throw BizException.notFound("成员");
        BizException.check(!"owner".equals(m.getRoleCode()), "不能修改所有者的角色");
        checkManageable(m);
        String role = body.get("roleCode");
        checkAssignableRole(sid, role);
        m.setRoleCode(role);
        memberMapper.updateById(m);
        return R.ok();
    }

    @DeleteMapping("/members/{id}")
    @RequirePerm(Perm.MEMBER_MANAGE)
    public R<Void> remove(@PathVariable Long id) {
        SpaceMember m = memberMapper.selectById(id);
        if (m == null || !UserContext.spaceId().equals(m.getSpaceId())) throw BizException.notFound("成员");
        BizException.check(!"owner".equals(m.getRoleCode()), "不能移除空间所有者");
        checkManageable(m);
        memberMapper.deleteById(id);
        return R.ok();
    }

    @PostMapping("/members/leave")
    public R<Void> leave() {
        String sid = UserContext.spaceId();
        SpaceMember m = memberMapper.selectOne(new LambdaQueryWrapper<SpaceMember>()
                .eq(SpaceMember::getSpaceId, sid).eq(SpaceMember::getUserId, UserContext.userId()));
        BizException.check(m != null, "你不是该空间成员");
        BizException.check(!"owner".equals(m.getRoleCode()), "所有者不能退出空间，请先删除空间");
        memberMapper.deleteById(m.getId());
        return R.ok();
    }

    private void checkAssignableRole(String sid, String role) {
        BizException.check(!"owner".equals(role), "不能直接授予所有者角色");
        long n = roleMapper.selectCount(new LambdaQueryWrapper<SpaceRole>().eq(SpaceRole::getCode, role)
                .and(w -> w.eq(SpaceRole::getSpaceId, sid).or().isNull(SpaceRole::getSpaceId)));
        BizException.check(n > 0, "角色不存在");
        BizException.check(withinMyPerms(authInterceptor.loadPerms(sid, role)), "不能授予超出自己权限的角色");
    }

    /** 非所有者只能分配/管理不超过自身权限的角色，防止越权提升 */
    private boolean withinMyPerms(Collection<String> perms) {
        UserContext.Ctx me = UserContext.get();
        return "owner".equals(me.getRoleCode()) || me.getPerms().containsAll(perms);
    }

    private void checkManageable(SpaceMember m) {
        BizException.check(!m.getUserId().equals(UserContext.userId()), "不能修改自己的角色或移除自己，请使用「退出空间」");
        BizException.check(withinMyPerms(authInterceptor.loadPerms(m.getSpaceId(), m.getRoleCode())), "不能管理权限高于你的成员");
    }

    // ---------------- 角色（支持自定义） ----------------

    @GetMapping("/roles")
    public R<Map<String, Object>> roles() {
        String sid = UserContext.spaceId();
        List<SpaceRole> list = roleMapper.selectList(new LambdaQueryWrapper<SpaceRole>()
                .isNull(SpaceRole::getSpaceId).or().eq(SpaceRole::getSpaceId, sid)
                .orderByAsc(SpaceRole::getId));
        List<Map<String, String>> catalog = new ArrayList<>();
        Perm.ALL.forEach((k, v) -> catalog.add(Map.of("code", k, "name", v)));
        return R.ok(Map.of("roles", list, "permissions", catalog));
    }

    @Data
    public static class RoleReq {
        @NotBlank @jakarta.validation.constraints.Pattern(regexp = "^[a-z][a-z0-9_]{1,31}$", message = "须为小写字母开头的英文标识")
        private String code;
        @NotBlank private String name;
        private String description;
        private List<String> permissions;
    }

    @PostMapping("/roles")
    @RequirePerm(Perm.MEMBER_MANAGE)
    public R<SpaceRole> createRole(@Valid @RequestBody RoleReq req) {
        BizException.check(!Set.of("owner", "admin", "member", "guest").contains(req.code), "与系统角色重名");
        BizException.check(withinMyPerms(req.permissions == null ? List.of() : req.permissions), "不能创建超出自己权限的角色");
        SpaceRole r = new SpaceRole();
        r.setSpaceId(UserContext.spaceId());
        r.setCode(req.code);
        r.setName(req.name);
        r.setDescription(req.description);
        r.setPermissions(permsNode(req.permissions));
        r.setIsSystem(false);
        roleMapper.insert(r);
        return R.ok(r);
    }

    @PutMapping("/roles/{id}")
    @RequirePerm(Perm.MEMBER_MANAGE)
    public R<SpaceRole> updateRole(@PathVariable Long id, @RequestBody RoleReq req) {
        SpaceRole r = roleMapper.selectById(id);
        if (r == null || !UserContext.spaceId().equals(r.getSpaceId())) throw new BizException("系统角色不可修改");
        BizException.check(withinMyPerms(authInterceptor.loadPerms(r.getSpaceId(), r.getCode())), "不能修改权限高于你的角色");
        BizException.check(withinMyPerms(req.permissions == null ? List.of() : req.permissions), "不能授予超出自己权限的权限");
        if (req.name != null) r.setName(req.name);
        r.setDescription(req.description);
        if (req.permissions != null) r.setPermissions(permsNode(req.permissions));
        roleMapper.updateById(r);
        return R.ok(r);
    }

    @DeleteMapping("/roles/{id}")
    @RequirePerm(Perm.MEMBER_MANAGE)
    public R<Void> deleteRole(@PathVariable Long id) {
        String sid = UserContext.spaceId();
        SpaceRole r = roleMapper.selectById(id);
        if (r == null || !sid.equals(r.getSpaceId())) throw new BizException("系统角色不可删除");
        long used = memberMapper.selectCount(new LambdaQueryWrapper<SpaceMember>()
                .eq(SpaceMember::getSpaceId, sid).eq(SpaceMember::getRoleCode, r.getCode()));
        BizException.check(used == 0, "仍有 " + used + " 名成员使用该角色");
        roleMapper.deleteById(id);
        return R.ok();
    }

    private JsonNode permsNode(List<String> perms) {
        var arr = Json.arr();
        if (perms != null) perms.stream().filter(Perm.ALL::containsKey).distinct().forEach(arr::add);
        return arr;
    }

    /** 供前端刷新当前权限 */
    @GetMapping("/space/perms")
    public R<Set<String>> myPerms() {
        var ctx = UserContext.get();
        return R.ok(authInterceptor.loadPerms(UserContext.spaceId(), ctx.getRoleCode()));
    }
}
