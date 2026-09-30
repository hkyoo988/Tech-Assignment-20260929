package com.example.chat.event;

import com.example.chat.common.ConflictException;
import com.example.chat.common.NotFoundException;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.dto.AppendResult;
import com.example.chat.event.dto.EventResponse;
import com.example.chat.session.ChatSession;
import com.example.chat.session.ChatSessionRepository;
import java.util.Objects;
import java.util.Optional;
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
	public AppendResult append(String sessionId, AppendEventRequest req) {
		ChatSession session = sessionRepository.findByIdForUpdate(sessionId)
			.orElseThrow(() -> new NotFoundException("세션이 없습니다: " + sessionId));

		Optional<SessionEvent> existing = eventRepository.findBySessionIdAndClientEventId(sessionId,
			req.clientEventId());

		if(existing.isPresent()) {
			SessionEvent original = existing.get();
			if (!original.hasSameContent(req.type(), req.userId(), req.payload())) {
				throw new ConflictException("같은 clientEventId로 다른 내용의 이벤트가 이미 있습니다: " + req.clientEventId());
			}
			return AppendResult.duplicate(EventResponse.from(original));
		}
		if (!session.isParticipant(req.userId())) {
			throw new IllegalArgumentException("세션 참여자가 아닙니다: " + req.userId());
		}
		if (req.type() == EventType.SESSION_STARTED) {
			throw new IllegalArgumentException("SESSION_STARTED는 클라이언트가 보낼 수 없습니다");
		}

		SessionEvent saved = saveEvent(session, req.type(), req.userId(), req.payload(),
			req.clientEventId(), toUtc(req.clientTs()));
		return AppendResult.created(EventResponse.from(saved));
	}

	// 세션 생성 등 서버 내부에서도 쓰는 공통 기록 로직
	@Transactional
	public SessionEvent saveEvent(ChatSession session, EventType type, String userId,
		Map<String, Object> payload, String clientEventId, LocalDateTime clientTs) {
		long seq = session.nextSeq(); // last_seq 증가 → 커밋 시 UPDATE (변경 감지)

		SessionEvent event = SessionEvent.builder()
			.sessionId(session.getId())
			.seq(seq)
			.clientEventId(clientEventId)
			.type(type)
			.userId(userId)
			.payload(payload)
			.clientTs(clientTs)
			.serverTs(LocalDateTime.now(clock))
			.build();
		return eventRepository.save(event);
	}

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

	private LocalDateTime toUtc(OffsetDateTime t) {
		return t == null ? null : t.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
	}
}
