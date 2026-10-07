package com.cloudai.auth;

import com.cloudai.common.BizException;
import com.cloudai.common.Crypto;
import org.springframework.stereotype.Component;

/** 无状态 token：base64(userId.expireEpochSec).hmac */
@Component
public class TokenService {
    private static final long TTL_SECONDS = 7 * 24 * 3600;
    private final Crypto crypto;

    public TokenService(Crypto crypto) {
        this.crypto = crypto;
    }

    public String issue(long userId) {
        long exp = System.currentTimeMillis() / 1000 + TTL_SECONDS;
        String payload = userId + "." + exp;
        return payload + "." + crypto.hmac(payload);
    }

    /** 校验 token，返回 userId；无效时抛 401 */
    public long verify(String token) {
        if (token == null || token.isBlank()) throw new BizException(401, "未登录");
        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new BizException(401, "登录已失效");
        String payload = parts[0] + "." + parts[1];
        if (!crypto.hmac(payload).equals(parts[2])) throw new BizException(401, "登录已失效");
        long exp = Long.parseLong(parts[1]);
        if (exp < System.currentTimeMillis() / 1000) throw new BizException(401, "登录已过期，请重新登录");
        return Long.parseLong(parts[0]);
    }
}
