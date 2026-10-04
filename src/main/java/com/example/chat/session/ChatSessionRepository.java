package com.example.chat.session;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {

	/** SELECT ... FOR UPDATE. 같은 세션의 쓰기를 한 줄로 세우는 핵심 락. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT s FROM ChatSession s WHERE s.id = :id")
	Optional<ChatSession> findByIdForUpdate(@Param("id") String id);

	@Query("""
		SELECT s FROM ChatSession s, SessionParticipant p
		WHERE p.sessionId = s.id
		  AND p.userId = :userId
		  AND (:status IS NULL OR s.status = :status)
		  AND s.startedAt >= :from AND s.startedAt < :to
		ORDER BY s.startedAt DESC
		""")
	/** OR 조건 대신 참여자 테이블을 거쳐 그 사용자의 세션만 읽는다. */
	List<ChatSession> findByParticipant(@Param("userId") String userId,
										@Param("status") SessionStatus status,
										@Param("from") LocalDateTime from,
										@Param("to") LocalDateTime to,
										Pageable pageable);
}
