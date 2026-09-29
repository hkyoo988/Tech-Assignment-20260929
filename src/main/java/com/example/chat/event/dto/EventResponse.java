package com.example.chat.event.dto;

import com.example.chat.event.EventType;
import com.example.chat.event.SessionEvent;

import java.time.LocalDateTime;
import java.util.Map;

public record EventResponse(
        Long eventId, String sessionId, long seq, String clientEventId, EventType type,
        String userId, Map<String, Object> payload, LocalDateTime clientTs, LocalDateTime serverTs
) {
    public static EventResponse from(SessionEvent e) {
        return new EventResponse(e.getId(), e.getSessionId(), e.getSeq(), e.getClientEventId(),
                e.getType(), e.getUserId(), e.getPayload(), e.getClientTs(), e.getServerTs());
    }
}
