# 5. 쿼리 / 인덱스 최적화

> 상태: **뼈대** — 구현 후 실제 SQL과 `EXPLAIN` 결과로 채운다.

## 5.1 핫패스 쿼리

### Q1. 이벤트 append (가장 빈번한 쓰기)
- SQL: 세션 락 → 멱등 키 조회 → INSERT → last_seq UPDATE
- 인덱스: PK, `uk_event_client_id`
- 예상 병목: 한 세션에 쓰기가 몰릴 때 락 대기
- 개선 방향: TODO (락 보유 시간 최소화, 트랜잭션 범위 축소)

### Q2. 재연결 resume (`seq > lastSeq`)
- SQL: TODO
- 인덱스: `uk_event_seq (session_id, seq)`
- 예상 병목: 오래 끊겼던 클라이언트의 대량 조회
- 개선 방향: TODO (페이지 크기 제한, 너무 오래됐으면 스냅샷 기반 전체 동기화)

### Q3. 시점 복원 (스냅샷 + 범위 스캔)
- SQL: TODO
- 인덱스: `session_snapshot PK`, `idx_event_session_server_ts`
- 예상 병목: 스냅샷이 없거나 오래된 경우 리플레이 증가
- 개선 방향: TODO

## 5.2 대량 데이터 전략
- TODO: 파티셔닝(세션 해시 / 기간), 오래된 이벤트 아카이빙, 커버링 인덱스, 페이지네이션 방식(커서 vs offset)

## 5.3 EXPLAIN 결과
- TODO
