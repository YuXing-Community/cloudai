package com.cloudai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudai.entity.Space;
import com.cloudai.entity.SpaceMember;
import com.cloudai.entity.SpaceRole;
import com.cloudai.entity.User;
import org.apache.ibatis.annotations.Mapper;

public final class CoreMappers {
    private CoreMappers() {}

    @Mapper
    public interface UserMapper extends BaseMapper<User> {}

    @Mapper
    public interface SpaceMapper extends BaseMapper<Space> {}

    @Mapper
    public interface SpaceRoleMapper extends BaseMapper<SpaceRole> {}

    @Mapper
    public interface SpaceMemberMapper extends BaseMapper<SpaceMember> {}
}
