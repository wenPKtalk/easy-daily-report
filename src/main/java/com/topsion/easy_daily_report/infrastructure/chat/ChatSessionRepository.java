package com.topsion.easy_daily_report.infrastructure.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.topsion.easy_daily_report.application.chat.ChatSession;
import com.topsion.easy_daily_report.application.chat.ConversationTurn;
import com.topsion.easy_daily_report.application.usecase.AgentLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Slf4j
public class ChatSessionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private static final String SESSION_ACTIVE_HOURS = "24";

    public void save(ChatSession session) {
        String contextJson = toJson(session.context());

        jdbcTemplate.update(
            """
            INSERT INTO chat_sessions
                (session_id, user_id, current_mode, mode_overridden, context_json, created_at, last_active_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (session_id) DO UPDATE SET
                current_mode    = EXCLUDED.current_mode,
                mode_overridden = EXCLUDED.mode_overridden,
                context_json    = EXCLUDED.context_json,
                last_active_at  = EXCLUDED.last_active_at
            """,
            session.sessionId(),
            session.userId(),
            session.currentMode().name(),
            session.modeOverridden(),
            contextJson,
            Timestamp.valueOf(session.createdAt()),
            Timestamp.valueOf(session.lastActiveAt())
        );

        saveMostRecentTurn(session);
    }

    public Optional<ChatSession> findActiveSession(String userId) {
        try {
            ChatSession session = jdbcTemplate.queryForObject(
                """
                SELECT session_id, user_id, current_mode, mode_overridden, context_json,
                       created_at, last_active_at
                FROM chat_sessions
                WHERE user_id = ?
                  AND last_active_at > NOW() - INTERVAL '%s hours'
                ORDER BY last_active_at DESC
                LIMIT 1
                """.formatted(SESSION_ACTIVE_HOURS),
                sessionRowMapper(),
                new Object[]{userId}
            );
            return Optional.ofNullable(session);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<ConversationTurn> findRecentTurns(String sessionId, int limit) {
        return jdbcTemplate.query(
            """
            SELECT role, content, created_at FROM conversation_turns
            WHERE session_id = ?
            ORDER BY created_at DESC
            LIMIT ?
            """,
            (rs, rowNum) -> new ConversationTurn(
                rs.getString("role"),
                rs.getString("content"),
                rs.getTimestamp("created_at").toLocalDateTime()
            ),
            sessionId, limit
        );
    }

    private void saveMostRecentTurn(ChatSession session) {
        if (session.history().isEmpty()) return;
        ConversationTurn last = session.history().get(session.history().size() - 1);
        jdbcTemplate.update(
            "INSERT INTO conversation_turns (session_id, role, content, created_at) VALUES (?, ?, ?, ?)",
            session.sessionId(),
            last.role(),
            last.content(),
            Timestamp.valueOf(last.at())
        );
    }

    private RowMapper<ChatSession> sessionRowMapper() {
        return (rs, rowNum) -> new ChatSession(
            rs.getString("session_id"),
            rs.getString("user_id"),
            AgentLevel.valueOf(rs.getString("current_mode")),
            rs.getBoolean("mode_overridden"),
            fromJson(rs.getString("context_json")),
            Collections.emptyList(),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("last_active_at").toLocalDateTime()
        );
    }

    private String toJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.error("Failed to serialize context map", e);
            return "{}";
        }
    }

    private Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.error("Failed to deserialize context JSON: {}", json, e);
            return Collections.emptyMap();
        }
    }
}
