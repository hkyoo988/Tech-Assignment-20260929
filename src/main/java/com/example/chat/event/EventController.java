package com.example.chat.event;

import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.dto.EventResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sessions/{sessionId}/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventResponse append(@PathVariable String sessionId, @Valid @RequestBody AppendEventRequest req) {
        return eventService.append(sessionId, req);
    }

    // 재연결 시 "afterSeq 이후 것만 달라"는 용도로도 사용 (4일차)
    @GetMapping
    public List<EventResponse> list(@PathVariable String sessionId,
                                    @RequestParam(defaultValue = "0") long afterSeq,
                                    @RequestParam(defaultValue = "100") int size) {
        return eventService.list(sessionId, afterSeq, size);
    }
}
