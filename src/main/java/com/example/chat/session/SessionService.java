package com.example.chat.session;

import com.example.chat.common.NotFoundException;
import com.example.chat.event.EventService;
import com.example.chat.event.EventType;
import com.example.chat.session.dto.CreateSessionRequest;
import com.example.chat.session.dto.SessionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SessionService {

	private final ChatSessionRepository sessionRepository;
	private final EventService eventService;
	private final SessionParticipantRepository participantRepository;
	private final Clock clock;

	@Transactional
	public SessionResponse create(CreateSessionRequest req) {
		LocalDateTime now = LocalDateTime.now(clock);
		ChatSession session = ChatSession.start(req.participantA(), req.participantB(),
			now);

		session = sessionRepository.save(session);

		// 허용된 참여자 2명의 상태 행을 미리 만든다 (이벤트 반영 전에 존재해야 함)
		participantRepository.save(
			SessionParticipant.create(session.getId(), req.participantA(), now));
		participantRepository.save(
			SessionParticipant.create(session.getId(), req.participantB(), now));

		// 세션 시작도 "일어난 사실"이므로 이벤트로 기록 (복원의 출발점)
		eventService.saveEvent(session, EventType.SESSION_STARTED, req.participantA(),
			Map.of("participantA", req.participantA(), "participantB", req.participantB()),
			"session-started-" + session.getId(), null);

		return SessionResponse.from(session);
	}

	@Transactional(readOnly = true)
	public SessionResponse get(String sessionId) {
		return sessionRepository.findById(sessionId)
			.map(SessionResponse::from)
			.orElseThrow(() -> new NotFoundException("세션이 없습니다: " + sessionId));
	}

	@Transactional(readOnly = true)
	public boolean isParticipant(String sessionId, String userId) {
		return sessionRepository.findById(sessionId)
			.map(s -> s.isParticipant(userId))
			.orElse(false);
	}
}
