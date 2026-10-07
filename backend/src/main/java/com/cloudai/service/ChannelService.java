package com.cloudai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudai.common.Crypto;
import com.cloudai.common.Json;
import com.cloudai.entity.Agent;
import com.cloudai.entity.ChatSession;
import com.cloudai.entity.OpsEntities.MessageChannel;
import com.cloudai.entity.AgentRun;
import com.cloudai.mapper.Mappers.AgentMapper;
import com.cloudai.mapper.Mappers.AgentRunMapper;
import com.cloudai.mapper.Mappers.ChatSessionMapper;
import com.cloudai.mapper.Mappers.MessageChannelMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/** 消息通道：飞书长连接生命周期（由执行器维护 WebSocket）+ 收到消息后的 Agent 执行 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelService implements ApplicationRunner {
    private final MessageChannelMapper channelMapper;
    private final AgentMapper agentMapper;
    private final ChatSessionMapper sessionMapper;
    private final AgentRunMapper runMapper;
    private final RunService runService;
    private final ExecutorClient executor;
    private final Crypto crypto;

    public void start(MessageChannel ch) {
        if (!"feishu".equals(ch.getType())) return;
        ObjectNode body = Json.obj().put("channel_id", ch.getId()).put("space_id", ch.getSpaceId())
                .put("app_id", ch.getAppId()).put("app_secret", crypto.decrypt(ch.getAppSecretEnc()));
        body.set("settings", ch.getSettings() == null ? Json.obj() : ch.getSettings());
        executor.call("POST", "/channels/feishu/start", body, 20);
    }

    public void stop(MessageChannel ch) {
        if (!"feishu".equals(ch.getType())) return;
        try {
            executor.call("POST", "/channels/feishu/stop", Json.obj().put("channel_id", ch.getId()), 10);
        } catch (Exception e) {
            log.warn("stop channel {} failed: {}", ch.getId(), e.getMessage());
        }
    }

    /** 启动后自动恢复所有活跃飞书通道的长连接（执行器可能晚于后端启动，失败由定时同步重试） */
    @Override
    public void run(ApplicationArguments args) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
                return;
            }
            restoreAll();
        }, "channel-restore");
        t.setDaemon(true);
        t.start();
    }

    private void restoreAll() {
        for (MessageChannel ch : channelMapper.selectList(new LambdaQueryWrapper<MessageChannel>()
                .eq(MessageChannel::getType, "feishu").in(MessageChannel::getStatus, "active", "error"))) {
            try {
                start(ch);
            } catch (Exception e) {
                log.info("restore channel {} deferred: {}", ch.getName(), e.getMessage());
            }
        }
    }

    /** 同步执行器中的连接状态；连接丢失（如执行器重启）时自动重连 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void sync() {
        JsonNode st;
        try {
            st = executor.call("GET", "/channels/status", null, 5).path("channels");
        } catch (Exception e) {
            return;
        }
        for (MessageChannel ch : channelMapper.selectList(new LambdaQueryWrapper<MessageChannel>()
                .eq(MessageChannel::getType, "feishu").in(MessageChannel::getStatus, "active", "error"))) {
            JsonNode s = st.path(ch.getId());
            String state = s.path("state").asText("missing");
            try {
                if ("missing".equals(state) || "stopped".equals(state)) {
                    start(ch);
                    continue;
                }
                String newStatus = "error".equals(state) ? "error" : "active";
                String msg = s.path("error").asText("");
                if (!newStatus.equals(ch.getStatus()) || !Objects.equals(msg, Objects.toString(ch.getStatusMessage(), ""))) {
                    ch.setStatus(newStatus);
                    ch.setStatusMessage(msg);
                    channelMapper.updateById(ch);
                }
                if ("error".equals(state)) start(ch);
            } catch (Exception e) {
                log.debug("sync channel {}: {}", ch.getId(), e.getMessage());
            }
        }
    }

    /**
     * 收到一条消息：按 chat（话题模式下按 chat+thread）复用会话，同步执行 Agent 并返回回复。
     */
    public Map<String, Object> handle(MessageChannel ch, String source, String chatKey, String text, String titlePrefix) {
        Map<String, Object> out = new LinkedHashMap<>();
        Agent agent = agentMapper.selectById(ch.getAgentId());
        if (agent == null || !"available".equals(agent.getStatus())) {
            out.put("reply", "绑定的 Agent 不存在或已归档，请联系管理员");
            out.put("status", "failed");
            return out;
        }
        ChatSession s = sessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getSpaceId, ch.getSpaceId()).eq(ChatSession::getAgentId, agent.getId())
                .eq(ChatSession::getSource, source).eq(ChatSession::getSourceRef, chatKey)
                .orderByDesc(ChatSession::getCreatedAt).last("limit 1"));
        if (s == null) {
            String shortKey = chatKey.length() > 14 ? chatKey.substring(0, 14) + "…" : chatKey;
            s = runService.createSession(agent, new RunService.SessionOptions(titlePrefix + " · " + shortKey, source, chatKey,
                    null, null, ch.getRuntimeEnv(), null));
        }
        if (runService.isRunning(s.getId())) {
            out.put("reply", "上一条消息仍在处理中，请稍候…");
            out.put("status", "busy");
            out.put("sessionId", s.getId());
            return out;
        }
        long t0 = System.currentTimeMillis();
        RunService.RunResult r = runService.sendAndWait(s, text, source, null, Duration.ofMinutes(30));
        boolean ok = "success".equals(r.status());
        String reply = ok ? (r.finalText() == null || r.finalText().isBlank() ? "（已完成，无文本回复）" : r.finalText())
                : "处理失败：" + Objects.toString(r.error(), "未知错误");
        out.put("reply", reply);
        out.put("status", r.status());
        out.put("sessionId", s.getId());
        JsonNode st = ch.getSettings();
        if (st != null && st.path("showFooter").asBoolean(false) && r.runId() != null) {
            AgentRun run = runMapper.selectById(r.runId());
            if (run != null) {
                out.put("footer", String.format("%s · %.1fs · %d tokens", Objects.toString(run.getModelRef(), ""),
                        (System.currentTimeMillis() - t0) / 1000.0, run.getInputTokens() + run.getOutputTokens()));
            }
        }
        return out;
    }
}
