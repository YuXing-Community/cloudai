package com.cloudai.auth;

import com.cloudai.common.BizException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/** 当前请求的用户与空间上下文 */
public final class UserContext {
    private static final ThreadLocal<Ctx> HOLDER = new ThreadLocal<>();

    private UserContext() {}

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Ctx {
        private Long userId;
        private String username;
        private String displayName;
        private boolean admin;
        private String spaceId;
        private String roleCode;
        private Set<String> perms;
    }

    public static void set(Ctx c) {
        HOLDER.set(c);
    }

    public static Ctx get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static Long userId() {
        Ctx c = HOLDER.get();
        if (c == null) throw new BizException(401, "未登录");
        return c.getUserId();
    }

    public static String username() {
        Ctx c = HOLDER.get();
        return c == null ? "system" : c.getUsername();
    }

    /** 当前空间 ID，请求头 X-Space-Id；未选择空间时报错 */
    public static String spaceId() {
        Ctx c = HOLDER.get();
        if (c == null || c.getSpaceId() == null) throw new BizException(400, "请先选择空间");
        return c.getSpaceId();
    }

    public static boolean has(String perm) {
        Ctx c = HOLDER.get();
        return c != null && c.getPerms() != null && c.getPerms().contains(perm);
    }

    public static void require(String perm) {
        if (!has(perm)) throw BizException.forbidden();
    }
}
