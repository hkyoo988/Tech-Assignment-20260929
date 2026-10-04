package com.example.chat.event;

import com.example.chat.common.ConflictException;
import com.example.chat.common.NotFoundException;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.dto.AppendResult;
import com.example.chat.event.dto.EventResponse;
import com.example.chat.session.ChatSession;
import com.example.chat.session.ChatSessionRepository;
import com.example.chat.session.ParticipantPresence;
import com.example.chat.session.SessionParticipant;
import com.example.chat.session.SessionParticipantRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** 모든 이벤트 저장의 단일 경로. REST, WebSocket, 세션 생성이 모두 여기를 거쳐 같은 락, 멱등, 순서 규칙을 따른다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

	private static final int MAX_PAGE_SIZE = 500;

	private final ChatSessionRepository sessionRepository;
	private final SessionEventRepository eventRepository;
	private final SessionParticipantRepository participantRepository;
	private final ApplicationEventPublisher publisher;
	private final Clock clock;

	@Transactional
	public AppendResult append(String sessionId, AppendEventRequest req) {
		// ① 세션 행 락(FOR UPDATE). 같은 세션의 요청은 여기서 한 줄로 선다 → seq 중복, 데드락 방지
		ChatSession session = sessionRepository.findByIdForUpdate(sessionId)
			.orElseThrow(() -> new NotFoundException("세션이 없습니다: " + sessionId));

		// ② 중복 확인은 락 안에서, 검증보다 먼저.
		//    락 밖이면 동시 재전송이 모두 "없음"으로 판단하고, 검증 뒤면 종료 후 재전송이 에러를 받는다
		Optional<SessionEvent> existing = eventRepository.findBySessionIdAndClientEventId(sessionId,
			req.clientEventId());

		if(existing.isPresent()) {
			SessionEvent original = existing.get();
			if (!original.hasSameContent(req.type(), req.userId(), req.payload())) {
				throw new ConflictException("같은 clientEventId로 다른 내용의 이벤트가 이미 있습니다: " + req.clientEventId());
			}
			// 같은 요청에는 같은 응답: 최초 결과(같은 seq)를 그대로 돌려준다
			return AppendResult.duplicate(EventResponse.from(original));
		}
		// ③ 검증: 참여자인가, 서버 전용 타입인가, 종료된 세션인가, 메시지면 JOINED인가
		if (!session.isParticipant(req.userId())) {
			throw new IllegalArgumentException("세션 참여자가 아닙니다: " + req.userId());
		}
		if (req.type() == EventType.SESSION_STARTED
				|| req.type() == EventType.DISCONNECTED
				|| req.type() == EventType.RECONNECTED) {
			throw new IllegalArgumentException("서버 전용 이벤트는 클라이언트가 보낼 수 없습니다: " + req.type());
		}
		if (session.isCompleted()) {
    		throw new ConflictException("종료된 세션입니다: " + sessionId);
		}
		if (req.type() == EventType.MESSAGE_SENT) {
			boolean joined = participantRepository.findBySessionIdAndUserId(sessionId, req.userId())
				.map(SessionParticipant::isJoined)
				.orElse(false);
			if (!joined) {
				throw new ConflictException("입장(JOINED) 상태가 아니라 메시지를 보낼 수 없습니다: " + req.userId());
			}
		}

		// ④ seq 발급 + 저장
		SessionEvent saved = saveEvent(session, req.type(), req.userId(), req.payload(),
			req.clientEventId(), toUtc(req.clientTs()));
		return AppendResult.created(EventResponse.from(saved));
	}

	// 세션 생성 등 서버 내부에서도 쓰는 공통 기록 로직
	@Transactional
	public SessionEvent saveEvent(ChatSession session, EventType type, String userId,
		Map<String, Object> payload, String clientEventId, LocalDateTime clientTs) {
		long seq = session.nextSeq(); // last_seq 증가 → 커밋 시 UPDATE (변경 감지)
		LocalDateTime now = LocalDateTime.now(clock);

		SessionEvent event = SessionEvent.builder()
			.sessionId(session.getId())
			.seq(seq)
			.clientEventId(clientEventId)
			.type(type)
			.userId(userId)
			.payload(payload)
			.clientTs(clientTs)
			.serverTs(now)
			.build();

		SessionEvent saved = eventRepository.save(event);   // ① 진실의 원천 먼저

		// 참여자 상태 갱신 (이벤트를 보낸 사람의 행)
		if (type.affectsParticipant()) {
			participantRepository.findBySessionIdAndUserId(session.getId(), userId)
				.ifPresent(p -> p.apply(type, seq, now));
		}
		if (type == EventType.SESSION_ENDED) {
			session.end(now);
		}
		log.info("① publishEvent 호출");
		// 커밋 후 작업(실시간 전송, 스냅샷)에 알림. 리스너가 AFTER_COMMIT이라 롤백되면 실행되지 않는다
		publisher.publishEvent(new EventAppended(EventResponse.from(saved)));
		return saved;
	}

	/** afterSeq 이후 이벤트를 seq 순으로 (커서 방식, 최대 500건). RESUME과 GET /events가 함께 쓴다. */
	@Transactional(readOnly = true)
	public List<EventResponse> getEvents(String sessionId, long afterSeq, int size) {
		if (!sessionRepository.existsById(sessionId)) {
			throw new NotFoundException("세션이 없습니다: " + sessionId);
		}
		int limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
		return eventRepository
				.findBySessionIdAndSeqGreaterThanOrderBySeqAsc(sessionId, afterSeq,
				PageRequest.of(0, limit))
			.stream().map(EventResponse::from).toList();
	}

	/** 연결, 끊김을 presence 이벤트로 기록. 참여 중(JOINED)이고 상태가 실제로 바뀔 때만 남긴다. */
	@Transactional
	public Optional<SessionEvent> recordPresence(String sessionId, String userId, boolean connected) {
		ChatSession session = sessionRepository.findByIdForUpdate(sessionId).orElse(null);
		if (session == null || session.isCompleted()) return Optional.empty();

		SessionParticipant p = participantRepository.findBySessionIdAndUserId(sessionId, userId).orElse(null);
		if (p == null || !p.isJoined()) return Optional.empty();       // 참여 중인 사람만

		EventType type = null;
		if (connected && p.getPresence() == ParticipantPresence.OFFLINE) type = EventType.RECONNECTED;
		if (!connected && p.getPresence() == ParticipantPresence.ONLINE) type = EventType.DISCONNECTED;
		if (type == null) return Optional.empty();                      // 상태 변화 없으면 기록 안 함

		String clientEventId = "sys-" + type.name().toLowerCase() + "-" + UUID.randomUUID();
		return Optional.of(saveEvent(session, type, userId, null, clientEventId, null));
	}

	private LocalDateTime toUtc(OffsetDateTime t) {
		return t == null ? null : t.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
	}
}
