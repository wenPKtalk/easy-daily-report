-- =============================================================================
-- Easy Daily Report - 对话模式表结构初始化
-- 对应应用层 chat 模式（ChatSession + ConversationTurn）
-- =============================================================================

-- 会话表：每个会话对应一次持续的对话上下文
CREATE TABLE IF NOT EXISTS chat_sessions (
    session_id      VARCHAR(36) PRIMARY KEY,
    user_id         VARCHAR(255),
    current_mode    VARCHAR(50),
    mode_overridden BOOLEAN DEFAULT FALSE,
    context_json    JSONB,
    created_at      TIMESTAMP,
    last_active_at  TIMESTAMP
);

-- 对话轮次表：每条 user/assistant 消息一行
CREATE TABLE IF NOT EXISTS conversation_turns (
    id          BIGSERIAL PRIMARY KEY,
    session_id  VARCHAR(36) REFERENCES chat_sessions(session_id),
    role        VARCHAR(20),
    content     TEXT,
    created_at  TIMESTAMP
);

-- 会话级索引：按用户最近活跃倒序拉取
CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_active
    ON chat_sessions(user_id, last_active_at DESC);

-- 轮次级索引：按会话内时间倒序拉取最近上下文
CREATE INDEX IF NOT EXISTS idx_conversation_turns_session
    ON conversation_turns(session_id, created_at DESC);
