# 6. 운영 설계: 실시간 전달, 재연결, 확장, 관측, 비동기, 장애 대응

> 각 절에 **구현 완료 / 설계(구현 예정)**를 표시한다.

## 6.1 실시간 전달 구조 — 구현 완료

```
alice ══WS══╗                                    ╔══WS══ bob
            ▼                                    ║
  ChatWebSocketHandler                           ║
    └ EventService.append()   @Transactional     ║
        └ saveEvent()                            ║
            ├ INSERT session_event               ║
            └ publishEvent(EventAppended)        ║   ← 커밋 후 실행 예약
        ── COMMIT ──                             ║
            └ EventBroadcaster.on()  @TransactionalEventListener(AFTER_COMMIT)
                └ WebSocketSessionRegistry.broadcast(보낸 사람 제외) ─┘ EVENT
    └ 보낸 사람에게 ACK
```

| 구성 요소 | 역할 |
|---|---|
| `ChatWebSocketHandler` | 연결/수신/종료 처리. 메시지를 `append()`로 넘기고 ACK·ERROR 응답 |
| `WebSocketSessionRegistry` | 세션별 연결 목록 (메모리, `ConcurrentHashMap`). 연결마다 `ConcurrentWebSocketSessionDecorator`로 감싸 **여러 스레드의 동시 전송을 직렬화** |
| `EventBroadcaster` | 커밋 후 이벤트를 받아 상대 연결로 전송 |

**설계 포인트**
- **커밋 후 전달**: 롤백된 이벤트가 상대 화면에 보이지 않는다. "상대가 본 메시지는 반드시 DB에 있다"가 보장된다.
- **입구와 무관**: 발행이 `saveEvent()` 안에 있어 REST로 들어온 이벤트(예: REST `/leave`)도 WebSocket 연결에 전달된다.
- **의존 방향**: `event` 패키지는 `realtime` 패키지를 모른다(Spring 이벤트로 느슨하게 연결).
- **클라이언트-서버 구조**: 사용자끼리 직접 연결하지 않고 서버가 중계한다. 순서(seq)·중복 제거·저장·복원이 모두 서버에서 일어나야 하기 때문 (P2P 비교는 [07-communication.md](07-communication.md)).

**한계**
| 한계 | 영향 | 보완 |
|---|---|---|
| 전송이 커밋 스레드에서 동기 실행 | 상대 전송이 느리면 보낸 사람의 ACK도 지연 | `@Async` 리스너로 분리 (6.5) |
| 커밋 후 전송 실패 시 재시도 없음 | 상대가 이벤트를 놓칠 수 있음 | **재연결 시 RESUME으로 보완** (6.2). 실시간 경로는 best-effort, 정합성은 RESUME이 책임 |
| 연결 목록이 서버 메모리 | 서버 2대 이상이면 상대가 다른 서버에 있을 때 전달 불가 | 서버 간 Pub/Sub (6.3) |

## 6.2 재연결 시 정합성 — 설계 (구현 진행 중)

```
bob 연결 끊김
  └ 서버: DISCONNECTED 기록 (presence → OFFLINE) → alice에게 EVENT
alice: 메시지 seq 6, 7 → bob은 연결이 없어 못 받음 (DB에는 저장)
bob 재연결: ws://…?sessionId=…&userId=bob&lastSeq=5
  ① 참여자 확인
  ② 연결 등록           ← 이후 실시간 EVENT 수신 시작
  ③ RECONNECTED 기록    (presence → ONLINE) → alice에게 EVENT
  ④ seq > 5 이벤트 조회 → RESUME [6, 7, 8] 전송
```

| 원칙 | 설명 |
|---|---|
| 등록 → 조회 순서 | 반대로 하면 조회와 등록 사이의 이벤트를 영영 놓친다. 이 순서면 **중복은 생겨도 누락은 없다** |
| at-least-once + seq 중복 제거 | 클라이언트는 받은 최대 seq를 기억하고 그 이하는 무시 |
| 보낸 쪽 재전송 | ACK를 못 받은 이벤트는 같은 `clientEventId`로 재전송 → 서버 멱등 처리로 한 번만 저장 |
| 대량 누락 | RESUME은 최대 500건. `hasMore=true`면 REST `GET /events?afterSeq=`로 페이지 조회 |
| presence 기록 조건 | JOINED 상태인 참여자의 연결 변화만 이벤트로 남긴다. 같은 사용자가 새 연결로 교체된 뒤 옛 연결이 끊긴 경우는 기록하지 않는다 |

## 6.3 서버 수평 확장 — 설계

