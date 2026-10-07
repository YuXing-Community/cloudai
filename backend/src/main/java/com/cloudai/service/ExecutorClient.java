package com.cloudai.service;

import com.cloudai.common.BizException;
import com.cloudai.common.Json;
import com.cloudai.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/** 调用 Python 执行器（FastAPI，默认 :8590） */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutorClient {
    private final AppProperties props;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /** 流式执行：逐条回调事件，直到执行器关闭流 */
    public void stream(String path, Object body, Consumer<JsonNode> onEvent) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.getExecutorUrl() + path))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("X-Internal-Token", props.getInternalToken())
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new BizException("执行器返回 " + resp.statusCode() + ": " + err);
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            StringBuilder data = new StringBuilder();
            while ((line = r.readLine()) != null) {
                if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                } else if (line.isEmpty() && data.length() > 0) {
                    onEvent.accept(Json.read(data.toString()));
                    data.setLength(0);
                }
            }
            if (data.length() > 0) onEvent.accept(Json.read(data.toString()));
        }
    }

    /** 普通 JSON 调用，返回 data */
    public JsonNode call(String method, String path, Object body, int timeoutSeconds) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(props.getExecutorUrl() + path))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", props.getInternalToken());
            if ("GET".equals(method)) b.GET();
            else if ("DELETE".equals(method)) b.DELETE();
            else b.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "{}" : Json.write(body), StandardCharsets.UTF_8));
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode n = Json.read(resp.body());
            if (resp.statusCode() >= 400) {
                String detail = n == null ? resp.body() : n.path("detail").asText(resp.body());
                throw new BizException("执行器错误: " + detail);
            }
            return n;
        } catch (BizException e) {
            throw e;
        } catch (java.net.ConnectException e) {
            throw new BizException("无法连接 Agent 执行器（" + props.getExecutorUrl() + "），请确认 executor 服务已启动");
        } catch (Exception e) {
            throw new BizException("调用执行器失败: " + e.getMessage());
        }
    }

    public boolean healthy() {
        try {
            call("GET", "/health", null, 3);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
