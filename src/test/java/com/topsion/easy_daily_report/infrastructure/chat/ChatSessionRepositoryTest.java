package com.topsion.easy_daily_report.infrastructure.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.topsion.easy_daily_report.application.chat.ChatSession;
import com.topsion.easy_daily_report.application.usecase.AgentLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatSessionRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ChatSessionRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ChatSessionRepository(jdbcTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("save executes upsert SQL with correct parameters")
    void save_executesUpsertSql() {
        ChatSession session = new ChatSession(
            UUID.randomUUID().toString(), "user@test.com",
            AgentLevel.SINGLE, false,
            Map.of("commitHash", "abc123"),
            List.of(), LocalDateTime.now(), LocalDateTime.now()
        );

        repository.save(session);

        verify(jdbcTemplate).update(
            contains("INSERT INTO chat_sessions"),
            any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    @DisplayName("findActiveSession returns empty Optional when query returns null")
    void findActiveSession_noResult_returnsEmpty() {
        when(jdbcTemplate.queryForObject(
            anyString(),
            any(RowMapper.class),
            any(Object[].class)))
            .thenReturn(null);

        Optional<ChatSession> result = repository.findActiveSession("user@test.com");

        assertThat(result).isEmpty();
    }
}
