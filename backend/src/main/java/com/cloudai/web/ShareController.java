package com.cloudai.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudai.auth.UserContext;
import com.cloudai.common.BizException;
import com.cloudai.common.R;
import com.cloudai.entity.Agent;
import com.cloudai.entity.AgentLinks.AgentGrant;
import com.cloudai.entity.ChatSession;
import com.cloudai.entity.SessionEvent;
import com.cloudai.entity.SpaceMember;
import com.cloudai.mapper.CoreMappers.SpaceMemberMapper;
import com.cloudai.mapper.Mappers.*;
import com.cloudai.service.RunService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

/**
 * 分享对话：拿到链接的用户（空间成员或被授权用户）可与 Agent 对话。
 * 不依赖 X-Space-Id，自行校验访问权限；会话 source=share，仅创建者可访问。
 */
@RestController
@RequestMapping("/api/share")
@RequiredArgsConstructor
public class ShareController {
    private final AgentMapper agentMapper;
    private final AgentGrantMapper grantMapper;
    private final SpaceMemberMapper memberMapper;
    private final ChatSessionMapper sessionMapper;
    private final SessionEventMapper eventMapper;
    private final RunService runService;
    private final SessionController sessionController;

    private Agent accessible(String agentId) {
        Agent a = agentMapper.selectById(agentId);
        if (a == null || !"available".equals(a.getStatus())) throw BizException.notFound("Agent");
        long uid = UserContext.userId();
        boolean member = memberMapper.selectCount(new LambdaQueryWrapper<SpaceMember>()
                .eq(SpaceMember::getSpaceId, a.getSpaceId()).eq(SpaceMember::getUserId, uid)) > 0;
        boolean granted = grantMapper.selectCount(new LambdaQueryWrapper<AgentGrant>()
                .eq(AgentGrant::getAgentId, agentId).eq(AgentGrant::getUserId, uid)) > 0;
        if (!member && !granted) throw new BizException(403, "你没有访问该智能体的权限，请联系负责人授权");
        return a;
    }

    private ChatSession mySession(String id) {
        ChatSession s = sessionMapper.selectById(id);
        if (s == null || !UserContext.userId().equals(s.getCreatorId())) throw BizException.notFound("会话");
        accessible(s.getAgentId());
        return s;
    }

    @GetMapping("/agents/{id}")
    public R<Map<String, Object>> agent(@PathVariable String id) {
        Agent a = accessible(id);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", a.getId());
        r.put("name", a.getName());
        r.put("identifier", a.getIdentifier());
        r.put("description", a.getDescription());
        r.put("avatar", a.getAvatar());
        r.put("version", a.getVersion());
        List<ChatSession> mine = sessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getAgentId, id).eq(ChatSession::getCreatorId, UserContext.userId())
                .eq(ChatSession::getSource, "share").orderByDesc(ChatSession::getUpdatedAt).last("limit 30"));
        r.put("sessions", mine.stream().map(sessionController::row).toList());
        return R.ok(r);
    }

    @PostMapping("/agents/{id}/sessions")
    public R<Map<String, Object>> create(@PathVariable String id) {
        Agent a = accessible(id);
        ChatSession s = runService.createSession(a, new RunService.SessionOptions(null, "share", null,
                UserContext.userId(), null, null, null));
        return R.ok(sessionController.row(s));
    }

    @PostMapping("/sessions/{id}/messages")
    public R<Void> send(@PathVariable String id, @RequestBody Map<String, Object> body) {
        ChatSession s = mySession(id);
        runService.send(s, String.valueOf(body.getOrDefault("content", "")), null, "share", UserContext.userId());
        return R.ok();
    }

    @PostMapping("/sessions/{id}/stop")
    public R<Void> stop(@PathVariable String id) {
        mySession(id);
        runService.stop(id);
        return R.ok();
    }

    @GetMapping("/sessions/{id}/events")
    public R<List<SessionEvent>> events(@PathVariable String id) {
        mySession(id);
        return R.ok(eventMapper.selectList(new LambdaQueryWrapper<SessionEvent>()
                .eq(SessionEvent::getSessionId, id).orderByAsc(SessionEvent::getSeq)));
    }

    @GetMapping(value = "/sessions/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id, @RequestParam(defaultValue = "0") int after) {
        return sessionController.openStream(mySession(id), after);
    }
}
