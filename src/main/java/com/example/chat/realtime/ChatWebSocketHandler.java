package com.example.chat.realtime;

import com.example.chat.common.ConflictException;
import com.example.chat.common.NotFoundException;
import com.example.chat.event.EventService;
import com.example.chat.event.EventType;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.dto.AppendResult;
import com.example.chat.event.dto.EventResponse;
import com.example.chat.realtime.ServerMessage.AckData;
import com.example.chat.realtime.ServerMessage.ErrorData;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Set<EventType> CLIENT_TYPES =
            EnumSet.of(EventType.MESSAGE_SENT, EventType.JOINED, EventType.LEFT);
    private final WebSocketSessionRegistry registry;
    private final EventService eventService;
    private final JsonMapper jsonMapper;   // tools.jackson.databind.json.JsonMapper (Spring이 만들어 둔 빈)

    @Override
    public void afterConnectionEstablished(WebSocketSession ws) throws Exception {
        // URL의 ?sessionId=...&userId=... 를 꺼내서 연결에 저장
        var params = UriComponentsBuilder.fromUri(ws.getUri()).build().getQueryParams();
        String sessionId = params.getFirst("sessionId");
        String userId = params.getFirst("userId");
        if (sessionId == null || userId == null) {
            ws.close(CloseStatus.POLICY_VIOLATION.withReason("sessionId, userId가 필요합니다"));
            return;
        }
        ws.getAttributes().put("sessionId", sessionId);
        ws.getAttributes().put("userId", userId);
        registry.register(sessionId, userId, ws);
        log.info("[연결] sessionId={}, userId={}, wsId={}", sessionId, userId, ws.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) throws Exception {
        String sessionId = (String) ws.getAttributes().get("sessionId");
        String userId = (String) ws.getAttributes().get("userId");

        ClientMessage msg;
        try {
            msg = jsonMapper.readValue(message.getPayload(), ClientMessage.class);
        } catch (Exception e) {
            send(ws, ServerMessage.error(new ErrorData(null, "MALFORMED", "JSON 형식이 아닙니다")));
            return;
        }

        if (msg.clientEventId() == null || msg.type() == null || !CLIENT_TYPES.contains(msg.type())) {
            send(ws, ServerMessage.error(new ErrorData(msg.clientEventId(), "BAD_REQUEST",
                    "clientEventId, type(MESSAGE_SENT/JOINED/LEFT)이 필요합니다")));
            return;
        }

        try {
            var req = new AppendEventRequest(msg.clientEventId(), msg.type(), userId, msg.payload(), msg.clientTs());
            AppendResult result = eventService.append(sessionId, req);   // REST와 같은 경로
            EventResponse e = result.event();
            log.info("③ ACK 전송");
            send(ws, ServerMessage.ack(new AckData(e.clientEventId(), e.seq(), e.serverTs(), result.duplicate())));
        } catch (NotFoundException ex) {
            send(ws, ServerMessage.error(new ErrorData(msg.clientEventId(), "NOT_FOUND", ex.getMessage())));
        } catch (ConflictException ex) {
            send(ws, ServerMessage.error(new ErrorData(msg.clientEventId(), "CONFLICT", ex.getMessage())));
        } catch (IllegalArgumentException ex) {
            send(ws, ServerMessage.error(new ErrorData(msg.clientEventId(), "BAD_REQUEST", ex.getMessage())));
        } catch (ConcurrencyFailureException ex) {
            send(ws, ServerMessage.error(new ErrorData(msg.clientEventId(), "TRY_AGAIN", "같은 clientEventId로 재시도하세요")));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        log.info("[종료] sessionId={}, userId={}, status={}",
                ws.getAttributes().get("sessionId"), ws.getAttributes().get("userId"), status);
        String sessionId = (String) ws.getAttributes().get("sessionId");
        String userId = (String) ws.getAttributes().get("userId");
        if (sessionId != null && userId != null) {
            registry.unregister(sessionId, userId, ws);
        }
    }

    private void send(WebSocketSession ws, ServerMessage message) throws IOException {
        ws.sendMessage(new TextMessage(jsonMapper.writeValueAsString(message)));
    }
}
