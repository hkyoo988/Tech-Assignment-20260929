package com.example.chat.realtime;

import com.example.chat.event.EventAppended;
import com.example.chat.event.EventType;
import com.example.chat.event.dto.EventResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;

/** 커밋된 이벤트만 상대에게 전송한다 → 롤백된 메시지가 상대 화면에 보이지 않는다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventBroadcaster {

	private final WebSocketSessionRegistry registry;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void on(EventAppended appended) {
		log.info("② 리스너 실행");
		EventResponse e = appended.event();
		registry.broadcast(e.sessionId(), e.userId(), ServerMessage.event(e));
		// 세션이 끝나면 모든 연결을 닫는다 (이후 이벤트는 어차피 거부됨)
		if (e.type() == EventType.SESSION_ENDED) {
			registry.closeAll(e.sessionId(), CloseStatus.NORMAL.withReason("세션이 종료되었습니다"));
		}
	}
}
