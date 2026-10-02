# 5. 쿼리 / 인덱스 최적화

> 상태: **실측 완료** — MySQL 8.4, 시드 데이터(`scripts/seed-perf.sql`)로 `EXPLAIN` / `EXPLAIN ANALYZE` 측정

## 5.0 측정 환경

| 테이블 | 건수 | 분포 |
|---|---|---|
| `chat_session` | 100,012 | 사용자 1,000명, 1인당 약 200개 세션 |
| `session_participant` | 약 200,000 | 세션당 2행 |
| `session_event` | 약 100,000 | 2,000개 세션 × 50개 |

데이터가 적으면 옵티마이저가 인덱스를 무시하고 전체를 읽기 때문에, 측정 전 시드 데이터를 넣고 `ANALYZE TABLE`로 통계를 갱신했다.

**관련 인덱스 (V1, V2)**

| 테이블 | 인덱스 | 컬럼 | 용도 |
|---|---|---|---|
| `session_event` | `uk_event_seq` | `(session_id, seq)` UNIQUE | 순서 보장 + 증분 조회·리플레이 |
| `session_event` | `uk_event_client_id` | `(session_id, client_event_id)` UNIQUE | 멱등 키 조회 + 중복 저장 최후 방어 |
| `session_event` | `idx_event_session_server_ts` | `(session_id, server_ts)` | 시각 → seq 변환 |
| `session_participant` | `idx_participant_user` | `(user_id, session_id)` | 사용자별 세션 목록 |

---

## 5.1 Q1. 이벤트 증분 조회 (재연결 RESUME · 복원 리플레이 · `GET /events`)

```sql
SELECT * FROM session_event
WHERE session_id = ? AND seq > ?
ORDER BY seq LIMIT 100;
```

| type | key | key_len | rows | Extra |
|---|---|---|---|---|
| `ref` | `uk_event_seq` | 144 | 50 | Using index condition |

**해석**
- `uk_event_seq (session_id, seq)`를 사용한다. key_len 144 = `session_id` CHAR(36) × utf8mb4 4바이트로, 선두 컬럼으로 세션을 찾고 `seq > ?`는 인덱스 조건 푸시다운(ICP)으로 인덱스 단계에서 걸러낸다.
- **`Using filesort`가 없다.** 인덱스가 이미 seq 순으로 정렬되어 있어 `ORDER BY seq`를 위한 정렬이 필요 없고, `LIMIT`에서 바로 멈춘다.
- UNIQUE 제약(순서 보장)과 조회 인덱스를 **하나의 인덱스로 겸용**한다.

