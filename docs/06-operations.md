# 6. 운영 설계: 재연결, 확장, 관측, 비동기, 장애 대응

> 상태: **뼈대** — 구현하면서 채운다. 각 절의 방향만 먼저 적어 둔다.

## 6.1 재연결 시 정합성
- 클라이언트는 받은 마지막 seq(`lastSeq`)를 기억하고, 재연결 시 전달
- 서버는 `lastSeq` 이후 이벤트를 먼저 보내고(RESUME), 그다음 실시간 스트림 시작
- ACK 못 받은 전송 이벤트는 같은 `clientEventId`로 재전송 → 멱등 처리
- 끊김은 `DISCONNECTED`, 유예 시간 내 복귀는 `RECONNECTED` 이벤트로 기록. 유예 초과 시 세션 `INTERRUPTED`
- TODO: 시퀀스 다이어그램

## 6.2 서버 수평 확장
- TODO: 세션 라우팅(sessionId 기반 스티키 / 컨시스턴트 해싱), 인스턴스 간 브로드캐스트(Redis Pub/Sub), presence 저장소 Redis 이전, seq 발급은 DB 락이라 인스턴스 수와 무관

## 6.3 관측 가능성
- 로그: TODO (구조화 로그, `sessionId`, `seq`, `clientEventId`, `traceId`)
- 메트릭: TODO (append 지연, 중복 거절 수, 락 대기, WebSocket 연결 수, 복원 시간)
- 추적: TODO (traceId 전파)

## 6.4 비동기 처리
- TODO: 이벤트 커밋 → 프로젝션/스냅샷 갱신 흐름, 재시도(지수 백오프), DLQ, 멱등성(last_applied_seq, 스냅샷 PK), 중복 실행 방지, Outbox 패턴 검토

## 6.5 장애 대응 (감지 → 완화 → 복구)

### 서버 다운
- TODO

### DB 장애 / 성능 저하 (커넥션 고갈, 락 경합)
- TODO

### 데이터 유실 / 정합성 이슈 (중복 저장, 부분 실패)
- TODO
