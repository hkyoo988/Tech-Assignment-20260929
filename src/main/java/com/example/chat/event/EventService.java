package com.example.chat.event;

import com.example.chat.common.NotFoundException;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.dto.EventResponse;
import com.example.chat.session.ChatSession;
import com.example.chat.session.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EventService {

    private static final int MAX_PAGE_SIZE = 500;

    private final ChatSessionRepository sessionRepository;
    private final SessionEventRepository eventRepository;
    private final Clock clock;

    @Transactional
    public EventResponse append(String sessionId, AppendEventRequest req) {
        // ⚠️ 2일차 버전: 락 없이 조회 → 동시 요청 시 같은 seq를 받을 수 있음 (3일차에 수정)
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("세션이 없습니다: " + sessionId));

        if (!session.isParticipant(req.userId())) {
            throw new IllegalArgumentException("세션 참여자가 아닙니다: " + req.userId());
        }
        if (req.type() == EventType.SESSION_STARTED) {
            throw new IllegalArgumentException("SESSION_STARTED는 클라이언트가 보낼 수 없습니다");
        }

        SessionEvent saved = record(session, req.type(), req.userId(), req.payload(),
                req.clientEventId(), toUtc(req.clientTs()));
        return EventResponse.from(saved);
    }

    // 세션 생성 등 서버 내부에서도 쓰는 공통 기록 로직
    @Transactional
    public SessionEvent record(ChatSession session, EventType type, String userId,
                               Map<String, Object> payload, String clientEventId, LocalDateTime clientTs) {
        long seq = session.nextSeq(); // last_seq 증가 → 커밋 시 UPDATE (변경 감지)
        SessionEvent event = SessionEvent.of(session.getId(), seq, clientEventId, type, userId,
                payload, clientTs, LocalDateTime.now(clock));
        return eventRepository.save(event);
    }

    @Transactional(readOnly = true)
    public List<EventResponse> list(String sessionId, long afterSeq, int size) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new NotFoundException("세션이 없습니다: " + sessionId);
        }
        int limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return eventRepository
                .findBySessionIdAndSeqGreaterThanOrderBySeqAsc(sessionId, afterSeq, PageRequest.of(0, limit))
                .stream().map(EventResponse::from).toList();
    }

    private LocalDateTime toUtc(OffsetDateTime t) {
        return t == null ? null : t.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
