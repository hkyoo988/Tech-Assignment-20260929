package com.example.chat.event.dto;

import com.example.chat.event.EventType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 클라이언트 요청. seq와 serverTs는 없다 — 둘 다 서버가 정한다.
 */
public record AppendEventRequest(
        @NotBlank @Size(max = 64) String clientEventId,
        @NotNull EventType type,
        @NotBlank @Size(max = 64) String userId,
        Map<String, Object> payload,
        OffsetDateTime clientTs          // 예: "2026-09-28T10:00:00+09:00"
) {}
