# 4. 이벤트 기반 상태 복원

> 상태: **전체 리플레이 방식 구현·검증 완료** (D9). 스냅샷 + 리플레이(D10, D11)는 설계 단계

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

## 4.4 스냅샷 정책

- **주기**: 이벤트 100개마다 (seq % 100 == 0). 복원 시 리플레이 이벤트가 최대 99개로 제한됨
  - 주기가 짧으면 복원은 빠르지만 저장량·쓰기 부하 증가 → 부하 테스트 후 조정
- **생성 시점**: 이벤트 커밋 이후 비동기 (06 문서의 비동기 처리 참고). 이벤트 저장 응답 지연에 영향 없음
- **멱등성**: PK `(session_id, seq)` → 같은 스냅샷을 두 번 만들려 해도 한 번만 저장
- **포맷**: JSON + `schemaVersion`. 상태 구조가 바뀌면 버전이 다른 스냅샷은 무시하고 리플레이로 대체
- **메시지가 많을 때**: 스냅샷에 전체 메시지를 넣으면 커진다 → 최근 N개만 포함하는 방식 / 메시지 프로젝션 테이블 분리를 확장안으로 기록

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

## 4.7 남은 작업
- 스냅샷 + 리플레이 (4.2~4.4) 구현 후, 같은 targetSeq에 대해 전체 리플레이 결과와 동일한지 비교 테스트
- 메시지 수정/삭제 이벤트(`EDITED`/`DELETED`)는 현재 이벤트 타입에 없어 범위 외. 추가 시 `messageId`(= 최초 clientEventId) 기준으로 `apply`에 반영
