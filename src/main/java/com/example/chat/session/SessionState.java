package com.example.chat.session;

import com.example.chat.event.SessionEvent;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SessionState {

	private String sessionId;
	private SessionStatus status;
	private long lastSeq;
	private LocalDateTime lastEventAt;
	private Map<String, ParticipantView> participants = new LinkedHashMap<>();
	private Map<String, MessageView> messages = new LinkedHashMap<>();

	public record ParticipantView(String userId, ParticipantState state, Presence presence) {

	}

	public record MessageView(String messageId, long seq, String senderId, String text,
							  LocalDateTime sentAt) {

	}

	public static SessionState empty(String sessionId) {
        SessionState s = new SessionState();
        s.sessionId = sessionId;
        return s;
    }

    /** 이벤트 하나를 적용한다. 순수 함수: 현재 시각·랜덤·DB를 쓰지 않는다. */
    public void apply(SessionEvent e) {
        if (e.getSeq() <= lastSeq) {
            return;
        }

        String uid = e.getUserId();
        switch (e.getType()) {
            case SESSION_STARTED -> {
                status = SessionStatus.ACTIVE;
                for (String key : List.of("participantA", "participantB")) {
                    String p = (String) e.getPayload().get(key);
                    participants.put(p, new ParticipantView(p, ParticipantState.LEFT, Presence.OFFLINE));
                }
            }
            case JOINED -> update(uid, ParticipantState.JOINED, Presence.ONLINE);
            case LEFT -> update(uid, ParticipantState.LEFT, Presence.OFFLINE);
            case DISCONNECTED -> update(uid, null, Presence.OFFLINE);
            case RECONNECTED -> update(uid, null, Presence.ONLINE);
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
    private void update(String userId, ParticipantState state, Presence presence) {
        participants.computeIfPresent(userId, (k, p) ->
                new ParticipantView(k, state == null ? p.state() : state, presence));
    }
}
