# 4. 주요 쿼리 + 인덱스 근거 + 병목

> 상태: **실측 완료** — MySQL 8.4, 시드 데이터(`scripts/seed-perf.sql`)로 `EXPLAIN` / `EXPLAIN ANALYZE` 측정

## 1. 측정 환경

| 테이블 | 건수 | 분포 |
|---|---|---|
| `chat_session` | 100,012 | 사용자 1,000명, 1인당 약 200개 세션 |
| `session_participant` | 약 200,000 | 세션당 2행 |
| `session_event` | 약 100,000 | 2,000개 세션 × 50개 |

데이터가 적으면 옵티마이저가 인덱스를 무시하고 전체를 읽기 때문에, 측정 전 시드 데이터를 넣고 `ANALYZE TABLE`로 통계를 갱신했다.

시드 데이터는 자동으로 들어가지 않는다(Flyway 마이그레이션도, 테스트도 아님). 앱을 한 번 띄워 테이블을 만든 뒤 직접 넣는다.

```bash
docker exec -i chat-mysql mysql -uchat -pchat chat < scripts/seed-perf.sql   # 넣기
docker compose down -v && docker compose up -d                              # 지우고 초기화
```

**관련 인덱스 (V1, V2)**

| 테이블 | 인덱스 | 컬럼 | 용도 |
|---|---|---|---|
| `session_event` | `uk_event_seq` | `(session_id, seq)` UNIQUE | 순서 보장 + 증분 조회, 리플레이 |
| `session_event` | `uk_event_client_id` | `(session_id, client_event_id)` UNIQUE | 멱등 키 조회 + 중복 저장 최후 방어 |
| `session_event` | `idx_event_session_server_ts` | `(session_id, server_ts)` | 시각 → seq 변환 |
| `session_participant` | `idx_participant_user` | `(user_id, session_id)` | 사용자별 세션 목록 |

---

## 2. 대량 데이터 조회 성능 전략

