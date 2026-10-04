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

/** 스냅샷 자동 생성. 커밋 후 별도 스레드(@Async)에서 실행되어 메시지 응답과 락에 영향을 주지 않는다. */
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
		boolean ended = e.type() == EventType.SESSION_ENDED;   // 종료 시점의 최종 상태 보존 (아카이빙 기준점)
		if (!periodic && !ended) {
			return;
		}
		try {
			// 이전 스냅샷 + 그 이후 이벤트로 계산 (증분 생성)
			SessionState state = timelineService.loadState(e.sessionId(), e.seq());
			snapshotRepository.insertIgnore(e.sessionId(), e.seq(),
					jsonMapper.writeValueAsString(state.toSnapshot()), LocalDateTime.now(clock));
			log.info("[스냅샷] sessionId={}, seq={}", e.sessionId(), e.seq());
		} catch (Exception ex) {
			// 실패해도 재시도하지 않는다. 다음 주기에 다시 만들고, 그사이 복원은 리플레이로 처리된다
			log.warn("[스냅샷 실패] sessionId={}, seq={} — 복원은 리플레이로 대체됨", e.sessionId(), e.seq(), ex);
		}
	}
}
