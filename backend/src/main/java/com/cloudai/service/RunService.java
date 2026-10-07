package com.cloudai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cloudai.common.BizException;
import com.cloudai.common.Json;
import com.cloudai.entity.*;
import com.cloudai.entity.OpsEntities.FileInfo;
import com.cloudai.entity.PluginEntities.Skill;
import com.cloudai.entity.RuntimeEntities.Sandbox;
import com.cloudai.mapper.CoreMappers.UserMapper;
import com.cloudai.mapper.Mappers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 执行编排：创建会话 → 写入用户输入 → 调用执行器 → 事件逐条落库并实时推送 → 汇总 agent_run。
 * 状态全部持久化在 MySQL，浏览器断开不影响执行，重新打开会话可完整回放。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunService {
    private final ChatSessionMapper sessionMapper;
    private final SessionEventMapper eventMapper;
    private final AgentRunMapper runMapper;
    private final AgentMapper agentMapper;
    private final SkillMapper skillMapper;
    private final FileInfoMapper fileMapper;
    private final SandboxMapper sandboxMapper;
    private final UserMapper userMapper;
    private final AgentResolver resolver;
    private final ModelService modelService;
    private final ExecutorClient executor;
    private final SessionHub hub;
    private final Storage storage;

    private final ExecutorService pool = Executors.newFixedThreadPool(32, r -> {
        Thread t = new Thread(r, "agent-run");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, AtomicInteger> seqs = new ConcurrentHashMap<>();
    /** sessionId -> 正在执行的 runId */
    private final Map<String, Long> running = new ConcurrentHashMap<>();

    public record RunResult(String status, String finalText, String error, String sessionId, Long runId) {}

    public record SessionOptions(String title, String source, String sourceRef, Long creatorId, String modelRef,
                                 String runtimeEnv, Integer agentVersion) {}

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------ 会话

    public ChatSession createSession(Agent agent, SessionOptions o) {
        JsonNode cfg = resolver.config(agent, o.agentVersion());
        ChatSession s = new ChatSession();
        s.setId(Json.uuid());
        s.setSpaceId(agent.getSpaceId());
        s.setAgentId(agent.getId());
        s.setAgentVersion(o.agentVersion() == null ? agent.getVersion() : o.agentVersion());
        s.setTitle(o.title() == null || o.title().isBlank() ? "新会话" : truncate(o.title(), 120));
        s.setSource(o.source() == null ? "web" : o.source());
        s.setSourceRef(o.sourceRef());
        s.setStatus("idle");
        String model = o.modelRef();
        if (model == null || model.isBlank()) model = cfg.path("model").path("primary").asText(agent.getModelRef());
        s.setModelRef(model);
        s.setRuntimeKind(cfg.path("runtime").path("kind").asText("claudecode"));
        s.setRuntimeEnv(o.runtimeEnv() == null || o.runtimeEnv().isBlank()
                ? cfg.path("runtime").path("env").asText("cloudai-cli-runtime") : o.runtimeEnv());
        String wsKey = cfg.path("runtime").path("sandboxIsolation").asBoolean(false) && o.creatorId() != null
                ? "user-" + o.creatorId() + "-" + agent.getId() : s.getId();
        s.setWorkspaceDir(storage.rel(storage.workspaceDir(wsKey)));
        s.setCreatorId(o.creatorId());
        s.setInputTokens(0L);
        s.setOutputTokens(0L);
        s.setCacheRead(0L);
        s.setCacheWrite(0L);
        s.setContextTokens(0L);
        s.setLastEventAt(LocalDateTime.now());
        sessionMapper.insert(s);
        return s;
    }

    public boolean isRunning(String sessionId) {
        return running.containsKey(sessionId);
    }

    // ------------------------------------------------------------------ 执行

    /**
     * 发送一条用户消息并异步执行。
     * @param fileIds 文件中心里的附件，会复制到会话工作区 attachments/ 下
     */
    public CompletableFuture<RunResult> send(ChatSession s, String text, List<String> fileIds, String trigger, Long userId) {
        BizException.check(text != null && !text.isBlank(), "消息不能为空");
        Agent agent = agentMapper.selectById(s.getAgentId());
        if (agent == null) throw BizException.notFound("Agent");
        if (running.putIfAbsent(s.getId(), -1L) != null) throw new BizException("会话正在执行中，请等待完成或先停止");

        try {
            Path ws = storage.abs(s.getWorkspaceDir());
            Files.createDirectories(ws);
            StringBuilder msg = new StringBuilder(text);
            if (fileIds != null && !fileIds.isEmpty()) {
                Path att = ws.resolve("attachments");
                Files.createDirectories(att);
                msg.append("\n\n[用户上传的附件，已放在工作目录]");
                for (String fid : fileIds) {
                    FileInfo f = fileMapper.selectById(fid);
                    if (f == null || !s.getSpaceId().equals(f.getSpaceId())) continue;
                    Path dst = att.resolve(Storage.safe(f.getFileName()));
                    Files.copy(storage.abs(f.getStoragePath()), dst, StandardCopyOption.REPLACE_EXISTING);
                    msg.append("\n- attachments/").append(dst.getFileName()).append(" (").append(f.getMimeType()).append(")");
                    if (f.getSessionId() == null) {
                        f.setSessionId(s.getId());
                        fileMapper.updateById(f);
                    }
                }
            }

            // 构造执行器 payload（模型/工具/Skills/记忆/SubAgents/环境变量），失败直接报错给用户
            JsonNode cfg = resolver.config(agent, s.getAgentVersion());
            ObjectNode payload = resolver.resolve(agent, cfg, s.getModelRef(), 0);
            ArrayNode history = buildHistory(s.getId());

            SessionEvent input = newEvent(s.getId(), null, "input", null);
            input.setContent(msg.toString());
            eventMapper.insert(input);
            hub.publishEvent(s.getId(), input);

            AgentRun run = new AgentRun();
            run.setSpaceId(s.getSpaceId());
            run.setAgentId(agent.getId());
            run.setSessionId(s.getId());
            run.setUserId(userId);
            run.setTriggerType(trigger);
            run.setModelRef(payload.path("models").path(0).path("ref").asText());
            run.setStatus("running");
            run.setInputTokens(0L);
            run.setOutputTokens(0L);
            run.setCacheRead(0L);
            run.setCacheWrite(0L);
            run.setTurns(0);
            run.setToolCalls(0);
            run.setDurationMs(0L);
            run.setStartedAt(LocalDateTime.now());
            runMapper.insert(run);
            running.put(s.getId(), run.getId());
            input.setRunId(run.getId());
            eventMapper.updateById(input);

            if ("新会话".equals(s.getTitle())) s.setTitle(truncate(text.replaceAll("\\s+", " "), 60));
            s.setStatus("running");
            s.setLastEventAt(LocalDateTime.now());
            sessionMapper.update(null, new LambdaUpdateWrapper<ChatSession>().eq(ChatSession::getId, s.getId())
                    .set(ChatSession::getTitle, s.getTitle()).set(ChatSession::getStatus, "running")
                    .set(ChatSession::getLastEventAt, s.getLastEventAt()));
            hub.publishSession(s.getId(), s);
            touchSandbox(s, agent, userId);

            payload.put("run_id", run.getId());
            payload.put("session_id", s.getId());
            payload.put("space_id", s.getSpaceId());
            payload.put("workspace_dir", ws.toString());
            payload.put("agent_memory_dir", storage.agentDir(s.getSpaceId(), agent.getId()).resolve("memory").toString());
            payload.set("history", history);
            payload.put("message", msg.toString());
            payload.set("internal", resolver.internalInfo(s.getSpaceId()));

            return CompletableFuture.supplyAsync(() -> execute(s.getId(), run.getId(), payload), pool);
        } catch (Exception e) {
            running.remove(s.getId());
            if (e instanceof BizException be) throw be;
            throw new BizException("启动执行失败: " + e.getMessage());
        }
    }

    /** 同步执行（定时任务、消息通道、Team 使用） */
    public RunResult sendAndWait(ChatSession s, String text, String trigger, Long userId, Duration timeout) {
        try {
            return send(s, text, null, trigger, userId).get(timeout.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            stop(s.getId());
            return new RunResult("failed", null, "执行超时（" + timeout.toMinutes() + " 分钟）", s.getId(), null);
        } catch (BizException e) {
            return new RunResult("failed", null, e.getMessage(), s.getId(), null);
        } catch (Exception e) {
            return new RunResult("failed", null, String.valueOf(e.getMessage()), s.getId(), null);
        }
    }

    public void stop(String sessionId) {
        Long runId = running.get(sessionId);
        if (runId == null || runId < 0) return;
        try {
            executor.call("POST", "/runs/" + runId + "/cancel", null, 5);
        } catch (Exception e) {
            log.warn("cancel run {} failed: {}", runId, e.getMessage());
        }
    }

    private RunResult execute(String sessionId, long runId, ObjectNode payload) {
        long t0 = System.currentTimeMillis();
        Map<String, SessionEvent> toolEvents = new HashMap<>();
        Set<String> skillsUsed = new LinkedHashSet<>();
        long[] usage = new long[5]; // in, out, cacheRead, cacheWrite, lastContext
        int[] counters = new int[2]; // turns, toolCalls
        String[] fin = {"failed", null, null}; // status, finalText, error
        String spaceId = payload.path("space_id").asText();

        try {
            executor.stream("/run", payload, ev -> {
                String type = ev.path("type").asText();
                switch (type) {
                    case "model" -> {
                        SessionEvent e = newEvent(sessionId, runId, "model", ev.path("name").asText());
                        e.setInputTokens(ev.path("input_tokens").asInt());
                        e.setOutputTokens(ev.path("output_tokens").asInt());
                        e.setDurationMs(ev.path("duration_ms").asLong());
                        e.setContent(Json.write(ev));
                        save(e);
                        usage[0] += ev.path("input_tokens").asLong();
                        usage[1] += ev.path("output_tokens").asLong();
                        usage[2] += ev.path("cache_read").asLong();
                        usage[3] += ev.path("cache_write").asLong();
                        usage[4] = ev.path("input_tokens").asLong();
                        counters[0]++;
                    }
                    case "thinking", "agent", "error" -> {
                        SessionEvent e = newEvent(sessionId, runId, type, ev.path("name").asText(null));
                        e.setContent(ev.path("content").asText());
                        e.setDurationMs(ev.path("duration_ms").asLong());
                        if ("error".equals(type)) e.setStatus("error");
                        save(e);
                    }
                    case "tool_start" -> {
                        SessionEvent e = newEvent(sessionId, runId, "tool", ev.path("name").asText());
                        e.setContent(ev.has("args") ? Json.write(ev.get("args")) : "{}");
                        e.setStatus("running");
                        e.setStartedAt(LocalDateTime.now());
                        save(e);
                        toolEvents.put(ev.path("call_id").asText(), e);
                        counters[1]++;
                    }
                    case "tool_end" -> {
                        SessionEvent e = toolEvents.get(ev.path("call_id").asText());
                        if (e == null) {
                            e = newEvent(sessionId, runId, "tool", ev.path("name").asText());
                            e.setContent("{}");
                            eventMapper.insert(e);
                        }
                        e.setResult(truncate(ev.path("result").asText(), 200_000));
                        e.setStatus(ev.path("status").asText("ok"));
                        e.setDurationMs(ev.path("duration_ms").asLong());
                        eventMapper.updateById(e);
                        hub.publishEvent(sessionId, e);
                    }
                    case "artifact" -> saveArtifact(spaceId, sessionId, ev);
                    case "skill_used" -> skillsUsed.add(ev.path("name").asText());
                    case "done" -> {
                        fin[0] = ev.path("status").asText("success");
                        fin[1] = ev.path("final_text").asText(null);
                        fin[2] = ev.path("error").asText(null);
                    }
                    default -> log.debug("unknown executor event {}", type);
                }
            });
        } catch (java.net.ConnectException e) {
            fin[2] = "无法连接 Agent 执行器，请确认 executor 服务已启动";
        } catch (Exception e) {
            log.warn("run {} failed", runId, e);
            fin[2] = e.getMessage() == null ? e.toString() : e.getMessage();
        }

        if (fin[2] != null && !"success".equals(fin[0])) {
            SessionEvent e = newEvent(sessionId, runId, "error", null);
            e.setContent(fin[2]);
            e.setStatus("error");
            save(e);
        }
        // 未收到 tool_end 的工具调用标记为失败
        for (SessionEvent e : toolEvents.values()) {
            if ("running".equals(e.getStatus())) {
                e.setStatus("error");
                e.setResult("执行中断");
                eventMapper.updateById(e);
            }
        }

        try {
            long dur = System.currentTimeMillis() - t0;
            AgentRun run = runMapper.selectById(runId);
            if (run != null) {
                run.setStatus(fin[0]);
                run.setInputTokens(usage[0]);
                run.setOutputTokens(usage[1]);
                run.setCacheRead(usage[2]);
                run.setCacheWrite(usage[3]);
                run.setTurns(counters[0]);
                run.setToolCalls(counters[1]);
                run.setDurationMs(dur);
                run.setError(fin[2]);
                run.setFinishedAt(LocalDateTime.now());
                ArrayNode su = Json.arr();
                skillsUsed.forEach(su::add);
                run.setSkillsUsed(su);
                try {
                    run.setCost(modelService.cost(modelService.resolve(spaceId, run.getModelRef()), usage[0], usage[1], usage[2]));
                } catch (Exception ignored) {
                    run.setCost(null);
                }
                runMapper.updateById(run);
            }
            // 只累加本次实际挂载的 Skill（按 id），避免同名 Skill 跨空间误计
            Map<String, String> skillIds = new HashMap<>();
            for (JsonNode sk : payload.path("skills")) skillIds.put(sk.path("name").asText(), sk.path("id").asText());
            for (String name : skillsUsed) {
                String sid = skillIds.get(name);
                if (sid == null || sid.isBlank()) continue;
                skillMapper.update(null, new LambdaUpdateWrapper<Skill>()
                        .eq(Skill::getId, sid).setSql("call_count = call_count + 1"));
            }

            String status = switch (fin[0]) {
                case "success" -> "completed";
                case "stopped" -> "stopped";
                default -> "failed";
            };
            // 只更新执行相关字段，避免覆盖执行期间用户对标题等字段的修改
            LambdaUpdateWrapper<ChatSession> uw = new LambdaUpdateWrapper<ChatSession>()
                    .eq(ChatSession::getId, sessionId)
                    .set(ChatSession::getStatus, status)
                    .set(ChatSession::getLastEventAt, LocalDateTime.now())
                    .setSql("input_tokens = IFNULL(input_tokens,0) + " + usage[0])
                    .setSql("output_tokens = IFNULL(output_tokens,0) + " + usage[1])
                    .setSql("cache_read = IFNULL(cache_read,0) + " + usage[2])
                    .setSql("cache_write = IFNULL(cache_write,0) + " + usage[3]);
            if (usage[4] > 0) uw.set(ChatSession::getContextTokens, usage[4]);
            sessionMapper.update(null, uw);
        } catch (Exception e) {
            log.error("finalize run {} failed", runId, e);
        } finally {
            running.remove(sessionId);
            ChatSession s = sessionMapper.selectById(sessionId);
            if (s != null) hub.publishSession(sessionId, s);
        }
        return new RunResult(fin[0], fin[1], fin[2], sessionId, runId);
    }

    // ------------------------------------------------------------------ 辅助

    private ArrayNode buildHistory(String sessionId) {
        List<SessionEvent> evs = eventMapper.selectList(new LambdaQueryWrapper<SessionEvent>()
                .eq(SessionEvent::getSessionId, sessionId)
                .in(SessionEvent::getType, "input", "agent")
                .orderByAsc(SessionEvent::getSeq));
        ArrayNode h = Json.arr();
        int from = Math.max(0, evs.size() - 40);
        for (SessionEvent e : evs.subList(from, evs.size())) {
            h.add(Json.obj().put("role", "input".equals(e.getType()) ? "user" : "assistant")
                    .put("content", truncate(e.getContent(), 20_000)));
        }
        return h;
    }

    private void saveArtifact(String spaceId, String sessionId, JsonNode ev) {
        try {
            Path src = Path.of(ev.path("path").asText());
            if (!Files.isRegularFile(src)) return;
            String name = ev.path("name").asText(src.getFileName().toString());
            long prev = fileMapper.selectCount(new LambdaQueryWrapper<FileInfo>()
                    .eq(FileInfo::getSessionId, sessionId).eq(FileInfo::getCategory, "artifact").eq(FileInfo::getFileName, name));
            Path dir = storage.filesDir(spaceId, "artifact").resolve(sessionId);
            Files.createDirectories(dir);
            Path dst = dir.resolve("v" + (prev + 1) + "_" + Storage.safe(name));
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            FileInfo f = new FileInfo();
            f.setId(Json.uuid());
            f.setSpaceId(spaceId);
            f.setName(name);
            f.setFileName(name);
            f.setCategory("artifact");
            f.setSource("agent");
            f.setSize(Files.size(dst));
            f.setMimeType(ev.hasNonNull("mime") ? ev.get("mime").asText() : Storage.guessMime(name));
            f.setDirPath(storage.rel(dir));
            f.setStoragePath(storage.rel(dst));
            f.setSessionId(sessionId);
            f.setVersion((int) prev + 1);
            fileMapper.insert(f);
            SessionEvent e = newEvent(sessionId, null, "status", "artifact");
            e.setContent(Json.write(Json.obj().put("fileId", f.getId()).put("name", name).put("version", f.getVersion())));
            save(e);
        } catch (Exception e) {
            log.warn("save artifact failed", e);
        }
    }

    private void touchSandbox(ChatSession s, Agent agent, Long userId) {
        Sandbox sb = sandboxMapper.selectOne(new LambdaQueryWrapper<Sandbox>()
                .eq(Sandbox::getWorkDir, s.getWorkspaceDir()).ne(Sandbox::getStatus, "destroyed").last("limit 1"));
        LocalDateTime now = LocalDateTime.now();
        if (sb == null) {
            sb = new Sandbox();
            sb.setId(Json.uuid());
            sb.setSpaceId(s.getSpaceId());
            String shortId = sb.getId().substring(0, 8);
            sb.setName("agent-" + agent.getIdentifier() + "-" + shortId);
            sb.setEnvName(s.getRuntimeEnv());
            sb.setPodName("agent-sandbox--" + agent.getIdentifier() + "-" + shortId);
            sb.setStatus("running");
            sb.setResourceGroup("local");
            User u = userId == null ? null : userMapper.selectById(userId);
            sb.setOwner(u == null ? s.getSource() : u.getUsername());
            sb.setSessionId(s.getId());
            sb.setWorkDir(s.getWorkspaceDir());
            sb.setTimeoutSeconds(8 * 3600);
            sb.setCreatedAt(now);
            sb.setDestroyAt(now.plusSeconds(sb.getTimeoutSeconds()));
            sandboxMapper.insert(sb);
        } else {
            sb.setStatus("running");
            sb.setDestroyAt(now.plusSeconds(sb.getTimeoutSeconds()));
            sandboxMapper.updateById(sb);
        }
    }

    private SessionEvent newEvent(String sessionId, Long runId, String type, String name) {
        SessionEvent e = new SessionEvent();
        e.setSessionId(sessionId);
        e.setRunId(runId);
        e.setSeq(nextSeq(sessionId));
        e.setType(type);
        e.setName(name);
        e.setStatus("ok");
        e.setDurationMs(0L);
        e.setInputTokens(0);
        e.setOutputTokens(0);
        e.setCreatedAt(LocalDateTime.now());
        return e;
    }

    private void save(SessionEvent e) {
        eventMapper.insert(e);
        hub.publishEvent(e.getSessionId(), e);
    }

    private int nextSeq(String sessionId) {
        return seqs.computeIfAbsent(sessionId, k -> {
            SessionEvent last = eventMapper.selectOne(new LambdaQueryWrapper<SessionEvent>()
                    .eq(SessionEvent::getSessionId, k).orderByDesc(SessionEvent::getSeq).last("limit 1"));
            return new AtomicInteger(last == null ? 0 : last.getSeq());
        }).incrementAndGet();
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }

    public static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
