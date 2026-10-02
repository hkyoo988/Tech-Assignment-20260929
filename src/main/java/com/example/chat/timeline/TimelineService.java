package com.example.chat.timeline;

import com.example.chat.common.NotFoundException;
import com.example.chat.event.SessionEvent;
import com.example.chat.event.SessionEventRepository;
import com.example.chat.session.ChatSessionRepository;
import com.example.chat.session.SessionState;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TimelineService {

    private final ChatSessionRepository sessionRepository;
    private final SessionEventRepository eventRepository;

    /**
     * targetSeq 시점의 세션 상태를 이벤트 리플레이로 계산한다.
     * 같은 targetSeq면 언제 호출해도 같은 결과 (결정성).
     */
    @Transactional(readOnly = true)
    public SessionState loadState(String sessionId, long targetSeq) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new NotFoundException("세션이 없습니다: " + sessionId);
        }

        // ① seq ≤ targetSeq 이벤트를 seq 순서로
        List<SessionEvent> events =
                eventRepository.findBySessionIdAndSeqLessThanEqualOrderBySeqAsc(sessionId, targetSeq);

        // ② 빈 상태에서 시작해 ③ 하나씩 적용
        SessionState state = SessionState.empty(sessionId);
        for (SessionEvent e : events) {
            state.apply(e);
        }
        return state;
    }

    @Transactional(readOnly = true)
    public TimelineResponse restore(String sessionId, Long atSeq, OffsetDateTime at) {
        if (atSeq != null && at != null) {
            throw new IllegalArgumentException("atSeq와 at은 동시에 지정할 수 없습니다");
        }
        if (atSeq != null && atSeq < 0) {
            throw new IllegalArgumentException("atSeq는 0 이상이어야 합니다");
        }

        long targetSeq = resolveTargetSeq(sessionId, atSeq, at);
        SessionState state = loadState(sessionId, targetSeq);
        return TimelineResponse.from(state);
    }

    private long resolveTargetSeq(String sessionId, Long atSeq, OffsetDateTime at) {
        if (atSeq != null) {
            return atSeq;
        }
        if (at != null) {
            LocalDateTime utc = at.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            return eventRepository
                    .findTopBySessionIdAndServerTsLessThanEqualOrderBySeqDesc(sessionId, utc)
                    .map(SessionEvent::getSeq)
                    .orElse(0L);           // 세션 시작 전 시각 → 빈 상태
        }
        return Long.MAX_VALUE;             // 파라미터 없음 → 전부 적용 = 현재 상태
    }
}
