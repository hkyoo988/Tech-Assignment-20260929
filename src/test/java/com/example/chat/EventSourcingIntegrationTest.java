package com.example.chat;

import static com.example.chat.event.EventType.*;
import static org.assertj.core.api.Assertions.*;

import com.example.chat.common.ConflictException;
import com.example.chat.event.EventService;
import com.example.chat.event.EventType;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.dto.AppendResult;
import com.example.chat.session.SessionParticipant;
import com.example.chat.session.SessionParticipantRepository;
import com.example.chat.session.SessionService;
import com.example.chat.session.dto.CreateSessionRequest;
import com.example.chat.timeline.TimelineResponse;
import com.example.chat.timeline.TimelineService;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfig.class)
class EventSourcingIntegrationTest {

	@Autowired SessionService sessionService;
	@Autowired EventService eventService;
	@Autowired TimelineService timelineService;
	@Autowired SessionParticipantRepository participantRepository;

	// ===== 헬퍼 =====
	private String newSession() {
		return sessionService.create(new CreateSessionRequest("alice", "bob")).sessionId();
	}

	private AppendResult send(String sid, String clientEventId, EventType type, String userId, Map<String, Object> payload) {
		return eventService.append(sid, new AppendEventRequest(clientEventId, type, userId, payload, null));
	}

	private AppendResult message(String sid, String clientEventId, String userId, String text) {
		return send(sid, clientEventId, MESSAGE_SENT, userId, Map.of("text", text));
	}

	// ===== ① ~ ③ 멱등성 =====
	@Test
	@DisplayName("같은 clientEventId 재전송은 최초 결과(같은 seq)를 반환하고 이벤트는 1건만 저장된다")
	void retryReturnsOriginalResult() {
		String sid = newSession();                                   // seq 1
		send(sid, "j-1", JOINED, "alice", null);                     // seq 2

		AppendResult first = message(sid, "m-1", "alice", "hi");     // seq 3
		AppendResult retry = message(sid, "m-1", "alice", "hi");

		assertThat(first.duplicate()).isFalse();
		assertThat(retry.duplicate()).isTrue();
		assertThat(retry.event().seq()).isEqualTo(first.event().seq());
		assertThat(eventService.getEvents(sid, 0, 500)).hasSize(3);
	}

	@Test
	@DisplayName("같은 clientEventId에 내용이 다르면 거부한다")
	void sameKeyDifferentContentIsRejected() {
		String sid = newSession();
		send(sid, "j-1", JOINED, "alice", null);
		message(sid, "m-1", "alice", "hi");

		assertThatThrownBy(() -> message(sid, "m-1", "alice", "다른 내용"))
				.isInstanceOf(ConflictException.class);
	}

	@Test
	@DisplayName("세션 종료 후에도 이전 이벤트의 재전송은 최초 결과를 받는다 (중복 확인이 검증보다 먼저)")
	void retryAfterEndStillIdempotent() {
		String sid = newSession();
		send(sid, "j-1", JOINED, "alice", null);
		AppendResult first = message(sid, "m-1", "alice", "hi");
		send(sid, "end-1", SESSION_ENDED, "alice", null);

		AppendResult retry = message(sid, "m-1", "alice", "hi");
		assertThat(retry.duplicate()).isTrue();
		assertThat(retry.event().seq()).isEqualTo(first.event().seq());

		assertThatThrownBy(() -> message(sid, "m-2", "alice", "새 메시지"))
				.isInstanceOf(ConflictException.class);
	}

	// ===== ④ ~ ⑤ 동시성 =====
	@Test
	@DisplayName("서로 다른 이벤트 20개를 동시에 보내도 seq는 빈틈·중복 없이 연속된다")
	void concurrentAppendsGetGaplessSeq() throws Exception {
		String sid = newSession();
		send(sid, "j-1", JOINED, "alice", null);                     // 여기까지 seq 2
		int n = 20;

		List<AppendResult> results = runConcurrently(n, i -> message(sid, "c-" + i, "alice", "msg " + i));

		List<Long> seqs = results.stream().map(r -> r.event().seq()).toList();
		assertThat(seqs).containsExactlyInAnyOrderElementsOf(
				LongStream.rangeClosed(3, 2 + n).boxed().toList());
		assertThat(sessionService.get(sid).lastSeq()).isEqualTo(2 + n);
	}

	@Test
	@DisplayName("같은 이벤트를 20번 동시에 재전송해도 1건만 저장되고 모두 같은 seq를 받는다")
	void concurrentDuplicatesStoredOnce() throws Exception {
		String sid = newSession();
		send(sid, "j-1", JOINED, "alice", null);

		List<AppendResult> results = runConcurrently(20, i -> message(sid, "dup-1", "alice", "hi"));

		assertThat(results).filteredOn(r -> !r.duplicate()).hasSize(1);
		assertThat(results).extracting(r -> r.event().seq()).containsOnly(3L);
		assertThat(eventService.getEvents(sid, 0, 500)).hasSize(3);
	}

	/** n개 스레드를 준비시킨 뒤 동시에 출발시킨다. */
	private <T> List<T> runConcurrently(int n, java.util.function.IntFunction<T> task) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(n);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<T>> futures = IntStream.range(0, n)
					.mapToObj(i -> pool.submit(() -> {
						start.await();
						return task.apply(i);
					}))
					.toList();
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> f : futures) {
				results.add(f.get(30, TimeUnit.SECONDS));
			}
			return results;
		} finally {
			pool.shutdownNow();
		}
	}

	// ===== ⑥ ~ ⑦ 복원 =====
	/** alice 입장 → 메시지 → bob 입장 → bob 메시지 → alice 퇴장 (seq 1~6) */
	private String scenario() {
		String sid = newSession();
		send(sid, "a-join", JOINED, "alice", null);
		message(sid, "a-m1", "alice", "안녕");
		send(sid, "b-join", JOINED, "bob", null);
		message(sid, "b-m1", "bob", "반가워");
		send(sid, "a-leave", LEFT, "alice", null);
		return sid;
	}

	@Test
	@DisplayName("모든 시점에서 같은 seq로 두 번 복원하면 결과가 완전히 같다 (결정성)")
	void restoreIsDeterministic() {
		String sid = scenario();

		for (long seq = 0; seq <= 6; seq++) {
			TimelineResponse a = timelineService.restore(sid, seq, null);
			TimelineResponse b = timelineService.restore(sid, seq, null);
			assertThat(a).as("atSeq=%d", seq).isEqualTo(b);
			assertThat(a.restoredAtSeq()).isEqualTo(seq);
		}

		TimelineResponse atSeq3 = timelineService.restore(sid, 3L, null);
		assertThat(atSeq3.messages()).hasSize(1);
		assertThat(atSeq3.participants())
				.anySatisfy(p -> {
					assertThat(p.userId()).isEqualTo("alice");
					assertThat(p.state().name()).isEqualTo("JOINED");
				});
	}

	@Test
	@DisplayName("참여자 테이블(프로젝션)은 이벤트 리플레이 결과와 일치한다")
	void projectionMatchesReplay() {
		String sid = scenario();

		TimelineResponse replayed = timelineService.restore(sid, null, null);

		assertThat(replayed.participants()).hasSize(2);
		replayed.participants().forEach(view -> {
			SessionParticipant stored = participantRepository
					.findBySessionIdAndUserId(sid, view.userId()).orElseThrow();
			assertThat(stored.getState()).as(view.userId() + " state").isEqualTo(view.state());
			assertThat(stored.getPresence()).as(view.userId() + " presence").isEqualTo(view.presence());
		});
	}
}
