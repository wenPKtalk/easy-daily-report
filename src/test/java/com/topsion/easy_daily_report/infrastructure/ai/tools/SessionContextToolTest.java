package com.topsion.easy_daily_report.infrastructure.ai.tools;

import com.topsion.easy_daily_report.application.chat.ChatSession;
import com.topsion.easy_daily_report.application.usecase.AgentLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SessionContextToolTest {

    private SessionContextTool tool;

    @BeforeEach
    void setUp() {
        tool = new SessionContextTool();
    }

    @Test
    @DisplayName("getContext returns serialized context when session is set")
    void getContext_sessionSet_returnsContextString() {
        ChatSession session = new ChatSession(
            UUID.randomUUID().toString(), "user@test.com",
            AgentLevel.SINGLE, false,
            Map.of("commitHash", "abc123", "jiraKey", "PROJ-1"),
            List.of(), LocalDateTime.now(), LocalDateTime.now()
        );
        tool.setCurrentSession(session);

        String context = tool.getContext();

        assertThat(context).contains("commitHash=abc123");
        assertThat(context).contains("jiraKey=PROJ-1");
    }

    @Test
    @DisplayName("getContext returns empty braces when no session is set")
    void getContext_noSession_returnsEmptyBraces() {
        assertThat(tool.getContext()).isEqualTo("{}");
    }

    @Test
    @DisplayName("updateContext stores key-value in current session context")
    void updateContext_updatesSessionContext() {
        ChatSession session = new ChatSession(
            UUID.randomUUID().toString(), "user@test.com",
            AgentLevel.SINGLE, false,
            Map.of(), List.of(), LocalDateTime.now(), LocalDateTime.now()
        );
        tool.setCurrentSession(session);

        tool.updateContext("commitHash", "def456");

        assertThat(tool.getContext()).contains("commitHash=def456");
    }

    @Test
    @DisplayName("getUpdatedSession returns the session with context changes applied")
    void getUpdatedSession_reflectsUpdates() {
        ChatSession session = new ChatSession(
            UUID.randomUUID().toString(), "user@test.com",
            AgentLevel.SINGLE, false,
            Map.of(), List.of(), LocalDateTime.now(), LocalDateTime.now()
        );
        tool.setCurrentSession(session);
        tool.updateContext("jiraKey", "PROJ-99");

        ChatSession updated = tool.getUpdatedSession();

        assertThat(updated.context()).containsEntry("jiraKey", "PROJ-99");
    }
}
