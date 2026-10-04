package com.example.chat.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 1:1 대화 단위이자 쓰기 모델. 이 행에 락을 걸어 seq를 발급한다. */
@Entity
@Table(name = "chat_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자. 외부에서 new 금지
public class ChatSession {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(columnDefinition = "char(36)")
	private String id;

	@Column(name = "participant_a", nullable = false, length = 64)
	private String participantA;

	@Column(name = "participant_b", nullable = false, length = 64)
	private String participantB;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, columnDefinition = "varchar(20)")
	private SessionStatus status;

	// 마지막 발급 seq. MAX(seq)를 복사해 둔 값이라, 락을 잡은 이 행에서 추가 조회 없이 다음 번호를 낸다
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

	// 순서 번호는 서버만, 이 메서드로만 발급. 락 안에서만 호출되며 바뀐 값은 변경 감지로 커밋 시 UPDATE된다
	public long nextSeq() {
		return ++lastSeq;
	}

	public void end(LocalDateTime now) {
		this.status = SessionStatus.COMPLETED;
		this.endedAt = now;
	}

	public boolean isCompleted() {
		return status == SessionStatus.COMPLETED;
	}
}
