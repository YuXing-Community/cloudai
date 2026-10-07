package com.cloudai.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudai.auth.Perm;
import com.cloudai.auth.RequirePerm;
import com.cloudai.auth.UserContext;
import com.cloudai.common.BizException;
import com.cloudai.common.Crypto;
import com.cloudai.common.Json;
import com.cloudai.common.R;
import com.cloudai.entity.Agent;
import com.cloudai.entity.OpsEntities.MessageChannel;
import com.cloudai.mapper.Mappers.AgentMapper;
import com.cloudai.mapper.Mappers.MessageChannelMapper;
import com.cloudai.service.ChannelService;
import com.cloudai.service.ExecutorClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 消息通道：飞书（WebSocket 长连接）与 Webhook；以及执行器/外部回调入口 */
@RestController
@RequiredArgsConstructor
public class ChannelController {
    private final MessageChannelMapper mapper;
    private final AgentMapper agentMapper;
    private final ChannelService channelService;
    private final ExecutorClient executor;
    private final Crypto crypto;

    @GetMapping("/api/channels")
    @RequirePerm(Perm.CHANNEL_VIEW)
    public R<Map<String, Object>> list(@RequestParam(defaultValue = "feishu") String type) {
        String sid = UserContext.spaceId();
        List<MessageChannel> all = mapper.selectList(new LambdaQueryWrapper<MessageChannel>().eq(MessageChannel::getSpaceId, sid)
                .orderByDesc(MessageChannel::getCreatedAt));
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("feishu", all.stream().filter(c -> "feishu".equals(c.getType())).count());
        counts.put("webhook", all.stream().filter(c -> "webhook".equals(c.getType())).count());
        long active = all.stream().filter(c -> "active".equals(c.getStatus())).count();
        Map<String, Agent> agents = new HashMap<>();
        List<Map<String, Object>> rows = all.stream().filter(c -> type.equals(c.getType())).map(c -> {
            Map<String, Object> m = row(c);
            Agent a = agents.computeIfAbsent(c.getAgentId(), agentMapper::selectById);
            m.put("agentIdentifier", a == null ? "已删除" : a.getIdentifier());
            m.put("agentName", a == null ? "已删除" : a.getName());
            return m;
        }).toList();
        return R.ok(Map.of("records", rows, "counts", counts, "active", active));
    }

    private Map<String, Object> row(MessageChannel c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("type", c.getType());
        m.put("name", c.getName());
        m.put("appId", c.getAppId());
        m.put("botName", c.getBotName());
        m.put("agentId", c.getAgentId());
        m.put("runtimeEnv", c.getRuntimeEnv());
        m.put("settings", c.getSettings());
        m.put("webhookToken", c.getWebhookToken());
        m.put("status", c.getStatus());
        m.put("statusMessage", c.getStatusMessage());
        m.put("createdAt", c.getCreatedAt());
        return m;
    }

    @GetMapping("/api/channels/options")
    public R<List<Map<String, Object>>> options() {
        return R.ok(mapper.selectList(new LambdaQueryWrapper<MessageChannel>().eq(MessageChannel::getSpaceId, UserContext.spaceId()))
                .stream().map(c -> Map.<String, Object>of("id", c.getId(), "name", c.getName(), "type", c.getType(), "status", c.getStatus())).toList());
    }

    @Data
    public static class Req {
        private String type;
        private String name;
        private String appId;
        private String appSecret;
        private String agentId;
        private String runtimeEnv;
        private JsonNode settings;
    }

