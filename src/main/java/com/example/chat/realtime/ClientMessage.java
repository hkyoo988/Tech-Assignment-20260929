package com.example.chat.realtime;

import com.example.chat.event.EventType;
import java.time.OffsetDateTime;
import java.util.Map;

public record ClientMessage(
	String clientEventId,
	EventType type,
	Map<String, Object> payload,
	OffsetDateTime clientTs) {

}
