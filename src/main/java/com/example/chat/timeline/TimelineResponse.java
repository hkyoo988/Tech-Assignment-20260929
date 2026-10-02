package com.example.chat.timeline;

import com.example.chat.session.SessionState;
import com.example.chat.session.SessionState.MessageView;
import com.example.chat.session.SessionState.ParticipantView;
import com.example.chat.session.SessionStatus;

import java.time.LocalDateTime;
import java.util.List;

public record TimelineResponse(
        String sessionId,
        long restoredAtSeq,          // 실제로 어디까지 적용됐는지
        LocalDateTime lastEventAt,   // 그 마지막 이벤트의 서버 시각
        SessionStatus status,        // 세션 시작 전 시점이면 null
        List<ParticipantView> participants,
        List<MessageView> messages
) {
    public static TimelineResponse from(SessionState state) {
        return new TimelineResponse(
                state.getSessionId(),
                state.getLastSeq(),
                state.getLastEventAt(),
                state.getStatus(),
                List.copyOf(state.getParticipants().values()),
                List.copyOf(state.getMessages().values())
        );
    }
}
