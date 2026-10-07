package com.cloudai.common;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<Void> biz(BizException e, HttpServletResponse resp) {
        int c = e.getCode();
        resp.setStatus(c == 401 || c == 403 || c == 404 || c == 413 ? c : 200);
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> invalid(MethodArgumentNotValidException e) {
        var fe = e.getBindingResult().getFieldError();
        return R.fail(400, fe == null ? "参数错误" : fe.getField() + " " + fe.getDefaultMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public R<Void> dup(DuplicateKeyException e) {
        return R.fail(409, "名称或标识已存在");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public R<Void> tooLarge(MaxUploadSizeExceededException e) {
        return R.fail(413, "文件超过 100MB 限制");
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void clientGone(AsyncRequestNotUsableException e) {
        // SSE 客户端断开，忽略
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public R<Void> notFound(Exception e, HttpServletResponse resp) {
        resp.setStatus(404);
        return R.fail(404, "接口不存在");
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public R<Void> method(Exception e, HttpServletResponse resp) {
        resp.setStatus(405);
        return R.fail(405, "不支持的请求方法");
    }

    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    public R<Void> badRequest(Exception e, HttpServletResponse resp) {
        resp.setStatus(400);
        return R.fail(400, "请求参数错误");
    }

    @ExceptionHandler(Exception.class)
    public R<Void> other(Exception e, HttpServletResponse resp) {
        log.error("unhandled error", e);
        resp.setStatus(500);
        return R.fail(500, "服务器错误: " + e.getMessage());
    }
}
