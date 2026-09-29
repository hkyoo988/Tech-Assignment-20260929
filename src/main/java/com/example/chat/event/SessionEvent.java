package com.example.chat.event;

import com.example.chat.common.JsonMapConverter;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

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

    public static SessionEvent of(String sessionId, long seq, String clientEventId, EventType type,
                                  String userId, Map<String, Object> payload,
                                  LocalDateTime clientTs, LocalDateTime serverTs) {
        SessionEvent e = new SessionEvent();
        e.sessionId = sessionId;
        e.seq = seq;
        e.clientEventId = clientEventId;
        e.type = type;
        e.userId = userId;
        e.payload = payload;
        e.clientTs = clientTs;
        e.serverTs = serverTs;
        return e;
    }
}
