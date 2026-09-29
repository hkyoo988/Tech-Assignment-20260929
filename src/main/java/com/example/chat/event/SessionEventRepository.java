package com.example.chat.event;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionEventRepository extends JpaRepository<SessionEvent, Long> {

    // 메서드 이름으로 쿼리 생성: WHERE session_id = ? AND seq > ? ORDER BY seq ASC LIMIT ?
    List<SessionEvent> findBySessionIdAndSeqGreaterThanOrderBySeqAsc(String sessionId, long afterSeq, Pageable pageable);
}
