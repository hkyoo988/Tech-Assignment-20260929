package com.example.chat.realtime;

import com.example.chat.event.EventAppended;
import com.example.chat.event.dto.EventResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
    }
}
