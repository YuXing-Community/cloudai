package com.cloudai.common;

import lombok.Getter;

@Getter
public class BizException extends RuntimeException {
    private final int code;

    public BizException(String message) {
        this(400, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static BizException notFound(String what) {
        return new BizException(404, what + "不存在");
    }

    public static BizException forbidden() {
        return new BizException(403, "没有权限执行该操作");
    }

    public static void check(boolean ok, String message) {
        if (!ok) throw new BizException(message);
    }
}
