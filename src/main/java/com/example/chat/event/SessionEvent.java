package com.example.chat.event;

import com.example.chat.common.JsonMapConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 이벤트 저장소의 한 행. append-only이므로 상태 변경 메서드가 없다.
 */
@Entity
@Table(name = "session_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SessionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "char(36)")
    private String sessionId;

    @Column(nullable = false)
    private long seq;                      // 순서의 단일 기준 (서버 부여)

    @Column(nullable = false, length = 64)
    private String clientEventId;          // 멱등성 키 (클라이언트 생성)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(30)")
    private EventType type;

    @Column(nullable = false, length = 64)
    private String userId;

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "json")
    private Map<String, Object> payload;

    private LocalDateTime clientTs;        // 참고용 (신뢰하지 않음)

    @Column(nullable = false)
    private LocalDateTime serverTs;        // 서버 수신 시각

    @Builder
    private SessionEvent(String sessionId, long seq, String clientEventId, EventType type,
        String userId, Map<String, Object> payload,
        LocalDateTime clientTs, LocalDateTime serverTs) {
        this.sessionId = sessionId;
        this.seq = seq;
        this.clientEventId = clientEventId;
        this.type = type;
        this.userId = userId;
        this.payload = payload;
        this.clientTs = clientTs;
        this.serverTs = serverTs;
    }

    public boolean hasSameContent(EventType type, String userId, Map<String, Object> payload) {
		return this.type == type
				&& this.userId.equals(userId)
				&& Objects.equals(this.payload, payload);   // payload는 null일 수 있어서 Objects.equals
	}
}
