package com.example.chat.timeline;

import com.example.chat.event.EventAppended;
import com.example.chat.event.EventType;
import com.example.chat.event.dto.EventResponse;
import com.example.chat.session.SessionState;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class SnapshotService {

	private final TimelineService timelineService;
	private final SnapshotRepository snapshotRepository;
	private final JsonMapper jsonMapper;
	private final Clock clock;

	@Value("${chat.snapshot.interval:100}")
	private long interval;

	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onEventAppended(EventAppended appended) {
		EventResponse e = appended.event();
		boolean periodic = e.seq() % interval == 0;
		boolean ended = e.type() == EventType.SESSION_ENDED;   // ← 1번 결정: 종료 시점도 찍을지 (원치 않으면 이 줄과 아래 조건에서 제거)
		if (!periodic && !ended) {
			return;
		}
		try {
			SessionState state = timelineService.loadState(e.sessionId(), e.seq());
			snapshotRepository.insertIgnore(e.sessionId(), e.seq(),
					jsonMapper.writeValueAsString(state.toSnapshot()), LocalDateTime.now(clock));
			log.info("[스냅샷] sessionId={}, seq={}", e.sessionId(), e.seq());
		} catch (Exception ex) {
			log.warn("[스냅샷 실패] sessionId={}, seq={} — 복원은 리플레이로 대체됨", e.sessionId(), e.seq(), ex);
		}
	}
}
