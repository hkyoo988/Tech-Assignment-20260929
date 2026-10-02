-- 성능 측정용 시드 데이터 (개발 DB 전용, 테스트와 무관)
-- 실행: docker exec -i chat-mysql mysql -uchat -pchat chat < scripts/seed-perf.sql
-- 초기화: docker compose down -v && docker compose up -d
-- 세션 10만 개 (사용자 1000명, 1인당 약 200개), 그중 2000개 세션에 이벤트 50개씩 (10만 건)
SET SESSION cte_max_recursion_depth = 200000;

INSERT INTO chat_session (id, participant_a, participant_b, status, last_seq, started_at, ended_at)
WITH RECURSIVE n AS (SELECT 1 AS i UNION ALL SELECT i + 1 FROM n WHERE i < 100000)
SELECT UUID(),
       CONCAT('user', i % 1000),
       CONCAT('user', (i * 7 + 1) % 1000),
       IF(i % 3 = 0, 'COMPLETED', 'ACTIVE'),
       0,
       TIMESTAMP('2026-01-01') + INTERVAL (i * 2) MINUTE,
       IF(i % 3 = 0, TIMESTAMP('2026-01-01') + INTERVAL (i * 2 + 60) MINUTE, NULL)
FROM n;

INSERT INTO session_participant (session_id, user_id, state, presence, last_applied_seq, updated_at)
SELECT s.id, s.participant_a, 'LEFT', 'OFFLINE', 0, s.started_at FROM chat_session s
WHERE NOT EXISTS (SELECT 1 FROM session_participant p WHERE p.session_id = s.id)
UNION ALL
SELECT s.id, s.participant_b, 'LEFT', 'OFFLINE', 0, s.started_at FROM chat_session s
WHERE NOT EXISTS (SELECT 1 FROM session_participant p WHERE p.session_id = s.id);

INSERT INTO session_event (session_id, seq, client_event_id, type, user_id, payload, client_ts, server_ts)
WITH RECURSIVE k AS (SELECT 1 AS seq UNION ALL SELECT seq + 1 FROM k WHERE seq < 50)
SELECT s.id, k.seq, CONCAT('seed-', k.seq), 'MESSAGE_SENT', s.participant_a,
       JSON_OBJECT('text', CONCAT('msg ', k.seq)), NULL, s.started_at + INTERVAL k.seq SECOND
FROM (SELECT id, participant_a, started_at FROM chat_session
      WHERE participant_a LIKE 'user%' ORDER BY started_at LIMIT 2000) s
CROSS JOIN k;

ANALYZE TABLE chat_session, session_participant, session_event;
