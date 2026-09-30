package com.example.chat.realtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
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
        connections.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>()).put(userId, safe);
    }

    public void unregister(String sessionId, String userId, WebSocketSession ws) {
        Map<String, WebSocketSession> users = connections.get(sessionId);
        if (users == null) return;
        // 같은 사용자가 새로 연결한 뒤에 옛 연결이 끊긴 경우, 새 연결을 지우지 않도록 id 비교
        users.computeIfPresent(userId, (k, current) -> current.getId().equals(ws.getId()) ? null : current);
        if (users.isEmpty()) connections.remove(sessionId);
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