    @PostMapping("/api/channels")
    @RequirePerm(Perm.CHANNEL_EDIT)
    public R<Map<String, Object>> create(@RequestBody Req req) {
        String sid = UserContext.spaceId();
        BizException.check("feishu".equals(req.type) || "webhook".equals(req.type), "渠道类型错误");
        Agent a = agentMapper.selectById(req.agentId);
        BizException.check(a != null && sid.equals(a.getSpaceId()), "请选择当前空间的 Agent");
        MessageChannel c = new MessageChannel();
        c.setId(Json.uuid());
        c.setSpaceId(sid);
        c.setType(req.type);
        c.setAgentId(req.agentId);
        c.setRuntimeEnv(req.runtimeEnv);
        c.setCreatedBy(UserContext.userId());
        c.setStatus("active");
        if ("feishu".equals(req.type)) {
            BizException.check(req.appId != null && !req.appId.isBlank() && req.appSecret != null && !req.appSecret.isBlank(), "请填写 App ID 和 App Secret");
            long dup = mapper.selectCount(new LambdaQueryWrapper<MessageChannel>().eq(MessageChannel::getAppId, req.appId.trim()));
            BizException.check(dup == 0, "该飞书应用已绑定其他通道（一个应用只能建立一个长连接）");
            c.setAppId(req.appId.trim());
            c.setAppSecretEnc(crypto.encrypt(req.appSecret.trim()));
            c.setBotName(botName(req.appId.trim(), req.appSecret.trim()));
            c.setName(req.name == null || req.name.isBlank() ? c.getBotName() : req.name.trim());
            c.setSettings(settings(req.settings));
        } else {
            BizException.check(req.name != null && !req.name.isBlank(), "请填写名称");
            c.setName(req.name.trim());
            c.setWebhookToken(crypto.randomToken(24));
            c.setSettings(Json.obj());
        }
        mapper.insert(c);
        if ("feishu".equals(c.getType())) {
            try {
                channelService.start(c);
            } catch (BizException e) {
                c.setStatus("error");
                c.setStatusMessage(e.getMessage());
                mapper.updateById(c);
            }
        }
        return R.ok(row(c));
    }

    private String botName(String appId, String secret) {
        String q = "/feishu/bot-info?app_id=" + URLEncoder.encode(appId, StandardCharsets.UTF_8)
                + "&app_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        JsonNode r = executor.call("GET", q, null, 20);
        String n = r.path("bot_name").asText("");
        return n.isBlank() ? appId : n;
    }

    private ObjectNode settings(JsonNode in) {
        ObjectNode s = Json.obj().put("requireMention", true).put("filterAtAll", false).put("topicSession", false)
                .put("showFooter", false).put("finalOnly", false).put("showTyping", true).put("replyInThread", false);
        if (in != null && in.isObject()) in.fields().forEachRemaining(e -> {
            if (s.has(e.getKey())) s.put(e.getKey(), e.getValue().asBoolean());
        });
        return s;
    }

    @PutMapping("/api/channels/{id}")
    @RequirePerm(Perm.CHANNEL_EDIT)
    public R<Map<String, Object>> update(@PathVariable String id, @RequestBody Req req) {
        MessageChannel c = load(id);
        if (req.agentId != null) {
            Agent a = agentMapper.selectById(req.agentId);
            BizException.check(a != null && c.getSpaceId().equals(a.getSpaceId()), "请选择当前空间的 Agent");
            c.setAgentId(req.agentId);
        }
        if (req.runtimeEnv != null) c.setRuntimeEnv(req.runtimeEnv);
        if (req.name != null && !req.name.isBlank()) c.setName(req.name.trim());
        boolean restart = false;
        if ("feishu".equals(c.getType())) {
            if (req.appId != null && !req.appId.isBlank() && !req.appId.trim().equals(c.getAppId())) {
                long dup = mapper.selectCount(new LambdaQueryWrapper<MessageChannel>().eq(MessageChannel::getAppId, req.appId.trim()).ne(MessageChannel::getId, c.getId()));
                BizException.check(dup == 0, "该飞书应用已绑定其他通道（一个应用只能建立一个长连接）");
                c.setAppId(req.appId.trim());
                restart = true;
            }
            if (req.appSecret != null && !req.appSecret.isBlank()) {
                c.setAppSecretEnc(crypto.encrypt(req.appSecret.trim()));
                restart = true;
            }
            if (restart) c.setBotName(botName(c.getAppId(), crypto.decrypt(c.getAppSecretEnc())));
            if (req.settings != null) {
                c.setSettings(settings(req.settings));
                restart = true;
            }
        }
        mapper.updateById(c);
        // 配置变更后重连；之前连接失败（error）的通道也借此机会重试
        if (restart && ("active".equals(c.getStatus()) || "error".equals(c.getStatus()))) {
            channelService.stop(c);
            connect(c);
        }
        return R.ok(row(c));
    }

