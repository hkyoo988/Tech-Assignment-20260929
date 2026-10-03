package com.example.chat.realtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketSessionRegistry {

    // sessionId → (userId → 연결)
    private final Map<String, Map<String, WebSocketSession>> connections = new ConcurrentHashMap<>();
    private final JsonMapper jsonMapper;

    public void register(String sessionId, String userId, WebSocketSession ws) {
        // 여러 스레드가 동시에 같은 연결로 보내도 안전하도록 감싼다 (전송 제한 5초, 버퍼 64KB)
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(ws, 5_000, 64 * 1024);
        WebSocketSession old = connections.computeIfAbsent(sessionId,
            k -> new ConcurrentHashMap<>()).put(userId, safe);
        if (old != null) {
            try {
                old.close(CloseStatus.POLICY_VIOLATION.withReason("다른 곳에서 접속했습니다"));
            } catch (Exception ignored) {
            }
        }
    }

    public boolean unregister(String sessionId, String userId, WebSocketSession ws) {
        Map<String, WebSocketSession> users = connections.get(sessionId);
        if (users == null) return false;
        boolean[] removed = {false};
        users.computeIfPresent(userId, (k, current) -> {
            if (current.getId().equals(ws.getId())) { removed[0] = true; return null; }
            return current;
        });
        if (users.isEmpty()) connections.remove(sessionId);
        return removed[0];
    }

    public void closeAll(String sessionId, CloseStatus status) {
        connections.getOrDefault(sessionId, Map.of()).values().forEach(ws -> {
            try { ws.close(status); } catch (Exception e) { log.warn("[닫기 실패] wsId={}", ws.getId()); }
        });
    }

    // 보낸 사람을 제외한 세션의 모든 연결에 전송
    public void broadcast(String sessionId, String excludeUserId, ServerMessage message) {
        Map<String, WebSocketSession> users = connections.getOrDefault(sessionId, Map.of());
        users.forEach((userId, ws) -> {
            if (!userId.equals(excludeUserId)) send(ws, message);
        });
    }

    public void send(WebSocketSession ws, ServerMessage message) {
        try {
            log.info("[전송] kind={}, wsId={}", message.kind(), ws.getId());
            ws.sendMessage(new TextMessage(jsonMapper.writeValueAsString(message)));
        } catch (Exception e) {
            log.warn("[전송 실패] wsId={}, reason={}", ws.getId(), e.getMessage());
        }
    }
}
