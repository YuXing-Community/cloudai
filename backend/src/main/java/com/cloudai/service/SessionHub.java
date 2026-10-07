package com.cloudai.service;

import com.cloudai.common.Json;
import com.cloudai.entity.SessionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 会话事件广播：执行中的事件实时推送到所有订阅该会话的浏览器 */
@Slf4j
@Component
public class SessionHub {
    private final Map<String, List<SseEmitter>> subs = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String sessionId) {
        SseEmitter e = new SseEmitter(0L);
        subs.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(e);
        Runnable rm = () -> {
            List<SseEmitter> l = subs.get(sessionId);
            if (l != null) l.remove(e);
        };
        e.onCompletion(rm);
        e.onTimeout(rm);
        e.onError(t -> rm.run());
        return e;
    }

    public void publishEvent(String sessionId, SessionEvent ev) {
        send(sessionId, "event", ev);
    }

    public void publishSession(String sessionId, Object session) {
        send(sessionId, "session", session);
    }

    private void send(String sessionId, String name, Object data) {
        List<SseEmitter> l = subs.get(sessionId);
        if (l == null || l.isEmpty()) return;
        String json = Json.write(data);
        for (SseEmitter e : l) {
            try {
                e.send(SseEmitter.event().name(name).data(json));
            } catch (Exception ex) {
                l.remove(e);
            }
        }
    }
}
