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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 시점 복원: targetSeq 이하 최신 스냅샷 + 그 이후 이벤트 리플레이. */
@Service
@Slf4j
@RequiredArgsConstructor
public class TimelineService {

    private final ChatSessionRepository sessionRepository;
    private final SessionEventRepository eventRepository;
    private final SnapshotRepository snapshotRepository;
    private final JsonMapper jsonMapper;

    @Transactional(readOnly = true)
    public SessionState loadState(String sessionId, long targetSeq) {
        return loadState(sessionId, targetSeq, true);
    }

    /** useSnapshot=false면 항상 처음부터 리플레이 (스냅샷 검증·비교용) */
    @Transactional(readOnly = true)
    public SessionState loadState(String sessionId, long targetSeq, boolean useSnapshot) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new NotFoundException("세션이 없습니다: " + sessionId);
        }

        SessionState state = SessionState.empty(sessionId);
        long fromSeq = 0;

        if (useSnapshot) {
            // targetSeq 이하 최신 스냅샷부터 시작 (없으면 빈 상태, seq 0)
            var snap = snapshotRepository.findLatest(sessionId, targetSeq).orElse(null);
            if (snap != null) {
                try {
                    state = SessionState.fromSnapshot(jsonMapper.readValue(snap.stateJson(), SessionState.Snapshot.class));
                    fromSeq = snap.seq();
                } catch (Exception ex) {
                    // 스냅샷은 캐시일 뿐이라, 읽기에 실패해도 처음부터 리플레이하면 같은 결과가 나온다
                    log.warn("[스냅샷 읽기 실패] sessionId={}, seq={} — 전체 리플레이로 대체", sessionId, snap.seq(), ex);
                    state = SessionState.empty(sessionId);
                    fromSeq = 0;
                }
            }
        }

        List<SessionEvent> events = eventRepository
                .findBySessionIdAndSeqGreaterThanAndSeqLessThanEqualOrderBySeqAsc(sessionId, fromSeq, targetSeq);
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

    // 시각도 seq로 바꿔서 복원한다 (순서 기준은 seq 하나)
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
