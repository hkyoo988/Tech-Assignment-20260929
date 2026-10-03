package com.example.chat.timeline;

import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SnapshotRepository {

	private final JdbcTemplate jdbc;

	public record SnapshotRow(long seq, String stateJson) {}

	/** 같은 (session_id, seq)가 이미 있으면 아무것도 하지 않는다 → 중복 생성돼도 안전 (멱등) */
	public void insertIgnore(String sessionId, long seq, String stateJson, LocalDateTime createdAt) {
		jdbc.update("INSERT IGNORE INTO session_snapshot (session_id, seq, state, created_at) VALUES (?, ?, ?, ?)",
				sessionId, seq, stateJson, createdAt);
	}

	/** maxSeq 이하에서 가장 최근 스냅샷 */
	public Optional<SnapshotRow> findLatest(String sessionId, long maxSeq) {
		return jdbc.query("""
						SELECT seq, state FROM session_snapshot
						WHERE session_id = ? AND seq <= ?
						ORDER BY seq DESC LIMIT 1
						""",
				(rs, i) -> new SnapshotRow(rs.getLong("seq"), rs.getString("state")),
				sessionId, maxSeq).stream().findFirst();
	}
}