    @PutMapping("/api/channels/{id}/status")
    @RequirePerm(Perm.CHANNEL_EDIT)
    public R<Void> status(@PathVariable String id, @RequestBody Map<String, String> body) {
        MessageChannel c = load(id);
        String s = body.get("status");
        BizException.check("active".equals(s) || "disabled".equals(s), "状态错误");
        c.setStatus(s);
        c.setStatusMessage(null);
        mapper.updateById(c);
        if ("active".equals(s)) connect(c);
        else channelService.stop(c);
        BizException.check(!"error".equals(c.getStatus()), "启用失败：" + c.getStatusMessage());
        return R.ok();
    }

    /** 建立飞书长连接，并把结果写回通道状态 */
    private void connect(MessageChannel c) {
        if (!"feishu".equals(c.getType())) return;
        try {
            channelService.start(c);
            c.setStatus("active");
            c.setStatusMessage(null);
        } catch (BizException e) {
            c.setStatus("error");
            c.setStatusMessage(e.getMessage());
        }
        mapper.updateById(c);
    }

    @DeleteMapping("/api/channels/{id}")
    @RequirePerm(Perm.CHANNEL_EDIT)
    public R<Void> delete(@PathVariable String id) {
        MessageChannel c = load(id);
        channelService.stop(c);
        mapper.deleteById(c.getId());
        return R.ok();
    }

    // ------------------------------------------------------------------ 回调入口

    /** 执行器收到飞书消息后回调（X-Internal-Token 校验在拦截器） */
    @PostMapping("/api/internal/channels/{id}/message")
    public R<Map<String, Object>> internalMessage(@PathVariable String id, @RequestBody Map<String, Object> body) {
        MessageChannel c = mapper.selectById(id);
        if (c == null || !"active".equals(c.getStatus()) && !"error".equals(c.getStatus())) throw BizException.notFound("通道");
        String chatId = String.valueOf(body.getOrDefault("chat_id", ""));
        Object thread = body.get("thread_id");
        boolean topic = c.getSettings() != null && c.getSettings().path("topicSession").asBoolean(false);
        String key = topic && thread != null && !String.valueOf(thread).isBlank() ? chatId + "#" + thread : chatId;
        String text = String.valueOf(body.getOrDefault("text", "")).trim();
        BizException.check(!text.isEmpty(), "消息为空");
        return R.ok(channelService.handle(c, "feishu", key, text, "飞书会话"));
    }

    /** Webhook：公开地址，凭 token 识别通道，同步返回 Agent 回复 */
    @PostMapping("/api/webhooks/{token}")
    public R<Map<String, Object>> webhook(@PathVariable String token, @RequestBody(required = false) Map<String, Object> body) {
        MessageChannel c = mapper.selectOne(new LambdaQueryWrapper<MessageChannel>().eq(MessageChannel::getWebhookToken, token));
        if (c == null || !"webhook".equals(c.getType())) throw BizException.notFound("Webhook");
        BizException.check("active".equals(c.getStatus()), "该 Webhook 已禁用");
        Map<String, Object> b = body == null ? Map.of() : body;
        Object t = b.getOrDefault("text", b.getOrDefault("message", b.get("content")));
        BizException.check(t != null && !String.valueOf(t).isBlank(), "请求体需要 text 字段");
        String key = String.valueOf(b.getOrDefault("sessionKey", "default"));
        return R.ok(channelService.handle(c, "webhook", c.getId() + ":" + key, String.valueOf(t), "Webhook"));
    }

    private MessageChannel load(String id) {
        MessageChannel c = mapper.selectById(id);
        if (c == null || !UserContext.spaceId().equals(c.getSpaceId())) throw BizException.notFound("通道");
        return c;
    }
}
