# 1:1 실시간 채팅 서비스 (Event Sourcing 기반 상태 복원)

1:1 참여자 간 실시간 채팅 서비스입니다. 대화 중 일어난 모든 일(입장, 메시지, 퇴장, 연결 끊김 등)을 **이벤트로 저장(append-only)**하고, 이벤트를 다시 적용해 **특정 시점의 대화 상태를 복원**합니다.

## 기술 스택과 선택 근거

| 구분 | 선택 | 근거 |
|---|---|---|
| 언어/프레임워크 | Java 21, Spring Boot 4 | LTS, WebSocket·JPA·검증·Actuator 기본 제공 |
| DB | MySQL 8.4 (InnoDB) | 행 단위 비관적 락(`FOR UPDATE`), UNIQUE 제약, JSON 타입으로 **순서·중복 보장을 DB 수준에서 강제** |
| ORM | Spring Data JPA (Hibernate) | 도메인 규칙을 엔티티 메서드로 캡슐화, 변경 감지 |
| 스키마 관리 | Flyway | 스키마를 코드와 함께 버전 관리. JPA는 `ddl-auto: validate`로 엔티티-스키마 불일치를 시작 시점에 검출 |
| 실시간 통신 | Spring WebSocket (순수 WebSocket + JSON) | ACK·재전송·재연결 동기화 프로토콜을 직접 설계하기 위해 STOMP 대신 선택 ([비교](docs/07-communication.md)) |

## 실행 방법

```bash
docker compose up -d                  # MySQL 8.4
./gradlew bootRun                     # 앱 실행 (Flyway가 테이블 생성)
curl localhost:8080/actuator/health   # {"status":"UP"}
```

### 빠른 동작 확인

```bash
# 세션 생성
curl -s -X POST localhost:8080/sessions -H "Content-Type: application/json" \
  -d '{"participantA":"alice","participantB":"bob"}'
SID="응답의_sessionId"

# 입장 → 메시지
curl -s -X POST localhost:8080/sessions/$SID/join -H "Content-Type: application/json" \
  -d '{"userId":"alice","clientEventId":"a-join"}'
curl -s -X POST localhost:8080/sessions/$SID/events -H "Content-Type: application/json" \
  -d '{"clientEventId":"a-msg-1","type":"MESSAGE_SENT","userId":"alice","payload":{"text":"안녕"}}'

# 실시간 (websocat 필요: brew install websocat)
websocat "ws://localhost:8080/ws?sessionId=$SID&userId=bob"
```

### 동시성·중복 재현 스크립트

```bash
./scripts/concurrency-test.sh      # 서로 다른 이벤트 20개 동시 전송 + 같은 이벤트 20번 동시 재전송
```
결과와 해석은 [순서·중복 처리 문서](docs/03-ordering-and-idempotency.md#36-검증-결과)에 있습니다.

## 주요 의사결정 요약

| 주제 | 결정 | 이유 |
|---|---|---|
| 순서 기준 | 세션별 **서버 발급 seq** | 클라이언트 시계는 신뢰할 수 없음. 한 곳에서 발급해야 복원 결과가 결정적 |
| seq 동시성 | 세션 행 **비관적 락** | 1:1이라 세션당 경합이 작고, 재시도 로직이 필요 없음. 락 없이는 데드락·seq 충돌 재현됨 |
| 중복 처리 | `clientEventId` 멱등 키 + **최초 결과를 200으로 재응답** | 재전송은 "저장됐는지 묻는 것". 에러를 주면 클라이언트가 실패로 오해 |
| 처리 순서 | 락 → 중복 확인 → 검증 → 저장 | 동시 재전송 대응 + 상태가 바뀐 뒤의 재전송에도 같은 응답 |
| 실시간 전달 | 트랜잭션 **커밋 후** 상대에게 전송 | 롤백된 메시지가 상대에게 보이지 않음 |
| 전송 방식 | REST와 WebSocket이 같은 서비스 메서드 사용 | 입구와 무관하게 같은 규칙 적용 |
| 참여자 상태 | 이벤트에서 파생된 프로젝션 테이블 | 현재 상태를 리플레이 없이 조회. `last_applied_seq`로 멱등 반영 |

전체 목록: [설계 결정 목록](docs/00-decisions.md)

## 구현 범위

| 과제 요구사항 | 상태 | 비고 |
|---|---|---|
| 실시간 메시지 송수신 | ✅ | WebSocket, 커밋 후 상대 전달 |
| join / leave 처리 | ✅ | REST 전용 API + WebSocket |
| presence (online/offline) | ⏳ | 연결/끊김 이벤트 기록, 재연결 동기화 진행 중 |
| 이벤트·메시지 수집 API | ✅ | `POST /sessions/{id}/events` |
| 중복 이벤트 방지 | ✅ | 재현 스크립트로 검증 |
| 순서 뒤바뀜 처리 기준 | ✅ | 서버 seq, 재현 스크립트로 검증 |
| 특정 시점 상태 복원 | ⏳ | 설계 완료 ([문서](docs/04-state-restoration.md)) |

| 가산점 항목 | 상태 |
|---|---|
| 스냅샷 생성 자동화 | ⏳ |
| 프로젝션 비동기 파이프라인 | ⏳ 설계 ([문서](docs/06-operations.md#65-비동기-처리--설계)) |
| 부하 테스트 | ⏳ |
| 메트릭 대시보드 | ⏳ |
| 통신 방식 비교 | ✅ [문서](docs/07-communication.md) |
| 테스트 전략 (재현 스크립트, 통합 테스트) | 🟡 재현 스크립트 완료, 통합 테스트 예정 |

## 문서

| 문서 | 내용 |
|---|---|
| [00 설계 결정 목록](docs/00-decisions.md) | 결정, 대안, 상태 |
| [01 도메인·ERD·DDL](docs/01-domain-and-erd.md) | 테이블, 인덱스 근거, 정규화/JSON 트레이드오프 |
| [02 API 명세](docs/02-api-spec.md) | REST, WebSocket 프로토콜 |
| [03 순서·중복 처리](docs/03-ordering-and-idempotency.md) | seq 기준, 멱등 처리, **검증 결과** |
| [04 상태 복원](docs/04-state-restoration.md) | 스냅샷 + 리플레이, 결정성 |
| [05 쿼리 최적화](docs/05-query-optimization.md) | 핫패스 쿼리, 인덱스, 병목 |
| [06 운영 설계](docs/06-operations.md) | 실시간 전달, 재연결, 확장, 관측, 비동기, 장애 대응 |
| [07 통신 방식 비교](docs/07-communication.md) | WebSocket, SSE, STOMP, WebRTC |

## 프로젝트 구조

```
com.example.chat
├── session/    세션, 참여자 프로젝션, 세션 API (생성·입장·퇴장·종료)
├── event/      이벤트 저장소, 수집·조회 API, 멱등·순서 처리
├── realtime/   WebSocket 핸들러, 연결 목록, 커밋 후 전달
└── common/     예외 처리, 설정, JSON 변환
```
과제의 핵심 도메인(Session, Event, Snapshot)을 기준으로 패키지를 나눠, 기능 단위로 코드가 모이도록 했습니다.

## AI 활용 내역

초기 코드 골격, 설계 검토와 대안 비교, 개념 학습, 코드 리뷰, 문서 초안 작성에 AI(Claude)를 활용했습니다. 이후 기능 구현, 설계 결정, 실행 검증(재현 스크립트·시나리오 테스트)과 최종 검토는 직접 수행했습니다.
