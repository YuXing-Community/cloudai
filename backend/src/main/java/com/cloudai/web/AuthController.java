package com.cloudai.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudai.auth.TokenService;
import com.cloudai.auth.UserContext;
import com.cloudai.common.BizException;
import com.cloudai.common.R;
import com.cloudai.entity.User;
import com.cloudai.mapper.CoreMappers.UserMapper;
import com.cloudai.service.SpaceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final UserMapper userMapper;
    private final TokenService tokenService;
    private final SpaceService spaceService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Data
    public static class RegisterReq {
        @NotBlank @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_.-]{2,31}$", message = "须以字母开头，3-32 位字母数字下划线")
        private String username;
        @NotBlank @Email(message = "邮箱格式不正确")
        private String email;
        @NotBlank @Size(max = 32)
        private String displayName;
        @NotBlank @Size(min = 6, max = 64, message = "密码至少 6 位")
        private String password;
    }

    @Data
    public static class LoginReq {
        @NotBlank private String account;
        @NotBlank private String password;
    }

    @PostMapping("/register")
    public R<Map<String, Object>> register(@Valid @RequestBody RegisterReq req) {
        BizException.check(userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, req.username)) == 0, "用户名已被注册");
        BizException.check(userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getEmail, req.email)) == 0, "邮箱已被注册");
        boolean first = userMapper.selectCount(null) == 0;
        User u = new User();
        u.setUsername(req.username);
        u.setEmail(req.email.toLowerCase());
        u.setDisplayName(req.displayName);
        u.setPasswordHash(encoder.encode(req.password));
        u.setIsAdmin(first);
        u.setStatus("active");
        userMapper.insert(u);
        // 每个用户注册后拥有一个个人空间
        spaceService.createSpace(u.getId(), req.displayName + " 的空间", "个人空间", "personal");
        return R.ok(Map.of("token", tokenService.issue(u.getId()), "user", u));
    }

    @PostMapping("/login")
    public R<Map<String, Object>> login(@Valid @RequestBody LoginReq req) {
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, req.account).or().eq(User::getEmail, req.account.toLowerCase()));
        if (u == null || !encoder.matches(req.password, u.getPasswordHash())) throw new BizException("账号或密码错误");
        BizException.check("active".equals(u.getStatus()), "账号已停用");
        u.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(u);
        return R.ok(Map.of("token", tokenService.issue(u.getId()), "user", u));
    }

    @GetMapping("/me")
    public R<User> me() {
        return R.ok(userMapper.selectById(UserContext.userId()));
    }

    @Data
    public static class ProfileReq {
        @Size(max = 32) private String displayName;
        private String avatar;
        private String oldPassword;
        @Size(min = 6, max = 64) private String newPassword;
    }

    @PutMapping("/me")
    public R<User> updateMe(@Valid @RequestBody ProfileReq req) {
        User u = userMapper.selectById(UserContext.userId());
        if (req.displayName != null && !req.displayName.isBlank()) u.setDisplayName(req.displayName);
        if (req.avatar != null) u.setAvatar(req.avatar);
        if (req.newPassword != null) {
            BizException.check(req.oldPassword != null && encoder.matches(req.oldPassword, u.getPasswordHash()), "原密码错误");
            u.setPasswordHash(encoder.encode(req.newPassword));
        }
        userMapper.updateById(u);
        return R.ok(u);
    }

    /** 搜索用户：用于添加成员、变更负责人 */
    @GetMapping("/users")
    public R<List<User>> searchUsers(@RequestParam(defaultValue = "") String q) {
        var w = new LambdaQueryWrapper<User>().eq(User::getStatus, "active");
        if (!q.isBlank()) {
            w.and(x -> x.like(User::getUsername, q).or().like(User::getDisplayName, q).or().like(User::getEmail, q));
        }
        return R.ok(userMapper.selectList(w.last("limit 20")));
    }
}