| 전략 | 상태 | 대상 | 내용 |
|---|---|---|---|
| 세션 단위로 범위를 좁히는 인덱스 | ✅ 구현 | 모든 핫 쿼리 | 이벤트, 참여자 인덱스가 모두 `session_id`(또는 `user_id`)로 시작해, 전체 데이터가 늘어도 한 세션(한 사용자) 분량만 읽는다 (§3) |
| 커서 페이지네이션 | ✅ 구현 (이벤트 조회) | `GET /events`, RESUME | `OFFSET` 대신 `seq > 마지막으로 받은 seq` 커서. 페이지가 깊어져도 비용이 같다 (쿼리 1) |
| 조회 크기 상한 | ✅ 구현 | 이벤트 조회, 세션 목록 | 이벤트 최대 500건, 세션 목록 최대 100건 |
| 스냅샷 | ✅ 구현 | 시점 복원 | 리플레이할 이벤트를 최대 99건으로 제한 ([6 §5](6-state-restoration.md#5-스냅샷-정책--구현-완료)) |
| 커서 페이지네이션 | ❌ 설계 | 세션 목록 | 현재는 최신 N건만 반환. `started_at < 마지막 값` 커서로 다음 페이지 (쿼리 3 개선안 B) |
| 파티셔닝 | ❌ 설계 | `session_event` | `session_id` 해시 파티션. 모든 핫 쿼리가 `session_id`를 조건에 포함하므로 파티션 1개만 읽는다(partition pruning). 전제: MySQL은 FK가 있는 테이블을 파티셔닝할 수 없고 파티션 키가 PK에 포함돼야 하므로, `fk_event_session` 제거와 PK 변경(예: `(session_id, id)`)이 필요 |
| 아카이빙 | ❌ 설계 | 종료 후 오래된 세션의 이벤트 | 최종 스냅샷을 남기고 이벤트는 저비용 저장소로 이동. 복원 요청 시 아카이브에서 조회 |
| 샤딩 | ❌ 설계 | 전체 | 세션 단위로 데이터가 닫혀 있어(조인이 세션 안에서만 일어남) `session_id` 기준 샤딩이 쉽다. 사용자별 목록(쿼리 3)은 샤드를 가로지르므로 inbox 프로젝션(쿼리 3 개선안 A)을 사용자 기준으로 별도 저장 |

## 3. 인덱스 설계 근거 (핫패스)

인덱스의 기본 목적은 조회 속도다. 쿼리 결과가 1건이어도, 인덱스가 없으면 그 1건을 찾기 위해 많은 행을 읽어야 한다. 아래 인덱스 중 일부는 같은 인덱스로 **유일성 보장**(UNIQUE 제약은 인덱스로 구현됨)이나 **락 범위 제한**(InnoDB는 조회하며 거친 인덱스 항목에 락을 건다)까지 함께 얻는다. 대신 인덱스가 많을수록 쓰기가 느려지므로, 핫패스 쿼리나 정합성에 필요한 것만 두었다.

| 인덱스 | 사용하는 쿼리                                                             | 조회 효과 | 추가 역할 |
|---|---------------------------------------------------------------------|---|---|
| `chat_session` PK | 이벤트 수집 시작 `SELECT … WHERE id=? FOR UPDATE`                          | 세션 행을 바로 찾음 (모든 쓰기의 시작점) | **락 범위 제한**: 찾은 세션 행에만 락이 걸려, 다른 세션끼리는 서로 기다리지 않음 |
| `uk_event_client_id (session_id, client_event_id)` | 중복 확인 `WHERE session_id=? AND client_event_id=?`                    | 인덱스가 없으면 세션의 이벤트를 전부 읽어야 함. **매 쓰기마다, 락을 잡은 상태에서** 실행되므로 빨리 끝나야 락 보유 시간이 짧아짐 | **유일성 보장**: 같은 `clientEventId`의 중복 저장을 DB가 거부 (최후 방어선) |
| `uk_event_seq (session_id, seq)` | 재연결 동기화, 이력 조회, 리플레이 복원 `WHERE session_id=? AND seq>? ORDER BY seq` | 범위를 바로 찾고, 이미 seq 순서라 **정렬 작업이 필요 없음** | **유일성 보장**: 같은 seq의 중복 저장을 DB가 거부 (최후 방어선) |
| `idx_event_session_server_ts (session_id, server_ts)` | 시각 기준 복원 `?at=` → 그 시각 이전 마지막 seq                                   | 시각으로 범위는 빠르게 찾지만, 그 안에서 seq 최댓값을 고르려면 범위 전체를 다시 정렬해야 한다(EXPLAIN의 Using filesort). 정렬을 없애는 방법과 지금 적용하지 않은 이유는 [아래 쿼리 2](#쿼리-2-복원-시각--seq-변환-get-timelineat) | - |
| `uk_participant (session_id, user_id)` | 참여자 상태 조회, 갱신 (메시지 전송 시 JOINED 확인, 입장/퇴장 반영)                         | 참여자 행을 바로 찾음 | **유일성 보장**: 세션당 같은 사용자 행이 두 개 생기지 않음 |
| `idx_participant_user (user_id, session_id)` | 사용자별 세션 목록 `GET /sessions?participant=`                             | `participant_a OR participant_b` 전체 스캔 대신 그 사용자 세션만 읽음. 필요한 `session_id`가 인덱스 안에 있어 **커버링 인덱스**로 동작 (실측 15배, [아래 쿼리 3](#쿼리-3-사용자-세션-목록-get-sessionsparticipantstatusfromto)) | - |
| `session_snapshot` PK `(session_id, seq)` | 복원 시 `WHERE session_id=? AND seq<=? ORDER BY seq DESC LIMIT 1`      | 인덱스를 거꾸로 읽어 가장 가까운 스냅샷 1건에서 멈춤 | **유일성 보장**: `INSERT IGNORE`와 함께 같은 스냅샷 중복 생성 방지 |

**쓰기 경로**: 이벤트 저장 시 실행되는 조회(세션 락, 중복 확인, 참여자 확인)는 모두 PK, UNIQUE 단건 조회라 데이터 양과 무관하다. 병목은 쿼리가 아니라 세션 락 보유 시간이며, 이에 대한 설계는 [5 설계 문서 §3](5-design.md#3-순서-기준과-중복-처리--구현-완료)에 있다.

## 4. 핫패스 주요 쿼리

### 쿼리 1. 이벤트 증분 조회 (재연결 RESUME, 복원 리플레이, `GET /events`)

코드: `SessionEventRepository.findBySessionIdAndSeqGreaterThanOrderBySeqAsc` (복원 리플레이는 같은 인덱스의 범위 조회 `…SeqGreaterThanAndSeqLessThanEqual…`)

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

**쿼리 1이 느려지는 경우와 대응**

| 상황 | 쿼리 1에서 일어나는 일 | 대응 (모두 구현됨) |
|---|---|---|
| 수만 건이 밀림 | 한 번에 수만 행을 읽고 보냄 | `LIMIT` 최대 500. RESUME은 `hasMore`로, REST는 마지막 seq를 `afterSeq`로 다시 요청해 나눠 받기 |
| 페이지가 깊어짐 | `OFFSET`을 썼다면 앞부분을 다 읽고 버려야 함 | `seq > ?` 커서라 인덱스에서 바로 그 위치로 감. 몇 번째 페이지든 비용이 같다 |
| 복원할 이벤트가 매우 많음 | 리플레이가 `seq 1 ~ 목표 seq`를 전부 읽음 | 스냅샷부터 읽어 범위를 최대 99건으로 줄임 ([6 문서](6-state-restoration.md#3-복원-알고리즘-스냅샷--리플레이)) |

---

### 쿼리 2. 복원 시각 → seq 변환 (`GET /timeline?at=`)

코드: `SessionEventRepository.findTopBySessionIdAndServerTsLessThanEqualOrderBySeqDesc`

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

**쿼리 2가 느려지는 경우**: 이벤트가 100만 건인 세션에서 최근 시각으로 복원하면 거의 100만 건을 정렬한다. 비용이 "세션의 이벤트 수"에 비례한다.

**개선안 (미적용, server_ts 단조 보정과 함께 적용 예정)**

| 방안 | 내용 | 전제 |
|---|---|---|
| A. 정렬 기준을 인덱스에 맞춤 | `ORDER BY server_ts DESC, seq DESC LIMIT 1` + 인덱스 `(session_id, server_ts, seq)` → 인덱스를 거꾸로 읽어 **1건만 읽고 종료** | 세션 내에서 `server_ts`가 seq와 같은 방향으로 증가해야 결과가 같다 → **server_ts 단조 보정**(새 이벤트의 시각이 직전보다 작으면 직전 값으로 맞춤) 필요 |

현재는 1:1 세션의 이벤트 수가 크지 않고 `atSeq`(쿼리 1과 같은 인덱스 경로)도 제공하므로 우선순위를 낮췄다. 단조 보정 없이 A를 적용하면 서버 간 시계 차이로 "seq는 더 큰데 server_ts는 더 작은" 이벤트가 생길 때 잘못된 seq를 고를 수 있어, **정합성 우선으로 현재 쿼리를 유지**했다.

---

### 쿼리 3. 사용자 세션 목록 (`GET /sessions?participant=&status=&from=&to=`)

코드: `ChatSessionRepository.findByParticipant`

1:1 세션에서 사용자는 `participant_a` 또는 `participant_b` 중 어느 쪽에도 있을 수 있다. 이 "OR 문제"를 참여자 테이블로 해결했는지 두 방식을 비교했다.

#### 채택: 참여자 테이블 경유

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

#### 비교: 참여자 테이블 없이 OR 조건

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

#### 결과

| | 참여자 테이블 (채택) | OR 조건 |
|---|---|---|
| 읽은 행 | **200** (그 사용자의 세션만) | **100,012** (전체) |
| 실행 시간 | **1.27 ms** | 18.9 ms (약 15배) |
| 비용 증가 요인 | 사용자 1명의 세션 수 | **전체 세션 수** |

**해석**
- `idx_participant_user (user_id, session_id)`는 **커버링 인덱스**(`Using index`)다. 필요한 `session_id`가 인덱스 안에 있어 참여자 테이블 본문을 읽지 않는다.
- 이어서 세션당 PK 1건 조회(`eq_ref`)로 세션 행을 가져온다.
- OR 방식은 지금도 15배 느리고, 세션이 1,000만 개가 되면 비용도 100배가 된다. 참여자 테이블 방식은 **전체 데이터 양과 무관하게 사용자 1명의 세션 수에만 비례**한다.
- 이것이 참여자 테이블을 따로 둔 근거다. `participant_a`, `participant_b`에 인덱스를 각각 거는 방법(index merge union)도 있지만, 본 테이블에 인덱스가 2개 늘고 정렬 문제는 그대로 남는다.

#### 쿼리 3이 느려지는 경우: 정렬 (`Using temporary; Using filesort`)

`started_at`은 `chat_session`에 있으므로, 그 사용자의 세션을 **모두 조인한 뒤 정렬**해야 상위 20개를 알 수 있다. 1인당 200개에서는 1ms지만, 세션이 10만 개인 사용자(봇, 상담원 등)는 매 요청마다 10만 건을 정렬한다.

**개선안 (확장 시)**

| 방안 | 내용 | 효과 |
|---|---|---|
| A. 정렬 키 비정규화 | `session_participant`에 `started_at`, `status` 복제 + 인덱스 `(user_id, started_at)` | 인덱스 순서대로 읽고 20건에서 멈춤. 정렬 없음. 메신저의 "사용자별 받은 편지함(inbox)" 테이블과 같은 구조 |
| B. 커서 페이지네이션 | `started_at < :마지막으로 본 값` 조건으로 다음 페이지 | `OFFSET`은 깊은 페이지일수록 앞부분을 모두 읽고 버리지만, 커서는 페이지 깊이와 무관 |

A는 참여자 테이블이 이미 **이벤트에서 파생된 프로젝션**이라는 점에서 자연스러운 확장이다. 새 컬럼도 `SESSION_STARTED`/`SESSION_ENDED` 이벤트로 갱신하고, 이벤트 리플레이로 재생성할 수 있다. 현재 범위(1:1 채팅, 사용자당 세션 수가 많지 않음)에서는 1ms 수준이라 적용하지 않았다.

---
