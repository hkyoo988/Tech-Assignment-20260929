# 2. API 명세

> 상태: **초안**. 구현 후 springdoc-openapi로 Swagger UI(`/swagger-ui.html`)와 `openapi.yaml`을 함께 제공한다.
> 경로는 결정 D1에 따라 `/sessions` 기준.

## 2.1 REST API

| 메서드 | 경로 | 설명 | 상태 |
|---|---|---|---|
| POST | `/sessions` | 세션 생성 (`SESSION_STARTED` 이벤트 기록) | 구현(경로 변경 예정) |
| GET | `/sessions` | 세션 목록. 필터: `status`, `participant`, `from`, `to`, 커서 페이지네이션 | 예정 |
| GET | `/sessions/{id}` | 세션 현재 상태 | 구현 |
| POST | `/sessions/{id}/join` | 참여 (`JOINED` 이벤트) | 예정 |
| POST | `/sessions/{id}/leave` | 퇴장 (`LEFT` 이벤트) | 예정 |
| POST | `/sessions/{id}/end` | 종료 (`SESSION_ENDED` 이벤트, 상태 COMPLETED) | 예정 |
| POST | `/sessions/{id}/events` | 이벤트/메시지 수집 (멱등) | 구현(멱등 처리 예정) |
| GET | `/sessions/{id}/events?afterSeq=&size=` | 재연결 resume용 증분 조회 | 구현 |
| GET | `/sessions/{id}/events?from=&to=` | 디버깅/검증용 seq 범위 조회 | 예정 |
| GET | `/sessions/{id}/timeline?at=` 또는 `?atSeq=` | 특정 시점 상태 복원 | 예정 |
| POST | `/sessions/{id}/snapshots` | 스냅샷 수동 생성 (선택) | 예정 |

## 2.2 주요 요청/응답 예시

### 이벤트 수집 `POST /sessions/{id}/events`

요청
```json
{
  "clientEventId": "0b6f7c1e-...",
  "type": "MESSAGE_SENT",
  "userId": "alice",
  "payload": { "text": "안녕" },
  "clientTs": "2026-09-29T10:00:00+09:00"
}
```

응답
| 상황 | 상태 코드 | 본문 |
|---|---|---|
| 신규 저장 | `201 Created` | 저장된 이벤트 (`seq`, `serverTs` 포함) |
| 같은 `clientEventId` 재전송, 내용 동일 | `200 OK` + 헤더 `Idempotent-Replayed: true` | **최초 저장 결과 그대로** |
| 같은 `clientEventId`인데 내용이 다름 | `409 Conflict` | 에러 |
| 종료된 세션 | `409 Conflict` | 에러 |
| 참여자가 아님 / 잘못된 타입 | `400 Bad Request` | 에러 |

### 상태 복원 `GET /sessions/{id}/timeline?at=2026-09-29T01:00:00Z`

```json
{
  "sessionId": "…",
  "restoredAtSeq": 42,
  "restoredAtTime": "2026-09-29T00:59:58.120Z",
  "status": "ACTIVE",
  "participants": [
    { "userId": "alice", "state": "JOINED", "presence": "ONLINE" },
    { "userId": "bob",   "state": "JOINED", "presence": "OFFLINE" }
  ],
  "messages": [
    { "messageId": "…", "seq": 12, "senderId": "alice", "text": "안녕(수정됨)", "status": "EDITED" },
    { "messageId": "…", "seq": 15, "senderId": "bob",   "text": null,           "status": "DELETED" }
  ],
  "source": { "snapshotSeq": 0, "replayedEvents": 42 }
}
```
`source`는 복원에 스냅샷을 썼는지, 이벤트를 몇 개 리플레이했는지 보여준다 (성능 검증·디버깅용).

## 2.3 공통 에러 형식

```json
{ "code": "NOT_FOUND", "message": "세션이 없습니다: …" }
```

## 2.4 WebSocket

- 엔드포인트: `ws://localhost:8080/ws?sessionId=…&userId=…&lastSeq=…`
- 클라이언트 → 서버: `{ "clientEventId", "type", "payload", "clientTs" }`
- 서버 → 클라이언트:
  - `ACK` `{ clientEventId, seq, serverTs, duplicate }`
  - `EVENT` (상대방 이벤트 브로드캐스트)
  - `RESUME` (재연결 시 `lastSeq` 이후 누락 이벤트)
- 상세는 [06-operations.md](06-operations.md)의 재연결 절 참고
