package com.cloudai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudai.auth.UserContext;
import com.cloudai.common.BizException;
import com.cloudai.common.Json;
import com.cloudai.entity.Space;
import com.cloudai.entity.SpaceMember;
import com.cloudai.entity.User;
import com.cloudai.mapper.CoreMappers.SpaceMapper;
import com.cloudai.mapper.CoreMappers.SpaceMemberMapper;
import com.cloudai.mapper.CoreMappers.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class SpaceService {
    private final SpaceMapper spaceMapper;
    private final SpaceMemberMapper memberMapper;
    private final UserMapper userMapper;

    @Transactional
    public Space createSpace(long ownerId, String name, String description, String type) {
        Space s = new Space();
        s.setId(Json.uuid());
        s.setName(name);
        s.setDescription(description);
        s.setType(type);
        s.setCustomEnvEnabled(false);
        s.setOwnerId(ownerId);
        spaceMapper.insert(s);
        SpaceMember m = new SpaceMember();
        m.setSpaceId(s.getId());
        m.setUserId(ownerId);
        m.setRoleCode("owner");
        memberMapper.insert(m);
        return s;
    }

    public Space current() {
        Space s = spaceMapper.selectById(UserContext.spaceId());
        if (s == null) throw BizException.notFound("空间");
        return s;
    }

    public long memberCount(String spaceId) {
        return memberMapper.selectCount(new LambdaQueryWrapper<SpaceMember>().eq(SpaceMember::getSpaceId, spaceId));
    }

    /** 用户 id -> 简要信息，供列表展示负责人等 */
    public Map<String, Object> userBrief(Long userId) {
        if (userId == null) return null;
        User u = userMapper.selectById(userId);
        if (u == null) return Map.of("id", userId, "displayName", "未知用户", "username", "");
        return Map.of("id", u.getId(), "username", u.getUsername(), "displayName", u.getDisplayName(),
                "email", u.getEmail(), "avatar", u.getAvatar() == null ? "" : u.getAvatar());
    }
}
