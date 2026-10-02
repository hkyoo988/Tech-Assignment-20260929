package com.example.chat.event;

import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.dto.AppendResult;
import com.example.chat.event.dto.EventResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Event", description = "이벤트 수집·조회 (멱등, seq 순서)")
@RestController
@RequestMapping("/sessions/{sessionId}/events")
@RequiredArgsConstructor
public class EventController {

	private final EventService eventService;

	@Operation(summary = "이벤트 수집",
			description = "세션 락 → 중복 확인 → 검증 → 저장 순서로 처리한다. 같은 clientEventId 재전송은 최초 결과를 그대로 반환한다.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "신규 저장"),
			@ApiResponse(responseCode = "200", description = "중복 재전송 — 최초 결과 반환",
					headers = @Header(name = "Idempotent-Replayed", description = "true")),
			@ApiResponse(responseCode = "400", description = "참여자 아님 / 서버 전용 타입 / 입력 오류", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "409", description = "같은 clientEventId에 다른 내용 / 종료된 세션 / JOINED 아님", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "503", description = "락 대기 초과 — 같은 clientEventId로 재시도하면 안전",
					headers = @Header(name = "Retry-After", description = "초"), content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	@PostMapping
	public ResponseEntity<EventResponse> append(@PathVariable String sessionId,
		@Valid @RequestBody AppendEventRequest req) {
		return eventService.append(sessionId, req).toResponse();
	}

	@Operation(summary = "이벤트 증분 조회", description = "afterSeq보다 큰 이벤트를 seq 오름차순으로 반환 (최대 500). 재연결 동기화에 사용.")
	@GetMapping
	public List<EventResponse> getEvents(@PathVariable String sessionId,
		@RequestParam(defaultValue = "0") long afterSeq,
		@RequestParam(defaultValue = "100") int size) {
		return eventService.getEvents(sessionId, afterSeq, size);
	}
}
