# 1:1 실시간 채팅 서비스 (Event Sourcing 기반 상태 복원)

> 상태: **작성 중**

## 개요
1:1 참여자 간 실시간 채팅 서비스. 대화 중 발생한 모든 일을 이벤트로 저장하고(append-only), 이벤트를 리플레이해 **특정 시점의 대화 상태를 복원**한다.

## 기술 스택과 선택 근거
| 구분 | 선택 | 근거 |
|---|---|---|
| 언어/프레임워크 | Java 21, Spring Boot 4 | LTS, 생태계 성숙도, WebSocket·JPA·검증·관측(Actuator) 기본 제공 |
| DB | MySQL 8.4 (InnoDB) | 행 단위 비관적 락(`FOR UPDATE`), UNIQUE 제약, JSON 타입으로 순서·중복 보장을 DB 수준에서 강제 |
| 스키마 관리 | Flyway | 스키마를 코드와 함께 버전 관리, JPA는 `ddl-auto: validate`로 검증만 |
| 실시간 통신 | Spring WebSocket | TODO |

## 실행 방법
```bash
docker compose up -d        # MySQL
./gradlew bootRun           # 앱 (Flyway가 테이블 생성)
curl localhost:8080/actuator/health
```

## 문서
| 문서 | 내용 |
|---|---|
| [설계 결정 목록](docs/00-decisions.md) | 주요 결정과 대안 |
| [도메인·ERD·DDL](docs/01-domain-and-erd.md) | 테이블, 인덱스 근거, 정규화/JSON 트레이드오프 |
| [API 명세](docs/02-api-spec.md) | REST, WebSocket |
| [순서·중복 처리](docs/03-ordering-and-idempotency.md) | seq 기준, 멱등 키 |
| [상태 복원](docs/04-state-restoration.md) | 스냅샷 + 리플레이, 결정성 |
| [쿼리 최적화](docs/05-query-optimization.md) | 핫패스 쿼리, 인덱스, 병목 |
| [운영 설계](docs/06-operations.md) | 재연결, 확장, 관측, 비동기, 장애 대응 |

## 주요 의사결정 요약
TODO (docs/00-decisions.md 확정 후 요약)

## 구현 범위
| 항목 | 상태 |
|---|---|
| 세션 생성 / 이벤트 수집·조회 API | ✅ |
| 중복 이벤트 멱등 처리, seq 동시성 | ⏳ |
| join / leave / end | ⏳ |
| WebSocket 실시간 송수신, presence | ⏳ |
| 시점 복원 API | ⏳ |
| 스냅샷 자동 생성 (가산점) | ⏳ |

## AI 활용 내역
코드 초안 작성과 리뷰, 문서 초안 작성에 AI(Claude)를 활용했다. 설계 결정, 실행 검증, 최종 검토는 직접 수행했다.
