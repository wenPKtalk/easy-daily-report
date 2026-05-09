CREATE TABLE IF NOT EXISTS chat_sessions (
    session_id      VARCHAR(36) PRIMARY KEY,
    user_id         VARCHAR(255),
    current_mode    VARCHAR(50),
    mode_overridden BOOLEAN DEFAULT FALSE,
    context_json    TEXT,
    created_at      TIMESTAMP,
    last_active_at  TIMESTAMP
);

CREATE TABLE IF NOT EXISTS conversation_turns (
    id          BIGSERIAL PRIMARY KEY,
    session_id  VARCHAR(36) REFERENCES chat_sessions(session_id),
    role        VARCHAR(20),
    content     TEXT,
    created_at  TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_conversation_turns_session
    ON conversation_turns(session_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_active
    ON chat_sessions(user_id, last_active_at DESC);
