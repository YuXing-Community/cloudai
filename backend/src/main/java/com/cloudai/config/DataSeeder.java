package com.cloudai.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudai.auth.Perm;
import com.cloudai.common.Json;
import com.cloudai.entity.SpaceRole;
import com.cloudai.mapper.CoreMappers.SpaceRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** 启动时初始化系统角色、系统运行环境和 data 目录 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {
    private final SpaceRoleMapper roleMapper;
    private final JdbcTemplate jdbc;
    private final AppProperties props;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        seedRole("owner", "所有者", "空间所有者，拥有全部权限", Perm.ownerPerms());
        seedRole("admin", "管理员", "管理空间资源与成员", Perm.adminPerms());
        seedRole("member", "成员", "可创建和使用 Agent 等资源", Perm.memberPerms());
        seedRole("guest", "访客", "只读，可使用会话", Perm.guestPerms());

        Integer envs = jdbc.queryForObject("SELECT COUNT(*) FROM runtime_env WHERE is_system = 1", Integer.class);
        if (envs != null && envs == 0) {
            jdbc.update("""
                INSERT INTO runtime_env (space_id, name, description, is_system, platform_only, template, usage_type,
                  resource_group, prewarm, network_policy, packages, metadata, version, status)
                VALUES (NULL, 'cloudai-cli-runtime', '支持 agent-cli，内置 Bash/文件工具与 Python 依赖', 1, 1, 'cloudai-cli-runtime', 'agent',
                  'local', 2, 'default', ?, ?, 1, 'available')""",
                    Json.write(List.of(
                            pkg("pip", "PyYAML"), pkg("pip", "openpyxl"), pkg("pip", "duckdb"), pkg("pip", "pandas"),
                            pkg("npm", "@anthropic-ai/claude-code"))),
                    Json.write(List.of(meta("system.cfg/sandbox-type", "agent-runtime", true),
                            meta("runtime-csi", "enabled", false), meta("space-default-env", "true", false))));
            jdbc.update("""
                INSERT INTO runtime_env (space_id, name, description, is_system, platform_only, template, usage_type,
                  resource_group, prewarm, network_policy, packages, metadata, version, status)
                VALUES (NULL, 'cloudai-sandbox-default', '默认沙箱环境，本地隔离工作目录', 1, 1, 'cloudai-sandbox-default', 'sandbox',
                  'local', 3, 'default', ?, ?, 1, 'available')""",
                    Json.write(List.of(pkg("pip", "requests"), pkg("pip", "pandas"), pkg("apt", "curl"), pkg("apt", "git"))),
                    Json.write(List.of(meta("system.cfg/sandbox-type", "sandbox", true))));
        }

        for (String d : List.of("skills", "files", "memory", "agents", "workspaces")) {
            Files.createDirectories(props.dataRoot().resolve(d));
        }
        log.info("CloudAI data dir: {}", props.dataRoot());
    }

    private void seedRole(String code, String name, String desc, Set<String> perms) {
        SpaceRole r = roleMapper.selectOne(new LambdaQueryWrapper<SpaceRole>().isNull(SpaceRole::getSpaceId).eq(SpaceRole::getCode, code));
        var arr = Json.arr();
        new TreeSet<>(perms).forEach(arr::add);
        if (r == null) {
            r = new SpaceRole();
            r.setCode(code);
            r.setName(name);
            r.setDescription(desc);
            r.setPermissions(arr);
            r.setIsSystem(true);
            roleMapper.insert(r);
        } else {
            // 系统角色权限随版本更新
            r.setPermissions(arr);
            roleMapper.updateById(r);
        }
    }

    private static java.util.Map<String, Object> pkg(String manager, String name) {
        return java.util.Map.of("manager", manager, "name", name, "version", "latest");
    }

    private static java.util.Map<String, Object> meta(String k, String v, boolean system) {
        return java.util.Map.of("key", k, "value", v, "system", system);
    }
}
