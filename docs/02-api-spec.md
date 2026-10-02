# 2. API 명세

> OpenAPI(Swagger UI)는 springdoc 적용 후 `/swagger-ui.html`에서 제공 예정. 이 문서는 사람이 읽기 위한 요약이다.

## 2.1 REST API

| 메서드 | 경로 | 설명 | 상태 |
|---|---|---|---|
| POST | `/sessions` | 세션 생성 (`SESSION_STARTED` 이벤트 기록, 참여자 2명 행 생성) | ✅ |
| GET | `/sessions/{id}` | 세션 현재 상태 | ✅ |
| POST | `/sessions/{id}/join` | 입장 (`JOINED`) | ✅ |
| POST | `/sessions/{id}/leave` | 퇴장 (`LEFT`) | ✅ |
| POST | `/sessions/{id}/end` | 종료 (`SESSION_ENDED`, status → COMPLETED) | ✅ |
| POST | `/sessions/{id}/events` | 이벤트/메시지 수집 (멱등) | ✅ |
| GET | `/sessions/{id}/events?afterSeq=&size=` | 이벤트 증분 조회 (재연결 동기화·디버깅) | ✅ |
| GET | `/sessions/{id}/timeline?at=` / `?atSeq=` | 특정 시점 상태 복원 (이벤트 리플레이) | ✅ |
| GET | `/sessions?status=&participant=&from=&to=` | 세션 목록 | 예정 |
| POST | `/sessions/{id}/snapshots` | 스냅샷 수동 생성 | 예정 (선택) |

join / leave / end는 전용 엔드포인트지만 내부적으로 **`POST /events`와 같은 `EventService.append()`**를 호출한다. 따라서 락·멱등·순서 규칙이 동일하게 적용된다.

## 2.2 요청 / 응답

### 세션 생성 `POST /sessions`
```json
// 요청
{ "participantA": "alice", "participantB": "bob" }
// 201
{ "sessionId": "1fa0…", "participantA": "alice", "participantB": "bob",
  "status": "ACTIVE", "lastSeq": 1, "startedAt": "…", "endedAt": null }
```

### 입장 / 퇴장 / 종료 `POST /sessions/{id}/join|leave|end`
```json
// 요청
{ "userId": "alice", "clientEventId": "a-join" }
```
응답은 아래 이벤트 수집과 같다.

### 이벤트 수집 `POST /sessions/{id}/events`
```json
// 요청
{
  "clientEventId": "a-msg-1",
  "type": "MESSAGE_SENT",
  "userId": "alice",
  "payload": { "text": "안녕" },
  "clientTs": "2026-09-30T10:00:00+09:00"
}
// 201 Created (신규 저장)
{
  "eventId": 4, "sessionId": "1fa0…", "seq": 4, "clientEventId": "a-msg-1",
  "type": "MESSAGE_SENT", "userId": "alice", "payload": { "text": "안녕" },
  "clientTs": "2026-09-30T01:00:00", "serverTs": "2026-09-30T01:00:00.123456"
}
```

| 상황 | 상태 코드 | 비고 |
|---|---|---|
| 신규 저장 | `201 Created` | |
| 같은 `clientEventId` 재전송, 내용 동일 | `200 OK` + `Idempotent-Replayed: true` | 본문은 **최초 저장 결과와 동일** (seq, serverTs 포함) |
| 같은 `clientEventId`, 내용 다름 | `409 CONFLICT` | 클라이언트 버그 |
| 종료된 세션 | `409 CONFLICT` | |
| `MESSAGE_SENT`인데 JOINED 상태가 아님 | `409 CONFLICT` | |
| 참여자가 아님 / 서버 전용 타입 | `400 BAD_REQUEST` | |
| 필수값 누락 / JSON 오류 | `400 INVALID_INPUT` / `MALFORMED_REQUEST` | |
| 세션 없음 | `404 NOT_FOUND` | |
| 락 대기 초과·데드락 등 일시적 실패 | `503 TRY_AGAIN` + `Retry-After: 1` | 같은 `clientEventId`로 재시도하면 안전 |

