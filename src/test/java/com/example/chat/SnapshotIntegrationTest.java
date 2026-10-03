package com.example.chat;

import static com.example.chat.event.EventType.*;
import static org.assertj.core.api.Assertions.*;

import com.example.chat.event.EventService;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.session.SessionService;
import com.example.chat.session.dto.CreateSessionRequest;
import com.example.chat.timeline.SnapshotRepository;
import com.example.chat.timeline.TimelineService;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = "chat.snapshot.interval=3")
@Import(TestcontainersConfig.class)
class SnapshotIntegrationTest {

	@Autowired SessionService sessionService;
	@Autowired EventService eventService;
	@Autowired TimelineService timelineService;
	@Autowired SnapshotRepository snapshotRepository;

	@Test
	@DisplayName("스냅샷이 주기마다 비동기로 생성되고, 스냅샷 기반 복원은 전체 리플레이와 모든 시점에서 같다")
	void snapshotRestoreEqualsFullReplay() throws Exception {
		String sid = sessionService.create(new CreateSessionRequest("alice", "bob")).sessionId();  // seq 1
		append(sid, "a-join", JOINED, "alice", null);                                               // seq 2
		append(sid, "b-join", JOINED, "bob", null);                                                 // seq 3 → 스냅샷
		for (int i = 1; i <= 10; i++) {                                                             // seq 4 ~ 13
			String who = i % 2 == 0 ? "alice" : "bob";
			append(sid, "m-" + i, MESSAGE_SENT, who, Map.of("text", "msg " + i));
		}

		// 비동기라 생성될 때까지 기다린다 (최대 5초)
		waitUntil(() -> snapshotRepository.findLatest(sid, Long.MAX_VALUE)
				.map(r -> r.seq() == 12).orElse(false));

		assertThat(snapshotRepository.findLatest(sid, 5)).get().extracting(r -> r.seq()).isEqualTo(3L);
		assertThat(snapshotRepository.findLatest(sid, 11)).get().extracting(r -> r.seq()).isEqualTo(9L);

		for (long seq = 0; seq <= 13; seq++) {
			var withSnapshot = timelineService.loadState(sid, seq, true).toSnapshot();
			var fullReplay = timelineService.loadState(sid, seq, false).toSnapshot();
			assertThat(withSnapshot).as("atSeq=%d", seq).isEqualTo(fullReplay);
		}
	}

	private void append(String sid, String clientEventId, com.example.chat.event.EventType type,
						String userId, Map<String, Object> payload) {
		eventService.append(sid, new AppendEventRequest(clientEventId, type, userId, payload, null));
	}

	private void waitUntil(BooleanSupplier condition) throws InterruptedException {
		for (int i = 0; i < 50; i++) {
			if (condition.getAsBoolean()) return;
			Thread.sleep(100);
		}
		fail("5초 안에 조건이 충족되지 않았습니다");
	}
}