| 대상 | 단일 서버 (현재) | 다중 서버 |
|---|---|---|
| seq 발급·중복 확인 | DB 행 락 | **그대로** (DB가 단일 기준이라 서버 수와 무관) |
| 연결 목록 | 서버 메모리 | 서버마다 자기 연결만 보유 |
| 실시간 전달 | 메모리에서 찾아 전송 | 커밋 후 **Redis Pub/Sub 채널 `session:{id}`**에 발행 → 모든 서버가 구독해 자기 연결에만 전송 |
| presence | 이벤트 + 참여자 테이블 | 동일. 연결 여부의 실시간 캐시가 필요하면 Redis에 TTL 키 |
| 세션 라우팅 | - | 로드밸런서에서 sessionId 기반 스티키 라우팅을 쓰면 Pub/Sub 트래픽이 줄어듦 (필수 아님) |

병목 예상: 한 세션의 쓰기는 행 락으로 직렬화되지만, 1:1 채팅은 세션당 쓰기 빈도가 낮아 문제가 적다. 전체 처리량은 세션 수에 따라 수평 확장되고, 결국 DB 쓰기가 한계가 된다 → 세션 id 해시 기반 샤딩, 오래된 이벤트 아카이빙.

## 6.4 관측 가능성 — 설계 (일부 구현)

| 구분 | 현재 | 계획 |
|---|---|---|
| 로그 | SQL 로그, WebSocket 연결/수신/종료/전송 로그 (`[전송] kind=…`) | 구조화 로그(JSON)에 `sessionId`, `seq`, `clientEventId`, `userId` 필드 고정 |
| 메트릭 | Actuator `health`, `metrics` | Micrometer 커스텀 메트릭: 이벤트 저장 수/지연, **중복 수신 수**, 락 대기 시간, 503 발생 수, WebSocket 연결 수, 전송 실패 수 → Prometheus + Grafana |
| 추적 | 없음 | traceId를 HTTP·WebSocket 요청마다 부여해 로그에 포함 (Micrometer Tracing) |

## 6.5 비동기 처리 — 설계

| 대상 | 현재 | 계획 |
|---|---|---|
| 참여자 프로젝션 | 이벤트와 같은 트랜잭션에서 동기 갱신 | 커밋 후 비동기 갱신 가능. `last_applied_seq`로 **중복·순서 역전 반영을 무시**하므로 재처리에 안전 |
| 스냅샷 생성 | 없음 | N개 이벤트마다 커밋 후 비동기 생성. PK `(session_id, seq)`로 중복 생성 방지 |
| 실시간 전달 | AFTER_COMMIT 동기 | `@Async`로 분리 |
| 재시도 / DLQ | 없음 | 비동기 작업 실패 시 지수 백오프 재시도(예: 1s, 2s, 4s, 최대 5회) → 실패 시 DLQ 테이블에 기록 후 운영자 재처리 |
| 유실 방지 | AFTER_COMMIT 이후 서버가 죽으면 비동기 작업 유실 | **Outbox 패턴**: 이벤트 INSERT와 같은 트랜잭션에 outbox 행 기록 → 별도 워커가 처리 후 완료 표시 |

## 6.6 장애 대응 (감지 → 완화 → 복구) — 초안

### 서버 다운
- 감지: 헬스체크 실패, WebSocket 연결 수 급감
- 완화: 로드밸런서가 다른 인스턴스로 전환. 클라이언트는 지수 백오프로 재연결
- 복구: 재연결 시 `lastSeq`로 RESUME → 누락 없음. 커밋 전이던 요청은 롤백되어 ACK가 없으므로 클라이언트가 같은 `clientEventId`로 재전송 → 멱등 처리. 서버가 죽어 기록되지 못한 `DISCONNECTED`는 재연결 시 presence 상태로 보정

### DB 장애 / 성능 저하 (커넥션 고갈, 락 경합)
- 감지: 커넥션 풀 대기 시간·고갈 메트릭, 락 대기·데드락 로그, 503 비율
- 완화: 락 대기 초과·데드락은 `503 TRY_AGAIN` + `Retry-After`로 응답(구현됨) → 클라이언트가 같은 `clientEventId`로 재시도해도 안전. 트랜잭션 범위 최소화, 커넥션 풀 타임아웃 설정
- 복구: 원인 쿼리 분석(`EXPLAIN`, slow query log), 인덱스 보강, 필요 시 읽기 전용 복제본으로 조회 분리

### 데이터 유실 / 정합성 이슈 (중복 저장, 부분 실패)
- 중복 저장: 코드(락 안 중복 확인) + DB UNIQUE 2개로 이중 방어. 재현 스크립트로 검증됨
- 부분 실패: 이벤트 INSERT·참여자 갱신·last_seq 갱신이 한 트랜잭션 → 전부 성공 또는 전부 롤백
- 프로젝션 불일치: 참여자 테이블은 이벤트에서 파생된 값이므로, 이벤트를 리플레이해 재생성 가능
- 감지: 주기적 정합성 점검 쿼리 (예: `last_seq`와 `MAX(seq)` 비교)
