package com.example.chat.session.dto;

import com.example.chat.session.ChatSession;
import com.example.chat.session.SessionStatus;

import java.time.LocalDateTime;

public record SessionResponse(
	String sessionId, String participantA, String participantB,
	SessionStatus status, long lastSeq, LocalDateTime startedAt, LocalDateTime endedAt
) {

	public static SessionResponse from(ChatSession s) {
		return new SessionResponse(s.getId(), s.getParticipantA(), s.getParticipantB(),
			s.getStatus(), s.getLastSeq(), s.getStartedAt(), s.getEndedAt());
	}
}
