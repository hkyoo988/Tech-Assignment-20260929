-- 세션: 현재 상태 + 다음에 부여할 seq를 관리
CREATE TABLE chat_session (
    id              CHAR(36)    NOT NULL PRIMARY KEY,
    participant_a   VARCHAR(64) NOT NULL,
    participant_b   VARCHAR(64) NOT NULL,
    status          VARCHAR(20) NOT NULL,            -- ACTIVE / INTERRUPTED / COMPLETED
    last_seq        BIGINT      NOT NULL DEFAULT 0,  -- 서버가 부여한 마지막 순번
    started_at      DATETIME(6) NOT NULL,
    ended_at        DATETIME(6) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 이벤트 저장소: append-only (UPDATE/DELETE 하지 않음)
CREATE TABLE session_event (
    id               BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id       CHAR(36)    NOT NULL,
    seq              BIGINT      NOT NULL,          -- 순서의 단일 기준 (서버 부여)
    client_event_id  VARCHAR(64) NOT NULL,          -- 클라이언트가 만든 멱등성 키
    type             VARCHAR(30) NOT NULL,
    user_id          VARCHAR(64) NOT NULL,
    payload          JSON        NULL,
    client_ts        DATETIME(6) NULL,              -- 참고용 (신뢰하지 않음)
    server_ts        DATETIME(6) NOT NULL,
    CONSTRAINT fk_event_session   FOREIGN KEY (session_id) REFERENCES chat_session(id),
    CONSTRAINT uk_event_seq       UNIQUE (session_id, seq),             -- 순서 중복 방지
    CONSTRAINT uk_event_client_id UNIQUE (session_id, client_event_id), -- 재전송 중복 방지
    INDEX idx_event_session_server_ts (session_id, server_ts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 스냅샷: 특정 seq 시점의 상태
CREATE TABLE session_snapshot (
    session_id  CHAR(36)    NOT NULL,
    seq         BIGINT      NOT NULL,
    state       JSON        NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (session_id, seq),
    CONSTRAINT fk_snapshot_session FOREIGN KEY (session_id) REFERENCES chat_session(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
