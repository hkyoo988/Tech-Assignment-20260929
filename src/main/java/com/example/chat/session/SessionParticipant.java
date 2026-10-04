package com.example.chat.session;

import static com.example.chat.session.ParticipantState.JOINED;
import static com.example.chat.session.ParticipantState.LEFT;
import static com.example.chat.session.ParticipantPresence.OFFLINE;
import static com.example.chat.session.ParticipantPresence.ONLINE;

import com.example.chat.event.EventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 참여자 현재 상태 프로젝션. 이벤트에서 파생되며 리플레이로 언제든 다시 만들 수 있다. */
@Entity
@Table(name = "session_participant")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class SessionParticipant {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, columnDefinition = "char(36)")
	private String sessionId;

	@Column(nullable = false, length = 64)
	private String userId;

	// 방에 들어와 있나 (JOINED / LEFT). 사용자가 직접 입장, 퇴장해서 바뀐다
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, columnDefinition = "varchar(10)")
	private ParticipantState state;

	// 이 대화방에 지금 접속해 있나 (ONLINE / OFFLINE). 연결 끊김, 재연결로 바뀐다. 퇴장한 사람은 항상 OFFLINE
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, columnDefinition = "varchar(10)")
	private ParticipantPresence presence;

	// 마지막으로 반영한 이벤트 seq. 이 값 이하의 이벤트는 다시 반영하지 않는다 (멱등)
	@Column(nullable = false)
	private long lastAppliedSeq;

	@Column(nullable = false)
	private LocalDateTime updatedAt;

	public static SessionParticipant create(String sessionId, String userId, LocalDateTime now) {
		SessionParticipant p = new SessionParticipant();
		p.sessionId = sessionId;
		p.userId = userId;
		p.state = LEFT;          // 초기 상태는 LEFT
		p.presence = OFFLINE;    // 초기 상태는 OFFLINE
		p.lastAppliedSeq = 0;
		p.updatedAt = now;
		return p;
	}

	public void apply(EventType type, long seq, LocalDateTime at) {
		if (seq <= lastAppliedSeq) {
			return;          // 이미 반영한 이벤트는 무시
		}
		switch (type) {
			case JOINED -> {
				state = JOINED;
				presence = ONLINE;
			}
			case LEFT -> {
				state = LEFT;
				presence = OFFLINE;
			}
			case DISCONNECTED -> presence = OFFLINE;
			case RECONNECTED -> presence = ONLINE;
			default -> {
			}                          // 참여자 상태와 무관한 이벤트
		}
		lastAppliedSeq = seq;
		updatedAt = at;
	}

	public boolean isJoined() {
		return state == JOINED;
	}
}
