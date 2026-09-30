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
	private final Clock clock;

	@Transactional
	public SessionResponse create(CreateSessionRequest req) {
		ChatSession session = ChatSession.start(req.participantA(), req.participantB(),
			LocalDateTime.now(clock));

		session = sessionRepository.save(session);

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
}
