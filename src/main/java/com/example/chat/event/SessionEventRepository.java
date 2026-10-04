package com.example.chat.event;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionEventRepository extends JpaRepository<SessionEvent, Long> {

	// 증분 조회 (RESUME, GET /events). uk_event_seq 범위 조회라 정렬 없이 seq 순으로 읽는다
	List<SessionEvent> findBySessionIdAndSeqGreaterThanOrderBySeqAsc(String sessionId,
		long afterSeq, Pageable pageable);

	// 멱등 키 조회 (uk_event_client_id)
	Optional<SessionEvent> findBySessionIdAndClientEventId(String sessionId, String clientEventId);

	// 시각 → seq 변환 (?at=). 범위 안을 seq로 정렬하므로 filesort가 생긴다
	Optional<SessionEvent> findTopBySessionIdAndServerTsLessThanEqualOrderBySeqDesc(
        String sessionId, LocalDateTime at);

	// 복원 리플레이: 스냅샷 seq 초과 ~ targetSeq 이하
	List<SessionEvent> findBySessionIdAndSeqGreaterThanAndSeqLessThanEqualOrderBySeqAsc(
		String sessionId, long afterSeq, long toSeq);
}
