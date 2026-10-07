package com.cloudai.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudai.common.BizException;
import com.cloudai.entity.SpaceMember;
import com.cloudai.entity.SpaceRole;
import com.cloudai.entity.User;
import com.cloudai.mapper.CoreMappers.SpaceMemberMapper;
import com.cloudai.mapper.CoreMappers.SpaceRoleMapper;
import com.cloudai.mapper.CoreMappers.UserMapper;
import com.cloudai.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.HashSet;
import java.util.Set;

/**
 * 鉴权 + 空间隔离：
 * 1. 校验 Authorization: Bearer token（SSE 等无法带头的场景可用 ?token=）
 * 2. 解析 X-Space-Id（或 ?spaceId=），校验当前用户是该空间成员，加载角色权限
 * 3. 校验 @RequirePerm
 * 内部接口 /api/internal/** 使用 X-Internal-Token 校验，供执行器回调。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {
    private final TokenService tokenService;
    private final UserMapper userMapper;
    private final SpaceMemberMapper memberMapper;
    private final SpaceRoleMapper roleMapper;
    private final AppProperties props;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) return true;
        // 以 Spring 实际匹配到的路由模式判断（而非原始 URI），避免 /api/internal;x/... 之类的路径绕过内部鉴权
        Object best = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path = best != null ? best.toString() : req.getRequestURI();
        if (req.getRequestURI().contains(";")) throw new BizException(400, "非法请求路径");
        if (path.startsWith("/api/internal/")) {
            if (!props.getInternalToken().equals(req.getHeader("X-Internal-Token"))) throw BizException.forbidden();
            String spaceId = req.getHeader("X-Space-Id");
            UserContext.set(new UserContext.Ctx(0L, "system", "system", true, spaceId, "owner", Perm.ownerPerms()));
            return true;
        }

        String token = null;
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) token = auth.substring(7);
        if (token == null) token = req.getParameter("token");
        long userId = tokenService.verify(token);
        User user = userMapper.selectById(userId);
        if (user == null || !"active".equals(user.getStatus())) throw new BizException(401, "账号不存在或已停用");

        UserContext.Ctx ctx = new UserContext.Ctx(user.getId(), user.getUsername(), user.getDisplayName(),
                Boolean.TRUE.equals(user.getIsAdmin()), null, null, Set.of());

        String spaceId = req.getHeader("X-Space-Id");
        if (spaceId == null || spaceId.isBlank()) spaceId = req.getParameter("spaceId");
        // 账号级接口（我的信息、空间列表、新建空间、分享对话）不依赖当前空间：忽略可能已失效的空间头
        boolean spaceless = path.startsWith("/api/auth/") || path.equals("/api/spaces") || path.startsWith("/api/share/");
        if (spaceless) spaceId = null;
        if (spaceId != null && !spaceId.isBlank()) {
            SpaceMember m = memberMapper.selectOne(new LambdaQueryWrapper<SpaceMember>()
                    .eq(SpaceMember::getSpaceId, spaceId).eq(SpaceMember::getUserId, userId));
            if (m == null) throw new BizException(403, "你不是该空间成员");
            ctx.setSpaceId(spaceId);
            ctx.setRoleCode(m.getRoleCode());
            ctx.setPerms(loadPerms(spaceId, m.getRoleCode()));
        }
        UserContext.set(ctx);

        if (handler instanceof HandlerMethod hm) {
            RequirePerm rp = hm.getMethodAnnotation(RequirePerm.class);
            if (rp == null) rp = hm.getBeanType().getAnnotation(RequirePerm.class);
            if (rp != null) {
                if (ctx.getSpaceId() == null) throw new BizException(400, "请先选择空间");
                boolean ok = false;
                for (String p : rp.value()) ok |= ctx.getPerms().contains(p);
                if (!ok) throw BizException.forbidden();
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp, Object handler, Exception ex) {
        UserContext.clear();
    }

    public Set<String> loadPerms(String spaceId, String roleCode) {
        SpaceRole role = roleMapper.selectOne(new LambdaQueryWrapper<SpaceRole>()
                .eq(SpaceRole::getCode, roleCode)
                .and(w -> w.eq(SpaceRole::getSpaceId, spaceId).or().isNull(SpaceRole::getSpaceId))
                .orderByDesc(SpaceRole::getSpaceId)
                .last("limit 1"));
        Set<String> perms = new HashSet<>();
        if (role != null && role.getPermissions() != null) role.getPermissions().forEach(n -> perms.add(n.asText()));
        return perms;
    }
}
