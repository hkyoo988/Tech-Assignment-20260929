# 4. 이벤트 기반 상태 복원

> 상태: **스냅샷 + 리플레이 구현·검증 완료** (D9~D11, D29~D31)

**왜 시점 복원이 필요한가**: 1:1 채팅은 상담·거래처럼 두 사람 사이에 약속이 오가는 경우가 많다. "그 메시지를 보낸 순간 누가 방에 있었고 상대가 접속해 있었는가"를 그대로 재현할 수 있어야 분쟁 조사, 고객 문의·장애 원인 분석, 신고 처리, 감사 대응이 가능하다. 현재 상태만 덮어쓰는 설계에서는 과거가 사라지지만, 이벤트를 모두 남기면 어느 시점이든 다시 만들 수 있다.

## 4.0 구현 요약

| 구성 요소 | 역할 |
|---|---|
| `SessionState` | 복원 결과를 담는 상태 객체. `apply(event)`가 이벤트 하나를 상태에 반영하는 **순수 함수** |
| `TimelineService.loadState(sessionId, targetSeq)` | `seq <= targetSeq` 이벤트를 seq 순으로 읽어 빈 상태부터 차례로 `apply` |
| `TimelineService.restore(sessionId, atSeq, at)` | 요청 파라미터를 `targetSeq` 하나로 변환한 뒤 `loadState` 호출 |
| `GET /sessions/{id}/timeline` | 복원 API ([02 API 명세](02-api-spec.md#복원-get-sessionsidtimeline)) |

**시점 지정 → targetSeq 변환**

| 요청 | targetSeq |
|---|---|
| `?atSeq=N` | N |
| `?at=시각` | `server_ts <= 시각`인 마지막 이벤트의 seq (없으면 0 → 빈 상태) |
| 파라미터 없음 | `Long.MAX_VALUE` → 모든 이벤트 적용 = 현재 상태 |
| 둘 다 지정 / `atSeq < 0` | 400 |

시각을 받더라도 **seq로 바꿔서 복원**한다. 같은 밀리초에 이벤트가 여럿이면 시각으로는 순서를 정할 수 없고, 순서의 기준은 seq 하나뿐이기 때문(D5).
`at`은 `OffsetDateTime`으로 받아 오프셋(`Z`, `+09:00`)이 반드시 있어야 하며, UTC로 변환해 `server_ts`(UTC 저장)와 비교한다.

**응답의 `restoredAtSeq`**: 실제로 적용된 마지막 seq. `atSeq=999`처럼 마지막 seq보다 큰 값을 요청하면 실제 마지막 seq가 내려가 "요청한 시점"과 "복원된 시점"을 구분할 수 있다.

## 4.1 복원 대상 (시점 t 기준)

- 세션 상태 (ACTIVE / INTERRUPTED / COMPLETED)
- 참여자 목록과 상태 (입장/퇴장, 온라인/오프라인)
- 메시지 목록 (최근 N개 옵션), 각 메시지의 상태 (SENT / EDITED / DELETED)
- (선택) 사용자별 unread count: 마지막으로 읽은 seq 이후 상대 메시지 수

## 4.2 복원 알고리즘: 스냅샷 + 리플레이

```
restore(sessionId, targetSeq):
    snapshot = 가장 최근 스냅샷 WHERE seq <= targetSeq     (없으면 빈 상태, seq=0)
    events   = 이벤트 WHERE snapshot.seq < seq <= targetSeq ORDER BY seq
    state    = snapshot.state
    for e in events:
        state = apply(state, e)      ← 순수 함수
    return state
```

`?at=시각`으로 요청하면 먼저 `server_ts <= at` 인 마지막 seq를 찾아 `targetSeq`로 쓴다.

### apply()의 규칙: 결정성(Determinism) 보장

같은 이벤트 목록이면 **언제, 몇 번, 어느 서버에서** 복원해도 결과가 같아야 한다.

| 규칙 | 이유 |
|---|---|
| 이벤트는 반드시 `seq` 오름차순으로 적용 | 순서 기준이 하나 |
| `apply()` 안에서 현재 시각·랜덤·DB·외부 호출 금지. 시각이 필요하면 이벤트의 `serverTs`만 사용 | 실행 환경에 따라 결과가 달라지지 않게 |
| `e.seq <= state.lastSeq`이면 건너뜀 | 중복 반영 방지 (프로젝션 재실행에도 안전) |
| 알 수 없는 이벤트 타입은 무시하고 기록 | 구버전 복원기가 신규 이벤트 때문에 깨지지 않게 |
| 컬렉션은 순서가 정해진 자료구조 사용 (예: `LinkedHashMap`, seq 순) | JSON 직렬화 결과까지 동일하게 |

### 검증 방법

- **동일성 테스트**: 같은 targetSeq에 대해 ① 전체 리플레이 결과 ② 스냅샷 + 리플레이 결과를 비교 → 완전히 같아야 함
- **반복 테스트**: 같은 요청 N회 → 결과 JSON 해시가 모두 같음

## 4.3 전체 리플레이 vs 스냅샷 + 리플레이

| | 전체 리플레이 | 스냅샷 + 리플레이 (채택) |
|---|---|---|
| 구현 | 가장 단순 | 스냅샷 생성·저장 로직 추가 |
| 비용 | 이벤트 수에 비례 O(N) | 최대 스냅샷 주기 K개만 리플레이 O(K) |
| 저장 공간 | 없음 | 스냅샷 수 × 상태 크기 |
| 정합성 위험 | 없음 | 스냅샷이 틀리면 이후 복원이 전부 틀림 → 동일성 테스트로 방어, 스냅샷은 언제든 재생성 가능 |

## 4.4 스냅샷 정책 — 구현 완료

**스냅샷의 역할**: 이벤트는 쌓이기만 하므로 "상태를 다시 만드는 비용"이 시간이 갈수록 커진다. 스냅샷은 이 비용에 **상한**을 두는 캐시다. 데이터를 잃지 않게 하는 것은 이벤트 저장소이고, 스냅샷은 잃지 않은 데이터로 **상태를 빨리 다시 만드는 것**을 책임진다.

| 상태를 다시 만드는 상황 | 스냅샷 효과 | 현재 |
|---|---|---|
| 과거 시점 복원 (`GET /timeline`) | 처음부터 → 최대 99개만 리플레이 | ✅ 적용 |
| 상태 테이블(프로젝션) 손상 시 재생성 | 복구 시간 단축 | 설계 |
| 오래 끊긴 클라이언트·새 기기 동기화 | 이벤트 수천 개 RESUME 대신 스냅샷 1개 + 이후 이벤트 | 확장안 |

| 결정 | 선택 | 근거 |
|---|---|---|
| **주기** (D29) | 이벤트 **100개**마다 (`chat.snapshot.interval`) + **세션 종료** 시점 | 아래 상세 |
| **생성 시점** (D30) | 이벤트 커밋 후 **비동기** (`@Async` + `@TransactionalEventListener(AFTER_COMMIT)`) | 스냅샷 생성이 메시지 응답 지연·락 보유 시간에 영향을 주면 안 된다 |
| **내용** (D31) | 메시지를 포함한 **전체 상태** (`SessionState.Snapshot` record → JSON) | 복원 결과에 메시지가 포함되므로 스냅샷만으로 해당 시점을 완전히 재현해야 한다 |
| **실패 처리** (D30) | 생성 실패·읽기 실패 모두 **로그만 남기고 리플레이로 대체** | 스냅샷은 속도를 위한 캐시일 뿐, 정답의 근거는 항상 이벤트다 |
| **멱등성** | PK `(session_id, seq)` + `INSERT IGNORE` | 같은 스냅샷이 두 번 만들어지려 해도 1건만 저장 |
| **증분 생성** | seq 200 스냅샷 = seq 100 스냅샷 + 이벤트 100개 | 생성 비용도 주기에 비례하는 상수로 유지 |

**주기 100의 근거 (D29)**
1. 복원 비용을 "스냅샷 1건 + 이벤트 최대 99건 조회"로 묶는다. `apply()`는 메모리 연산이라 99회도 1ms 미만이므로 주기를 더 줄여도 이득이 작다.
2. 스냅샷에 메시지 전체가 들어가므로 주기가 짧으면 저장량이 급증한다. (이벤트 1만 개 세션 기준 메시지 복사본: 주기 10 → 약 500만, 주기 100 → 약 50만)
3. 1:1 채팅은 짧은 세션이 대부분이라, 주기 100이면 **긴 세션에만** 스냅샷이 생긴다. 짧은 세션은 전체 리플레이로도 충분히 빠르다.
4. 입장·퇴장 같은 이벤트 종류만으로 시점을 정하면 대화만 길게 이어지는 세션의 비용을 막지 못하므로 주기를 기본으로 하고, **세션 종료**는 최종 상태 보존(향후 이벤트 아카이빙의 기준점) 용도로 추가했다.
5. 설정값으로 분리했으므로 실제 트래픽에서 복원 시간과 저장량을 측정해 조정한다.

**구성 요소**

| 클래스 | 역할 |
|---|---|
| `SnapshotService` | `EventAppended`를 비동기로 구독, 주기/종료 시점이면 `loadState`로 상태 계산 후 저장 |
| `SnapshotRepository` | JdbcTemplate. `insertIgnore`, `findLatest(sessionId, maxSeq)` (PK 역방향 1건) |
| `TimelineService.loadState(sessionId, targetSeq, useSnapshot)` | 최신 스냅샷 + `seq > 스냅샷 seq AND seq <= targetSeq` 이벤트만 리플레이. `useSnapshot=false`는 검증용 전체 리플레이 |

`EventService`는 스냅샷의 존재를 모른다. 실시간 전송(`EventBroadcaster`)과 스냅샷 생성(`SnapshotService`)이 **같은 이벤트를 각자 구독**하는 구조라, 쓰기 경로를 건드리지 않고 파생 작업을 추가할 수 있다.

**확장 시 고려**
- 상태 구조 변경: 현재는 읽기 실패 시 리플레이로 대체한다. 변경이 잦아지면 `schemaVersion`을 넣어 버전이 다른 스냅샷을 명시적으로 무시한다.
- 메시지가 매우 많은 세션: 스냅샷에 최근 N개만 넣고 나머지는 메시지 프로젝션 테이블로 분리한다.

## 4.5 복원 비용 추정 (구현 후 측정값으로 교체)

| 항목 | 쿼리 | 비용 |
|---|---|---|
| 시각 → seq | `(session_id, server_ts)` 인덱스 1건 | O(log N) |
| 스냅샷 조회 | PK 역방향 1건 | O(log S) |
| 리플레이 이벤트 | `(session_id, seq)` 범위 스캔 최대 99건 | O(K) |

## 4.6 검증 결과

시나리오: 세션 생성(seq 1) → alice 입장(seq 2) → alice 메시지 "hi"(seq 3)

| 요청 | restoredAtSeq | status | alice | bob | messages |
|---|---|---|---|---|---|
| `?atSeq=0` | 0 | null | - | - | 0건 |
| `?atSeq=1` | 1 | ACTIVE | LEFT / OFFLINE | LEFT / OFFLINE | 0건 |
| `?atSeq=2` | 2 | ACTIVE | JOINED / ONLINE | LEFT / OFFLINE | 0건 |
| 파라미터 없음 | 3 | ACTIVE | JOINED / ONLINE | LEFT / OFFLINE | 1건 (`messageId=m-1`) |
| `?at=2020-01-01T00:00:00Z` (세션 이전) | 0 | null | - | - | 0건 |
| `?atSeq=1&at=…` | 400 `atSeq와 at은 동시에 지정할 수 없습니다` |||||
| 없는 세션 | 404 |||||

- **결정성**: 같은 `atSeq=2` 요청 2회 응답을 `diff` → 차이 없음
- **프로젝션 교차 검증**: 현재 시점 복원 결과와 `session_participant` 테이블이 일치
  (`alice JOINED ONLINE last_applied_seq=2`, `bob LEFT OFFLINE last_applied_seq=0`)
  → 프로젝션 테이블은 이벤트만으로 재생성 가능한 파생 데이터임을 확인

**자동화**: `EventSourcingIntegrationTest`의 `restoreIsDeterministic`(seq 0~6 전 구간 2회 복원 비교), `projectionMatchesReplay`(참여자 테이블 = 리플레이 결과)로 위 검증을 `./gradlew test`에 포함했다.

### 스냅샷 검증 (`SnapshotIntegrationTest`, 주기 3으로 축소)

| 확인 항목 | 결과 |
|---|---|
| 스냅샷 생성 시점 | seq 3, 6, 9, 12 (주기마다) ✅ |
| 비동기 실행 | 이벤트 저장은 요청 스레드, 스냅샷은 `task-N` 스레드에서 실행 (로그) ✅ |
| 증분 생성 | seq 6 스냅샷 생성 시 조회 조건 `seq > 3 AND seq <= 6` (이전 스냅샷 재사용) ✅ |
| 복원 시 사용 | `atSeq=8` → `seq > 6 AND seq <= 8` (스냅샷 6 + 이벤트 2개) ✅ |
| **스냅샷 기반 복원 = 전체 리플레이** | seq 0~13 모든 시점에서 동일 ✅ |

## 4.7 남은 작업
- 메시지 수정/삭제 이벤트(`EDITED`/`DELETED`)는 현재 이벤트 타입에 없어 범위 외. 추가 시 `messageId`(= 최초 clientEventId) 기준으로 `apply`에 반영
