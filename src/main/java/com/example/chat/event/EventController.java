package com.example.chat.event;

import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.dto.AppendResult;
import com.example.chat.event.dto.EventResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sessions/{sessionId}/events")
@RequiredArgsConstructor
public class EventController {

	private final EventService eventService;

	@PostMapping
	public ResponseEntity<EventResponse> append(@PathVariable String sessionId,
		@Valid @RequestBody AppendEventRequest req) {

		AppendResult result = eventService.append(sessionId, req);
		if(result.duplicate()) {
			return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.event());
		}
		return ResponseEntity.status(HttpStatus.CREATED).body(result.event());
	}

	@GetMapping
	public List<EventResponse> getEvents(@PathVariable String sessionId,
		@RequestParam(defaultValue = "0") long afterSeq,
		@RequestParam(defaultValue = "100") int size) {
		return eventService.getEvents(sessionId, afterSeq, size);
	}
}
