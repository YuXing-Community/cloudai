package com.cloudai.auth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 权限点定义。系统角色 owner/admin/member/guest，空间可自定义角色组合这些权限点。 */
public final class Perm {
    private Perm() {}

    public static final String AGENT_VIEW = "agent:view";
    public static final String AGENT_EDIT = "agent:edit";
    public static final String SESSION_USE = "session:use";
    public static final String TEAM_VIEW = "team:view";
    public static final String TEAM_EDIT = "team:edit";
    public static final String SCHEDULE_VIEW = "schedule:view";
    public static final String SCHEDULE_EDIT = "schedule:edit";
    public static final String CHANNEL_VIEW = "channel:view";
    public static final String CHANNEL_EDIT = "channel:edit";
    public static final String FILE_VIEW = "file:view";
    public static final String FILE_EDIT = "file:edit";
    public static final String MEMORY_VIEW = "memory:view";
    public static final String MEMORY_EDIT = "memory:edit";
    public static final String USAGE_VIEW = "usage:view";
    public static final String PLUGIN_VIEW = "plugin:view";
    public static final String PLUGIN_EDIT = "plugin:edit";
    public static final String SANDBOX_VIEW = "sandbox:view";
    public static final String SANDBOX_EDIT = "sandbox:edit";
    public static final String SPACE_EDIT = "space:edit";
    public static final String SPACE_DELETE = "space:delete";
    public static final String MEMBER_MANAGE = "member:manage";
    public static final String MODEL_MANAGE = "model:manage";

    /** 权限点 -> 中文说明，按功能分组，前端角色编辑器使用 */
    public static final Map<String, String> ALL = new LinkedHashMap<>();
    static {
        ALL.put(AGENT_VIEW, "查看智能体");
        ALL.put(AGENT_EDIT, "创建/编辑智能体");
        ALL.put(SESSION_USE, "使用会话");
        ALL.put(TEAM_VIEW, "查看任务看板");
        ALL.put(TEAM_EDIT, "管理 Agent 小队与任务");
        ALL.put(SCHEDULE_VIEW, "查看定时任务");
        ALL.put(SCHEDULE_EDIT, "管理定时任务");
        ALL.put(CHANNEL_VIEW, "查看消息通道");
        ALL.put(CHANNEL_EDIT, "管理消息通道");
        ALL.put(FILE_VIEW, "查看文件中心");
        ALL.put(FILE_EDIT, "上传/删除文件");
        ALL.put(MEMORY_VIEW, "查看记忆库");
        ALL.put(MEMORY_EDIT, "管理记忆库");
        ALL.put(USAGE_VIEW, "查看用量");
        ALL.put(PLUGIN_VIEW, "查看插件（工具/Skills/Hooks/凭据）");
        ALL.put(PLUGIN_EDIT, "管理插件（工具/Skills/Hooks/凭据）");
        ALL.put(SANDBOX_VIEW, "查看沙箱与运行环境");
        ALL.put(SANDBOX_EDIT, "管理沙箱与运行环境");
        ALL.put(SPACE_EDIT, "修改空间基础信息");
        ALL.put(SPACE_DELETE, "删除空间");
        ALL.put(MEMBER_MANAGE, "成员与角色管理");
        ALL.put(MODEL_MANAGE, "模型管理");
    }

    private static final List<String> VIEW_ALL = List.of(AGENT_VIEW, TEAM_VIEW, SCHEDULE_VIEW, CHANNEL_VIEW,
            FILE_VIEW, MEMORY_VIEW, USAGE_VIEW, PLUGIN_VIEW, SANDBOX_VIEW);

    public static Set<String> ownerPerms() {
        return Set.copyOf(ALL.keySet());
    }

    public static Set<String> adminPerms() {
        var s = new java.util.HashSet<>(ALL.keySet());
        s.remove(SPACE_DELETE);
        return s;
    }

    public static Set<String> memberPerms() {
        var s = new java.util.HashSet<>(VIEW_ALL);
        s.addAll(List.of(AGENT_EDIT, SESSION_USE, TEAM_EDIT, SCHEDULE_EDIT, FILE_EDIT, MEMORY_EDIT, PLUGIN_EDIT, SANDBOX_EDIT));
        return s;
    }

    public static Set<String> guestPerms() {
        var s = new java.util.HashSet<>(VIEW_ALL);
        s.add(SESSION_USE);
        return s;
    }
}
