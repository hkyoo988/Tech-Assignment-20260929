package com.example.chat.realtime;

import static java.lang.Long.parseLong;

import com.example.chat.common.ConflictException;
import com.example.chat.common.NotFoundException;
import com.example.chat.event.EventService;
import com.example.chat.event.EventType;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.dto.AppendResult;
import com.example.chat.event.dto.EventResponse;
import com.example.chat.realtime.ServerMessage.AckData;
import com.example.chat.realtime.ServerMessage.ErrorData;
import com.example.chat.realtime.ServerMessage.ResumeData;
import com.example.chat.session.SessionService;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
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

/** WebSocket 입구. 연결 시 RESUME을 보내고, 메시지는 EventService.append()로 넘겨 REST와 같은 규칙을 적용한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {
    private static final int RESUME_LIMIT = 500;

    private static final Set<EventType> CLIENT_TYPES =
            EnumSet.of(EventType.MESSAGE_SENT, EventType.JOINED, EventType.LEFT);
    private final WebSocketSessionRegistry registry;
    private final EventService eventService;
    private final JsonMapper jsonMapper;   // tools.jackson.databind.json.JsonMapper (Spring이 만들어 둔 빈)
    private final SessionService sessionService;

    @Override
    public void afterConnectionEstablished(WebSocketSession ws) throws Exception {
        var params = UriComponentsBuilder.fromUri(ws.getUri()).build().getQueryParams();
        String sessionId = params.getFirst("sessionId");
        String userId = params.getFirst("userId");
        long lastSeq = parseLong(params.getFirst("lastSeq"));

        // ① 참여자 확인
        if (sessionId == null || userId == null || !sessionService.isParticipant(sessionId, userId)) {
            ws.close(CloseStatus.POLICY_VIOLATION.withReason("세션 참여자가 아닙니다"));
            return;
        }
        ws.getAttributes().put("sessionId", sessionId);
        ws.getAttributes().put("userId", userId);

        // ② 등록 먼저: 조회보다 먼저 등록해야 그사이 생긴 이벤트를 놓치지 않는다 (중복은 생겨도 누락은 없음)
        registry.register(sessionId, userId, ws);
        log.info("[연결] sessionId={}, userId={}, lastSeq={}", sessionId, userId, lastSeq);

        // ③ 끊겼다 돌아온 참여자면 RECONNECTED
        eventService.recordPresence(sessionId, userId, true);

        // ④ 놓친 이벤트: lastSeq 이후를 RESUME으로 보낸다 (500건을 채우면 hasMore=true)
        List<EventResponse> missed = eventService.getEvents(sessionId, lastSeq, RESUME_LIMIT);
        registry.send(ws, ServerMessage.resume(new ResumeData(missed, missed.size() == RESUME_LIMIT)));
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
        String sessionId = (String) ws.getAttributes().get("sessionId");
        String userId = (String) ws.getAttributes().get("userId");
        if (sessionId == null || userId == null) return;

        // 다른 기기로 교체되며 닫힌 옛 연결이면 false → 끊김으로 기록하지 않는다
        boolean wasCurrent = registry.unregister(sessionId, userId, ws);
        log.info("[종료] sessionId={}, userId={}, status={}, current={}", sessionId, userId, status, wasCurrent);
        if (wasCurrent) {
            eventService.recordPresence(sessionId, userId, false);
        }
    }

    private void send(WebSocketSession ws, ServerMessage message) throws IOException {
        ws.sendMessage(new TextMessage(jsonMapper.writeValueAsString(message)));
    }

    private long parseLong(String v) {
        try { return v == null ? 0 : Long.parseLong(v); } catch (NumberFormatException e) { return 0; }
    }
}
