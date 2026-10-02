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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Session", description = "세션 생성·입장·퇴장·종료, 시점 복원")
@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
public class SessionController {

	private final SessionService sessionService;
	private final EventService eventService;
	private final TimelineService timelineService;

	@Operation(summary = "세션 생성", description = "SESSION_STARTED 이벤트(seq 1)를 기록하고 참여자 2명을 LEFT/OFFLINE으로 생성")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public SessionResponse create(@Valid @RequestBody CreateSessionRequest req) {
		return sessionService.create(req);
	}

	@Operation(summary = "세션 조회")
	@GetMapping("/{sessionId}")
	public SessionResponse get(@PathVariable String sessionId) {
		return sessionService.get(sessionId);
	}

	@Operation(summary = "입장", description = "JOINED 이벤트 기록. 응답 코드는 POST /events와 동일 (멱등)")
	@PostMapping("/{sessionId}/join")
	public ResponseEntity<EventResponse> join(@PathVariable String sessionId,
											  @Valid @RequestBody ParticipantSessionRequest req) {
		return appendAs(sessionId, req, EventType.JOINED);
	}

	@Operation(summary = "퇴장", description = "LEFT 이벤트 기록. 연결은 유지됨. 응답 코드는 POST /events와 동일")
	@PostMapping("/{sessionId}/leave")
	public ResponseEntity<EventResponse> leave(@PathVariable String sessionId,
											   @Valid @RequestBody ParticipantSessionRequest req) {
		return appendAs(sessionId, req, EventType.LEFT);
	}

	@Operation(summary = "세션 종료", description = "SESSION_ENDED 기록 후 모든 WebSocket 연결 종료. 이후 이벤트는 409")
	@PostMapping("/{sessionId}/end")
	public ResponseEntity<EventResponse> end(@PathVariable String sessionId,
											 @Valid @RequestBody ParticipantSessionRequest req) {
		return appendAs(sessionId, req, EventType.SESSION_ENDED);
	}

	@Operation(summary = "특정 시점 상태 복원",
		description = "이벤트를 seq 순으로 리플레이해 상태를 계산한다. 파라미터가 없으면 현재 상태. atSeq와 at은 동시에 지정할 수 없다.")
	@GetMapping("/{sessionId}/timeline")
	public TimelineResponse timeline(
			@PathVariable String sessionId,
			@RequestParam(required = false) Long atSeq,
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime at) {
		return timelineService.restore(sessionId, atSeq, at);
	}

	@Operation(summary = "참여자의 세션 목록", description = "최신 시작순. status, 기간(from 이상 ~ to 미만, ISO-8601 오프셋 필수) 필터. size 최대 100")
	@GetMapping
	public List<SessionResponse> list(
			@RequestParam String participant,
			@RequestParam(required = false) SessionStatus status,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
			@RequestParam(defaultValue = "20") int size) {
		return sessionService.list(participant, status, from, to, size);
	}

	private ResponseEntity<EventResponse> appendAs(String sessionId, ParticipantSessionRequest req, EventType type) {
		AppendEventRequest eventReq = new AppendEventRequest(req.clientEventId(), type, req.userId(), null, null);
		return eventService.append(sessionId, eventReq).toResponse();
	}
}
