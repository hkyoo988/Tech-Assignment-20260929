package com.example.chat.session;

import static com.example.chat.session.ParticipantState.JOINED;
import static com.example.chat.session.ParticipantState.LEFT;
import static com.example.chat.session.Presence.OFFLINE;
import static com.example.chat.session.Presence.ONLINE;

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

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, columnDefinition = "varchar(10)")
	private ParticipantState state;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, columnDefinition = "varchar(10)")
	private Presence presence;

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