> 시각은 모두 UTC로 저장·응답한다. (응답 형식에 `Z` 표기를 추가하는 작업은 예정)

### 복원 `GET /sessions/{id}/timeline`

| 파라미터 | 의미 |
|---|---|
| `atSeq` | 이 seq까지 적용한 상태 |
| `at` | ISO-8601 시각, **오프셋 필수** (`2026-10-02T01:57:00Z`). 이 시각 이전 마지막 이벤트까지 적용 |
| (없음) | 현재 상태 |

```json
// GET /sessions/{id}/timeline?atSeq=3   → 200
{
  "sessionId": "3ef1…",
  "restoredAtSeq": 3,
  "lastEventAt": "2026-10-02T01:57:00.317155",
  "status": "ACTIVE",
  "participants": [
    { "userId": "alice", "state": "JOINED", "presence": "ONLINE" },
    { "userId": "bob",   "state": "LEFT",   "presence": "OFFLINE" }
  ],
  "messages": [
    { "messageId": "m-1", "seq": 3, "senderId": "alice", "text": "hi", "sentAt": "2026-10-02T01:57:00.317155" }
  ]
}
```

| 상황 | 상태 코드 |
|---|---|
| 세션 시작 이전 시점 | 200, `restoredAtSeq: 0`, `status: null`, 빈 목록 |
| `atSeq`와 `at` 동시 지정, `atSeq < 0`, 시각 형식 오류 | 400 |
| 세션 없음 | 404 |

> `at`에 `+09:00`을 쓸 때는 URL에서 `+`가 공백으로 해석되므로 `%2B09:00`으로 인코딩해야 한다.

### 이벤트 조회 `GET /sessions/{id}/events?afterSeq=3&size=100`
seq 오름차순 배열. `size` 최대 500.

### 공통 에러 형식
```json
{ "code": "CONFLICT", "message": "종료된 세션입니다: 1fa0…" }
```

## 2.3 WebSocket

### 연결
```
ws://localhost:8080/ws?sessionId={sessionId}&userId={userId}[&lastSeq={n}]
```
- 발신자는 **연결 시점의 userId**로 식별한다. 메시지 본문의 userId는 받지 않는다.
- `lastSeq`: 재연결 시 마지막으로 받은 seq. 이후 이벤트를 RESUME으로 받는다 (생략 시 0)

### 클라이언트 → 서버
```json
{ "clientEventId": "a2", "type": "MESSAGE_SENT", "payload": { "text": "안녕" }, "clientTs": "…" }
```
허용 타입: `MESSAGE_SENT`, `JOINED`, `LEFT` (서버 전용 이벤트는 거부)

### 서버 → 클라이언트 (봉투 형식)
```json
{ "kind": "ACK",    "data": { "clientEventId": "a2", "seq": 4, "serverTs": "…", "duplicate": false } }
{ "kind": "EVENT",  "data": { /* 이벤트 수집 응답과 같은 형식 */ } }
{ "kind": "ERROR",  "data": { "clientEventId": "a2", "code": "CONFLICT", "message": "…" } }
{ "kind": "RESUME", "data": { "events": [ … ], "hasMore": false } }
```

| kind | 받는 사람 | 시점 |
|---|---|---|
| `ACK` | 보낸 사람 | 저장(또는 중복 확인) 완료 후 |
| `EVENT` | 보낸 사람을 **제외한** 세션의 연결 | 트랜잭션 **커밋 후** |
| `ERROR` | 보낸 사람 | 검증 실패 등 |
| `RESUME` | 재연결한 사람 | 연결 직후, `lastSeq` 이후 이벤트 (최대 500건) |

재전송(`duplicate: true`)은 새 이벤트가 저장되지 않으므로 상대에게 `EVENT`가 가지 않는다.

### 재현 방법 (websocat)
```bash
websocat "ws://localhost:8080/ws?sessionId=$SID&userId=alice"
{"clientEventId":"a1","type":"JOINED"}
{"clientEventId":"a2","type":"MESSAGE_SENT","payload":{"text":"안녕 bob"}}
```