**병목과 대응**
| 상황 | 대응 |
|---|---|
| 오래 끊겼던 클라이언트가 수만 건을 요청 | 페이지 크기 상한(RESUME 500, REST 500) + `hasMore`로 나눠 받기 (구현됨) |
| 페이지가 깊어짐 | `OFFSET`이 아니라 **seq 커서**(`seq > 마지막으로 받은 seq`) 방식이라 몇 번째 페이지든 비용이 같다 (구현됨) |
| 세션 하나의 이벤트가 매우 많아 복원 리플레이가 느림 | 스냅샷 + 이후 이벤트만 리플레이 ([04 문서](04-state-restoration.md#42-복원-알고리즘-스냅샷--리플레이)) |

---

## 5.2 Q2. 복원 시각 → seq 변환 (`GET /timeline?at=`)

```sql
SELECT seq FROM session_event
WHERE session_id = ? AND server_ts <= ?
ORDER BY seq DESC LIMIT 1;
```

| type | key | key_len | rows | Extra |
|---|---|---|---|---|
| `range` | `idx_event_session_server_ts` | 152 | 30 | Using index condition; **Using filesort** |

**해석**
- key_len 152 = 144(`session_id`) + 8(`server_ts` DATETIME(6))로 두 컬럼을 모두 사용한 범위 스캔이다.
- 그러나 인덱스는 `server_ts` 순인데 정렬은 `seq` 기준이라 **범위에 걸린 행 전부를 정렬(filesort)한 뒤 1건만 쓴다.**

**병목**: 이벤트가 100만 건인 세션에서 최근 시각으로 복원하면 거의 100만 건을 정렬한다. 비용이 "세션의 이벤트 수"에 비례한다.

**개선안 (미적용, D8과 함께 적용 예정)**

| 방안 | 내용 | 전제 |
|---|---|---|
| A. 정렬 기준을 인덱스에 맞춤 | `ORDER BY server_ts DESC, seq DESC LIMIT 1` + 인덱스 `(session_id, server_ts, seq)` → 인덱스를 거꾸로 읽어 **1건만 읽고 종료** | 세션 내에서 `server_ts`가 seq와 같은 방향으로 증가해야 결과가 같다 → **D8(server_ts 단조 보정)** 필요 |
| B. 스냅샷에 시각 저장 | 스냅샷 행에 `server_ts`를 함께 두고 먼저 스냅샷 구간을 찾은 뒤 그 안의 이벤트만 탐색 | 스냅샷 구현 |

현재는 1:1 세션의 이벤트 수가 크지 않고 `atSeq`(Q1과 같은 인덱스 경로)도 제공하므로 우선순위를 낮췄다. D8 없이 A를 적용하면 서버 간 시계 차이로 "seq는 더 큰데 server_ts는 더 작은" 이벤트가 생길 때 잘못된 seq를 고를 수 있어, **정합성 우선으로 현재 쿼리를 유지**했다.

---

## 5.3 Q3. 사용자 세션 목록 (`GET /sessions?participant=&status=&from=&to=`)

1:1 세션에서 사용자는 `participant_a` 또는 `participant_b` 중 어느 쪽에도 있을 수 있다. 이 "OR 문제"를 참여자 테이블로 해결했는지 두 방식을 비교했다.

### 채택: 참여자 테이블 경유

```sql
SELECT s.* FROM chat_session s
JOIN session_participant p ON p.session_id = s.id
WHERE p.user_id = ? AND (상태 조건) AND s.started_at >= ? AND s.started_at < ?
ORDER BY s.started_at DESC LIMIT 20;
```

| table | type | key | rows | Extra |
|---|---|---|---|---|
| p | `ref` | `idx_participant_user` | 200 | **Using index**; Using temporary; Using filesort |
| s | `eq_ref` | `PRIMARY` | 1 | Using where |

```
-> Limit: 20 row(s)                                     (actual time=1.27 ms)
    -> Sort: s.started_at DESC, limit input to 20 row(s)
        -> Nested loop inner join                       (rows=200)
            -> Covering index lookup on p using idx_participant_user (user_id='user42')  (rows=200)
            -> Single-row index lookup on s using PRIMARY (id=p.session_id)              (loops=200)
```

### 비교: 참여자 테이블 없이 OR 조건

```sql
SELECT * FROM chat_session
WHERE participant_a = ? OR participant_b = ?
ORDER BY started_at DESC LIMIT 20;
```

```
-> Limit: 20 row(s)                                     (actual time=18.9 ms)
    -> Sort: chat_session.started_at DESC
        -> Filter: (participant_a = 'user42' or participant_b = 'user42')   (rows=200)
            -> Table scan on chat_session                                    (rows=100,012)
```

### 결과

| | 참여자 테이블 (채택) | OR 조건 |
|---|---|---|
| 읽은 행 | **200** (그 사용자의 세션만) | **100,012** (전체) |
| 실행 시간 | **1.27 ms** | 18.9 ms (약 15배) |
| 비용 증가 요인 | 사용자 1명의 세션 수 | **전체 세션 수** |

**해석**
- `idx_participant_user (user_id, session_id)`는 **커버링 인덱스**(`Using index`)다. 필요한 `session_id`가 인덱스 안에 있어 참여자 테이블 본문을 읽지 않는다.
- 이어서 세션당 PK 1건 조회(`eq_ref`)로 세션 행을 가져온다.
- OR 방식은 지금도 15배 느리고, 세션이 1,000만 개가 되면 비용도 100배가 된다. 참여자 테이블 방식은 **전체 데이터 양과 무관하게 사용자 1명의 세션 수에만 비례**한다.
- 이것이 D2에서 참여자 테이블을 따로 둔 근거다. `participant_a`, `participant_b`에 인덱스를 각각 거는 방법(index merge union)도 있지만, 본 테이블에 인덱스가 2개 늘고 정렬 문제는 그대로 남는다.

### 남은 병목: 정렬 (`Using temporary; Using filesort`)

`started_at`은 `chat_session`에 있으므로, 그 사용자의 세션을 **모두 조인한 뒤 정렬**해야 상위 20개를 알 수 있다. 1인당 200개에서는 1ms지만, 세션이 10만 개인 사용자(봇, 상담원 등)는 매 요청마다 10만 건을 정렬한다.

**개선안 (확장 시)**

| 방안 | 내용 | 효과 |
|---|---|---|
| A. 정렬 키 비정규화 | `session_participant`에 `started_at`, `status` 복제 + 인덱스 `(user_id, started_at)` | 인덱스 순서대로 읽고 20건에서 멈춤. 정렬 없음. 메신저의 "사용자별 받은 편지함(inbox)" 테이블과 같은 구조 |
| B. 커서 페이지네이션 | `started_at < :마지막으로 본 값` 조건으로 다음 페이지 | `OFFSET`은 깊은 페이지일수록 앞부분을 모두 읽고 버리지만, 커서는 페이지 깊이와 무관 |

A는 참여자 테이블이 이미 **이벤트에서 파생된 프로젝션**이라는 점에서 자연스러운 확장이다. 새 컬럼도 `SESSION_STARTED`/`SESSION_ENDED` 이벤트로 갱신하고, 이벤트 리플레이로 재생성할 수 있다. 현재 범위(1:1 채팅, 사용자당 세션 수가 많지 않음)에서는 1ms 수준이라 적용하지 않았다.

---

## 5.4 쓰기 경로: 이벤트 append

```
SELECT … FROM chat_session WHERE id = ? FOR UPDATE      -- PK, 세션 행 락
SELECT … FROM session_event WHERE session_id = ? AND client_event_id = ?   -- uk_event_client_id
SELECT … FROM session_participant WHERE session_id = ? AND user_id = ?     -- uk_participant
INSERT INTO session_event …
UPDATE session_participant … / UPDATE chat_session SET last_seq = ?
COMMIT
```

- 모든 조회가 PK 또는 UNIQUE 인덱스의 **단건 조회**라 데이터 양과 무관하다.
- **병목은 쿼리가 아니라 락 보유 시간**이다. 같은 세션의 쓰기는 직렬화되므로 세션 하나의 처리량 = 1 / (트랜잭션 시간).
  - 1:1 채팅은 세션당 초당 쓰기가 많지 않아 문제가 되지 않는다 (동시 20건 테스트도 0.12초).
  - 트랜잭션 안에 외부 호출(WebSocket 전송 등)을 넣지 않고 커밋 후로 미룬 것(D17)이 락 보유 시간을 줄이는 핵심이다.
- 락 대기가 길어지면 `503 TRY_AGAIN` + `Retry-After`로 응답하고, 같은 `clientEventId`로 재시도하면 멱등 처리된다.

## 5.5 대량 데이터 전략 (설계)

| 전략 | 대상 | 내용 |
|---|---|---|
| 파티셔닝 | `session_event` | `session_id` 해시 파티션. 모든 핫 쿼리가 `session_id`를 조건에 포함하므로 파티션 1개만 읽는다(partition pruning) |
| 아카이빙 | 종료 후 오래된 세션의 이벤트 | 최종 스냅샷을 남기고 이벤트는 저비용 저장소로 이동. 복원 요청 시 아카이브에서 조회 |
| 샤딩 | 전체 | 세션 단위로 데이터가 닫혀 있어(조인이 세션 안에서만 일어남) `session_id` 기준 샤딩이 쉽다. 사용자별 목록(Q3)은 샤드를 가로지르므로 inbox 프로젝션(5.3 A)을 사용자 기준으로 별도 저장 |
| 페이지네이션 | 모든 목록 | `OFFSET` 대신 커서 (Q1은 seq 커서로 구현됨) |
