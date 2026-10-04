package com.example.chat.session;

import com.example.chat.event.SessionEvent;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 복원 결과. apply()로 이벤트를 하나씩 적용해 만든다. 같은 이벤트 목록이면 언제 복원해도 결과가 같다 (결정성). */
@Getter
@NoArgsConstructor
public class SessionState {

	private String sessionId;
	private SessionStatus status;
	private long lastSeq;
	private LocalDateTime lastEventAt;
	// LinkedHashMap: 순서가 고정돼 JSON 직렬화 결과까지 같다
	private Map<String, ParticipantView> participants = new LinkedHashMap<>();
	private Map<String, MessageView> messages = new LinkedHashMap<>();

	public record ParticipantView(String userId, ParticipantState state, ParticipantPresence presence) {

	}

	public record MessageView(String messageId, long seq, String senderId, String text,
							  LocalDateTime sentAt) {

	}

	// 스냅샷 저장 형태 (JSON으로 직렬화해 session_snapshot.state에 저장)
	public record Snapshot(String sessionId, SessionStatus status, long lastSeq,
						   LocalDateTime lastEventAt,
						   List<ParticipantView> participants, List<MessageView> messages) {

	}

	public static SessionState empty(String sessionId) {
		SessionState s = new SessionState();
		s.sessionId = sessionId;
		return s;
	}

	/**
	 * 이벤트 하나를 적용한다. 순수 함수: 현재 시각·랜덤·DB를 쓰지 않는다.
	 */
	public void apply(SessionEvent e) {
		// 이미 반영한 seq는 건너뜀 (중복 반영 방지)
		if (e.getSeq() <= lastSeq) {
			return;
		}

		String uid = e.getUserId();
		switch (e.getType()) {
			case SESSION_STARTED -> {
				status = SessionStatus.ACTIVE;
				for (String key : List.of("participantA", "participantB")) {
					String p = (String) e.getPayload().get(key);
					participants.put(p,
						new ParticipantView(p, ParticipantState.LEFT, ParticipantPresence.OFFLINE));
				}
			}
			case JOINED -> update(uid, ParticipantState.JOINED, ParticipantPresence.ONLINE);
			case LEFT -> update(uid, ParticipantState.LEFT, ParticipantPresence.OFFLINE);
			case DISCONNECTED -> update(uid, null, ParticipantPresence.OFFLINE);
			case RECONNECTED -> update(uid, null, ParticipantPresence.ONLINE);
			case MESSAGE_SENT -> messages.put(e.getClientEventId(), new MessageView(
				e.getClientEventId(), e.getSeq(), uid,
				e.getPayload() == null ? null : (String) e.getPayload().get("text"),
				e.getServerTs()));
			case SESSION_ENDED -> status = SessionStatus.COMPLETED;
		}
		lastSeq = e.getSeq();
		lastEventAt = e.getServerTs();
	}

	// state가 null이면 기존 입장 상태 유지 (presence만 변경)
	private void update(String userId, ParticipantState state, ParticipantPresence presence) {
		participants.computeIfPresent(userId, (k, p) ->
			new ParticipantView(k, state == null ? p.state() : state, presence));
	}

	public Snapshot toSnapshot() {
		return new Snapshot(sessionId, status, lastSeq, lastEventAt,
			List.copyOf(participants.values()), List.copyOf(messages.values()));
	}

	public static SessionState fromSnapshot(Snapshot s) {
		SessionState st = new SessionState();
		st.sessionId = s.sessionId();
		st.status = s.status();
		st.lastSeq = s.lastSeq();
		st.lastEventAt = s.lastEventAt();
		s.participants()
			.forEach(p -> st.participants.put(p.userId(), p));   // 순서 유지 (LinkedHashMap)
		s.messages().forEach(m -> st.messages.put(m.messageId(), m));
		return st;
	}
}
