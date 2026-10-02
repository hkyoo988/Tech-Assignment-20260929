package com.example.chat.session;

import com.example.chat.event.EventService;
import com.example.chat.event.EventType;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.dto.AppendResult;
import com.example.chat.event.dto.EventResponse;
import com.example.chat.session.dto.CreateSessionRequest;
import com.example.chat.session.dto.ParticipantSessionRequest;
import com.example.chat.session.dto.SessionResponse;
import com.example.chat.timeline.TimelineResponse;
import com.example.chat.timeline.TimelineService;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
public class SessionController {

	private final SessionService sessionService;
	private final EventService eventService;
	private final TimelineService timelineService;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public SessionResponse create(@Valid @RequestBody CreateSessionRequest req) {
		return sessionService.create(req);
	}

	@GetMapping("/{sessionId}")
	public SessionResponse get(@PathVariable String sessionId) {
		return sessionService.get(sessionId);
	}

	@PostMapping("/{sessionId}/join")
	public ResponseEntity<EventResponse> join(@PathVariable String sessionId,
											  @Valid @RequestBody ParticipantSessionRequest req) {
		return appendAs(sessionId, req, EventType.JOINED);
	}

	@PostMapping("/{sessionId}/leave")
	public ResponseEntity<EventResponse> leave(@PathVariable String sessionId,
											   @Valid @RequestBody ParticipantSessionRequest req) {
		return appendAs(sessionId, req, EventType.LEFT);
	}

	@PostMapping("/{sessionId}/end")
	public ResponseEntity<EventResponse> end(@PathVariable String sessionId,
											 @Valid @RequestBody ParticipantSessionRequest req) {
		return appendAs(sessionId, req, EventType.SESSION_ENDED);
	}

	private ResponseEntity<EventResponse> appendAs(String sessionId, ParticipantSessionRequest req, EventType type) {
		AppendEventRequest eventReq = new AppendEventRequest(req.clientEventId(), type, req.userId(), null, null);
		return eventService.append(sessionId, eventReq).toResponse();
	}

	@GetMapping("/{sessionId}/timeline")
	public TimelineResponse timeline(
			@PathVariable String sessionId,
			@RequestParam(required = false) Long atSeq,
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime at) {
		return timelineService.restore(sessionId, atSeq, at);
	}
}
