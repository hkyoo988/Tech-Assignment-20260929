CREATE TABLE session_participant (
    id               BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id       CHAR(36)    NOT NULL,
    user_id          VARCHAR(64) NOT NULL,
    state            VARCHAR(10) NOT NULL,   -- NOT_JOINED / JOINED / LEFT
    presence         VARCHAR(10) NOT NULL,   -- ONLINE / OFFLINE
    last_applied_seq BIGINT      NOT NULL,   -- 이 seq까지 반영됨 (중복 반영 방지)
    updated_at       DATETIME(6) NOT NULL,
    CONSTRAINT uk_participant UNIQUE (session_id, user_id),
    INDEX idx_participant_user (user_id, session_id),     -- GET /sessions?participant= 용
    CONSTRAINT fk_participant_session FOREIGN KEY (session_id) REFERENCES chat_session(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
