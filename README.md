# 1:1 실시간 채팅 서비스 (Event Sourcing 기반 상태 복원)

1:1 참여자 간 실시간 채팅 서비스입니다. 대화 중 일어난 모든 일(입장, 메시지, 퇴장, 연결 끊김 등)을 **이벤트로 저장(append-only)**하고, 이벤트를 다시 적용해 **특정 시점의 대화 상태를 복원**합니다.

1:1 대화는 상담, 거래처럼 두 사람 사이에 약속이 오가는 경우가 많아, "그 순간 누가 방에 있었고 무슨 말이 오갔는가"를 그대로 재현할 수 있어야 분쟁 조사, 장애 분석, 신고 처리가 가능합니다. 현재 상태만 덮어쓰면 과거가 사라지므로, 모든 변화를 이벤트로 남기는 구조를 택했습니다.

## 제출물 체크리스트

| # | 항목 | 위치 |
|---|---|---|
| 1 | README: 실행 방법, 환경 구성, 주요 의사결정 요약 | 이 문서: [기술 스택](#기술-스택과-선택-근거), [실행 방법](#실행-방법), [주요 의사결정](#주요-의사결정-요약) |
| 2 | API 명세 (OpenAPI) | [docs/2-api-spec.md](docs/2-api-spec.md), [docs/openapi.yaml](docs/openapi.yaml) |
| 3 | ERD + 핵심 DDL | [docs/3-erd-ddl.md](docs/3-erd-ddl.md) |
| 4 | 주요 쿼리 2~3개 + 인덱스 근거 + 병목 설명 | [docs/4-queries.md](docs/4-queries.md) |
| 5 | 설계 문서: 재연결, 중복 처리, 확장성, 관측 가능성, 장애 대응 | [docs/5-design.md](docs/5-design.md) |
| 6 | 이벤트 기반 상태 복원 설계 또는 구현 결과 | [docs/6-state-restoration.md](docs/6-state-restoration.md) |
| 7 | (선택) Snapshot/Projection 고도화 구현 | ✅ [docs/7-optional.md](docs/7-optional.md) |
| 8 | (선택) 부하 테스트 결과, 대시보드, 추가 통신 방식 검토 | [docs/7-optional.md](docs/7-optional.md) |

## 필수 구현 (과제 4.1)

| # | 요구사항 | 구현 | 설명 | 검증 |
|---|---|---|---|---|
| 1 | 실시간 메시지 송수신 | WebSocket(`/ws`), 이벤트 **커밋 후** 상대 연결로 전송 | [5 §1](docs/5-design.md#1-실시간-메시지-전달-구조--구현-완료) | [5 §9](docs/5-design.md#9-검증-결과-순서-중복-동시성) |
| 2 | join / leave | `POST /sessions/{id}/join`, `/leave` + WebSocket `JOINED`/`LEFT`, 같은 `append()` 경로 | [5 §2](docs/5-design.md#2-join--leave-presence-이벤트-수집-api--구현-완료) | [5 §9](docs/5-design.md#9-검증-결과-순서-중복-동시성) |
| 3 | presence | 입장 시 ONLINE, 퇴장 시 OFFLINE, 참여 중 끊김/재연결을 `DISCONNECTED`/`RECONNECTED` 이벤트로 기록 | [5 §2](docs/5-design.md#2-join--leave-presence-이벤트-수집-api--구현-완료), [§4](docs/5-design.md#4-재연결-시-정합성--구현-완료) | [6 §7](docs/6-state-restoration.md#7-검증-결과) (끊김/재연결은 수동 확인) |
| 4 | 이벤트, 메시지 수집 API | `POST /sessions/{id}/events` (멱등), `GET …/events?afterSeq=` | [2 API](docs/2-api-spec.md) | [OpenAPI](docs/openapi.yaml) |
| 5 | 중복 방지 | `clientEventId` 멱등 키 + 락 안 중복 확인 + UNIQUE 2개 | [5 §3](docs/5-design.md#3-순서-기준과-중복-처리--구현-완료) | [5 §9](docs/5-design.md#9-검증-결과-순서-중복-동시성) |
| 6 | 순서 뒤바뀜 처리 기준 | 세션별 **서버 발급 seq**, 세션 행 비관적 락 | [5 §3](docs/5-design.md#3-순서-기준과-중복-처리--구현-완료) | [5 §9](docs/5-design.md#9-검증-결과-순서-중복-동시성) |
| 7 | 특정 시점 상태 복원 | `GET /sessions/{id}/timeline?at=` / `?atSeq=` (스냅샷 + 리플레이) | [6 상태 복원](docs/6-state-restoration.md) | [6 §7](docs/6-state-restoration.md#7-검증-결과) |

## 기술 스택과 선택 근거

| 구분 | 선택 | 근거 |
|---|---|---|
| 언어/프레임워크 | Java 21, Spring Boot 4 | 가장 익숙한 스택을 골라 과제의 핵심(락, 트랜잭션, 커밋 후 이벤트)에 집중 |
| DB | MySQL 8.4 (InnoDB) | 필요한 것은 행 락(`FOR UPDATE`), UNIQUE, JSON이고 주요 RDBMS가 모두 제공한다. Oracle이 더 익숙했지만, 무료이고 가벼워 Docker, Testcontainers로 바로 띄울 수 있으며 크디랩 기술 스택에 포함되어 있어 MySQL을 택했다. |
| ORM | Spring Data JPA + 일부 JdbcTemplate | `last_seq` 증가는 엔티티 변경 감지로 커밋 시 UPDATE. 스냅샷 저장의 `INSERT IGNORE`처럼 JPA로 표현하기 어려운 곳은 JdbcTemplate |
| 스키마 관리 | Flyway | UNIQUE와 인덱스가 정합성의 핵심이라 JPA 자동 생성에 맡기지 않고 SQL로 직접 관리. `ddl-auto: validate`로 엔티티와의 불일치를 시작 시점에 검출 |
| 실시간 통신 | Spring WebSocket (순수 WebSocket + JSON) | 서버 한 대 범위에서는 순수 WebSocket으로 충분하고, 연결 시점(`lastSeq`를 받아 RESUME)과 종료 시점(presence 기록)을 핸들러에서 직접 다룰 수 있어 흐름이 단순함. 다중 서버로 확장할 때는 STOMP + 외부 브로커도 대안 ([비교](docs/7-optional.md#1-통신-방식-비교-webrtc-등)) |

## 실행 방법

**필요 환경**: JDK 21, Docker (MySQL 8.4 컨테이너 실행 및 테스트용 Testcontainers). 포트 8080(앱), 3306(MySQL)

```bash
docker compose up -d                  # MySQL 8.4
./gradlew bootRun                     # 앱 실행 (Flyway가 테이블 생성)
curl localhost:8080/actuator/health   # {"status":"UP"}
```

API 문서: Swagger UI `http://localhost:8080/swagger-ui.html` / OpenAPI 명세 파일 [`docs/openapi.yaml`](docs/openapi.yaml)

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

# 특정 시점 상태 복원
curl -s "localhost:8080/sessions/$SID/timeline?atSeq=2"
curl -s "localhost:8080/sessions/$SID/timeline"            # 현재 상태

# 실시간 (websocat 필요: brew install websocat)
websocat "ws://localhost:8080/ws?sessionId=$SID&userId=bob"
websocat "ws://localhost:8080/ws?sessionId=$SID&userId=bob&lastSeq=3"   # 재연결: seq 3 이후를 RESUME으로 수신
```

### 자동 테스트

```bash
./gradlew test        # Docker만 켜져 있으면 됨 (Testcontainers가 MySQL 8.4를 자동으로 띄움)
```

통합 테스트 9개(멱등, 동시성, 복원 결정성, 스냅샷 등)의 목록과 테스트 전략은 [7 §2 테스트 전략](docs/7-optional.md#2-테스트-전략)에 있습니다.

### 동시성, 중복 재현 스크립트

```bash
./scripts/concurrency-test.sh      # 서로 다른 이벤트 20개 동시 전송 + 같은 이벤트 20번 동시 재전송
```
결과와 해석은 [5 §9 검증 결과](docs/5-design.md#9-검증-결과-순서-중복-동시성)에 있습니다.

## 주요 의사결정 요약

| 주제 | 결정 | 이유 | 상세 |
|---|---|---|---|
| 순서 기준 | 세션별 **서버 발급 seq** | 클라이언트 시계는 신뢰할 수 없음. 한 곳에서 발급해야 복원 결과가 결정적 | [5 §3](docs/5-design.md#순서-기준-세션별-서버-seq) |
| seq 동시성 | 세션 행 **비관적 락** | 1:1이라 세션당 경합이 작고, 재시도 로직이 필요 없음. 락 없이는 데드락, seq 충돌 재현됨 | [5 §9](docs/5-design.md#9-검증-결과-순서-중복-동시성) |
| 중복 처리 | `clientEventId` 멱등 키 + **최초 결과를 200으로 재응답** | 재전송은 "저장됐는지 묻는 것". 에러를 주면 클라이언트가 실패로 오해 | [5 §3](docs/5-design.md#중복-처리-멱등-키) |
| 처리 순서 | 락 → 중복 확인 → 검증 → 저장 | 동시 재전송 대응 + 상태가 바뀐 뒤의 재전송에도 같은 응답 | [5 §3](docs/5-design.md#처리-순서와-그-이유-구현) |
| 실시간 전달 | 트랜잭션 **커밋 후** 상대에게 전송 | 롤백된 메시지가 상대에게 보이지 않음 | [5 §1](docs/5-design.md#1-실시간-메시지-전달-구조--구현-완료) |
| 전송 방식 | REST와 WebSocket이 같은 서비스 메서드 사용 | 입구와 무관하게 같은 규칙 적용 | [5 §2](docs/5-design.md#2-join--leave-presence-이벤트-수집-api--구현-완료) |
| 참여자 상태 | 이벤트에서 파생된 프로젝션 테이블 | 현재 상태를 리플레이 없이 조회. `last_applied_seq`로 멱등 반영 | [3 ERD, DDL](docs/3-erd-ddl.md#4-정규화--비정규화--json-선택과-트레이드오프) |
| 재연결 | 클라이언트가 `lastSeq`를 보내고, 서버는 연결 등록 **후** 그 이후 이벤트를 RESUME | 등록→조회 순서라 누락 없음(중복은 seq로 제거) | [5 §4](docs/5-design.md#4-재연결-시-정합성--구현-완료) |
| 시점 복원 | 시각도 seq로 변환한 뒤 seq 순 리플레이, `apply()`는 순수 함수 | 같은 입력이면 언제 복원해도 같은 결과 | [6 §3](docs/6-state-restoration.md#3-복원-알고리즘-스냅샷--리플레이) |
| 스냅샷 | 100개마다 + 세션 종료 시 커밋 후 비동기 생성, 실패해도 리플레이로 대체 | 복원 비용에 상한(최대 99개). 쓰기 경로에 영향 없음 | [6 §5](docs/6-state-restoration.md#5-스냅샷-정책--구현-완료) |

## 프로젝트 구조

```
com.example.chat
├── session/    세션, 참여자 프로젝션, 세션 API (생성, 입장, 퇴장, 종료)
├── event/      이벤트 저장소, 수집, 조회 API, 멱등, 순서 처리
├── realtime/   WebSocket 핸들러, 연결 목록, 커밋 후 전달, 재연결 RESUME
├── timeline/   시점 복원 (스냅샷 + 이벤트 리플레이) API, 스냅샷 비동기 생성
└── common/     예외 처리, 설정, JSON 변환
```
과제의 핵심 도메인(Session, Event, Snapshot)을 기준으로 패키지를 나눠, 기능 단위로 코드가 모이도록 했습니다.

## 고민한 점

### 1. 동시 전송에서 나온 500 에러: 데드락

처음에는 락 없이 `last_seq + 1`로 seq를 발급했습니다. 재현 스크립트로 서로 다른 이벤트 20개를 동시에 보내 보니 201은 2건뿐이고 409와 500이 섞여 나왔습니다. 500의 원인을 따라가 보니 데드락이었습니다. 이벤트를 INSERT할 때 FK 확인 때문에 세션 행에 공유 락이 걸리고, 이어서 `last_seq`를 UPDATE하려면 배타 락이 필요해 두 트랜잭션이 서로를 기다렸습니다. 처음부터 `SELECT ... FOR UPDATE`로 세션 행에 배타 락을 잡아 같은 세션의 요청을 한 줄로 세우자 데드락과 seq 충돌이 함께 사라졌습니다. ([5 §9](docs/5-design.md#9-검증-결과-순서-중복-동시성))

### 2. UNIQUE 제약이 있는데 락이 꼭 필요한가

`UNIQUE(session_id, seq)`와 `UNIQUE(session_id, client_event_id)`가 있으니 락 없이도 중복 저장은 막힐 것 같았습니다. 확인하려고 `@Lock(PESSIMISTIC_WRITE)`만 빼고 동시성 테스트를 다시 돌렸습니다. 예상대로 잘못된 데이터는 저장되지 않았지만, 동시 전송은 같은 seq 발급으로 UNIQUE 위반이, 동시 재전송은 데드락이 나서 정상 요청이 실패 응답을 받았습니다. 그래서 **UNIQUE는 정합성을, 락은 모든 요청이 올바른 응답을 받는 것을 책임진다**고 정리했고, 동시에 테스트가 실제로 락을 검증하고 있다는 것도 확인했습니다.

### 3. 재전송은 중복 확인이 검증보다 먼저

처리 순서를 "검증 → 중복 확인"으로 두면, 메시지를 보낸 직후 세션이 종료된 상황에서 ACK를 못 받은 클라이언트가 재전송할 때 "종료된 세션" 에러를 받습니다. 이미 저장된 이벤트인데 클라이언트는 실패로 알게 됩니다. 그래서 순서를 **락 → 중복 확인 → 검증 → 저장**으로 정하고, 중복이면 최초 결과를 200으로 그대로 돌려주도록 했습니다. 이 경우는 `retryAfterEndStillIdempotent` 테스트로 고정했습니다.

### 4. 매번 처음부터 리플레이하는 비용: 스냅샷

시점 복원을 처음 구현했을 때는 항상 seq 1부터 모든 이벤트를 다시 적용했습니다. 이벤트는 계속 쌓이기만 하니 오래된 세션일수록 복원이 느려진다는 점이 걸렸고, 스냅샷을 도입하면서 네 가지를 정했습니다.

- **주기 100**: 스냅샷에 메시지 전체가 들어가므로, 이벤트 1만 개 세션 기준 주기 10이면 메시지 복사본이 약 500만 개, 100이면 약 50만 개입니다. 리플레이 99건은 메모리 연산이라 충분히 빠르다고 판단했습니다. 세션 종료 시점에도 최종 상태를 남깁니다.
- **커밋 후 비동기 생성**: 스냅샷 때문에 메시지 응답이 늦어지거나 락을 오래 잡으면 안 되기 때문입니다.
- **메시지를 포함한 전체 상태**: 복원 결과에 메시지가 들어가므로 스냅샷 하나로 그 시점을 그대로 재현해야 합니다.
- **실패하면 로그만 남기고 리플레이로 대체**: 스냅샷은 캐시일 뿐 정답의 근거는 이벤트이기 때문입니다.

스냅샷 기반 복원이 모든 시점에서 전체 리플레이와 같은지 테스트로 확인했습니다. ([6 §5](docs/6-state-restoration.md#5-스냅샷-정책--구현-완료))

### 5. 소켓은 연결돼 있는데 OFFLINE?

처음에는 presence를 "소켓이 연결돼 있는가"로 생각했는데, 퇴장한 사람이 소켓을 유지하고 있으면 ONLINE으로 보이는 문제가 있었습니다. 실제 메신저를 떠올려 보니 상대 이름 옆의 초록 점은 앱 접속이 아니라 **이 대화방에 지금 있는가**였습니다. 그래서 참여 상태(JOINED/LEFT)와 접속 상태(ONLINE/OFFLINE)를 두 필드로 나눴습니다. "잠시 끊긴 사람(JOINED/OFFLINE)"과 "나간 사람(LEFT/OFFLINE)"은 상대 화면에서 다르게 보여야 하는데, 필드가 하나면 둘을 구분할 수 없기 때문입니다. ([5 §2](docs/5-design.md#2-join--leave-presence-이벤트-수집-api--구현-완료))
