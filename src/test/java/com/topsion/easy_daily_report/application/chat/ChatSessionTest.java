package com.topsion.easy_daily_report.application.chat;

import com.topsion.easy_daily_report.agent.supervisor.Intent;
import com.topsion.easy_daily_report.agent.supervisor.SupervisorDecision;
import com.topsion.easy_daily_report.application.usecase.AgentLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChatSessionTest {

    private ChatSession newSession() {
        return new ChatSession(
            UUID.randomUUID().toString(),
            "user@example.com",
            AgentLevel.SINGLE,
            false,
            Map.of(),
            List.of(),
            LocalDateTime.now(),
            LocalDateTime.now()
        );
    }

    @Test
    @DisplayName("withMode returns new session with updated mode and modeOverridden flag")
    void withMode_returnsNewSessionWithUpdatedFields() {
        ChatSession original = newSession();
        ChatSession updated = original.withMode(AgentLevel.SAMPLE_MULTIPLE, true);

        assertThat(updated.currentMode()).isEqualTo(AgentLevel.SAMPLE_MULTIPLE);
        assertThat(updated.modeOverridden()).isTrue();
        assertThat(updated.sessionId()).isEqualTo(original.sessionId());
        assertThat(updated).isNotSameAs(original);
    }

    @Test
    @DisplayName("appendTurn returns new session with one more history entry")
    void appendTurn_addsNewConversationTurn() {
        ChatSession session = newSession();
        ChatSession updated = session.appendTurn("user", "hello");

        assertThat(updated.history()).hasSize(1);
        assertThat(updated.history().get(0).role()).isEqualTo("user");
        assertThat(updated.history().get(0).content()).isEqualTo("hello");
    }

    @Test
    @DisplayName("updateContext with SupervisorDecision merges non-null fields into context")
    void updateContext_mergesDecisionFields() {
        ChatSession session = newSession();
        SupervisorDecision decision = new SupervisorDecision(
            Intent.GENERATE_REPORT,
            AgentLevel.SAMPLE_MULTIPLE,
            "abc123",
            "PROJ-456",
            null,
            null
        );

        ChatSession updated = session.updateContext(decision);

        assertThat(updated.context()).containsEntry("commitHash", "abc123");
        assertThat(updated.context()).containsEntry("jiraKey", "PROJ-456");
    }

    @Test
    @DisplayName("updateContext skips null commitHash and jiraKey")
    void updateContext_skipsNullFields() {
        ChatSession session = newSession();
        SupervisorDecision decision = new SupervisorDecision(
            Intent.FOLLOW_UP,
            null,
            null,
            null,
            "here is the English version",
            null
        );

        ChatSession updated = session.updateContext(decision);

        assertThat(updated.context()).isEmpty();
    }

    @Test
    @DisplayName("contextAsString returns human-readable representation of context map")
    void contextAsString_returnsFormattedString() {
        ChatSession session = newSession().updateContext(new SupervisorDecision(
            Intent.GENERATE_REPORT, AgentLevel.SINGLE, "abc123", null, null, null
        ));

        String result = session.contextAsString();

        assertThat(result).contains("commitHash=abc123");
    }

    @Test
    @DisplayName("contextAsString returns empty braces for empty context")
    void contextAsString_emptyContext() {
        ChatSession session = newSession();
        assertThat(session.contextAsString()).isEqualTo("{}");
    }
}
