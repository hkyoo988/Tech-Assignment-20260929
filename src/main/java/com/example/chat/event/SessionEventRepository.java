package com.example.chat.event;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionEventRepository extends JpaRepository<SessionEvent, Long> {

	List<SessionEvent> findBySessionIdAndSeqGreaterThanOrderBySeqAsc(String sessionId,
		long afterSeq, Pageable pageable);

	Optional<SessionEvent> findBySessionIdAndClientEventId(String sessionId, String clientEventId);

	List<SessionEvent> findBySessionIdAndSeqLessThanEqualOrderBySeqAsc(String sessionId, long seq);

	Optional<SessionEvent> findTopBySessionIdAndServerTsLessThanEqualOrderBySeqDesc(
        String sessionId, LocalDateTime at);
}
