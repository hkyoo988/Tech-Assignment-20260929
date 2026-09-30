package com.example.chat.realtime;

import com.example.chat.event.dto.EventResponse;
import java.time.LocalDateTime;
import java.util.List;

public record ServerMessage(String kind, Object data) {
	public static ServerMessage ack(AckData data)      { return new ServerMessage("ACK", data); }
    public static ServerMessage error(ErrorData data)  { return new ServerMessage("ERROR", data); }
	public static ServerMessage event(EventResponse data) { return new ServerMessage("EVENT", data); }

	public static ServerMessage resume(ResumeData data) { return new ServerMessage("RESUME", data); }
	public record ResumeData(List<EventResponse> events, boolean hasMore) {}
	public record AckData(String clientEventId, long seq, LocalDateTime serverTs, boolean duplicate) {}
	public record ErrorData(String clientEventId, String code, String message) {}
}
