package com.example.chat.session;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "chat_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자. 외부에서 new 금지
public class ChatSession {

    @Id
    @Column(columnDefinition = "char(36)")
    private String id;

    @Column(nullable = false, length = 64)
    private String participantA;

    @Column(nullable = false, length = 64)
    private String participantB;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    private SessionStatus status;

    @Column(nullable = false)
    private long lastSeq;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime endedAt;

    // 생성은 이 정적 메서드로만 → 초기 상태 규칙이 한 곳에 모임
    public static ChatSession start(String participantA, String participantB, LocalDateTime now) {
        if (participantA.equals(participantB)) {
            throw new IllegalArgumentException("자기 자신과는 세션을 만들 수 없습니다");
        }
        ChatSession s = new ChatSession();
        s.id = UUID.randomUUID().toString();
        s.participantA = participantA;
        s.participantB = participantB;
        s.status = SessionStatus.ACTIVE;
        s.lastSeq = 0;
        s.startedAt = now;
        return s;
    }

    public boolean isParticipant(String userId) {
        return participantA.equals(userId) || participantB.equals(userId);
    }

    // 순서 번호는 서버만, 이 메서드로만 발급 (단조 증가 보장)
    public long nextSeq() {
        return ++lastSeq;
    }
}
