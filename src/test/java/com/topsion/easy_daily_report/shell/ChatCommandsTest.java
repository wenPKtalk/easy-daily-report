package com.topsion.easy_daily_report.shell;

import com.topsion.easy_daily_report.application.chat.ChatOrchestrator;
import com.topsion.easy_daily_report.application.chat.ChatSession;
import com.topsion.easy_daily_report.application.usecase.AgentLevel;
import com.topsion.easy_daily_report.infrastructure.chat.ChatSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ChatCommandsTest {

    @Mock private ChatOrchestrator chatOrchestrator;
    @Mock private ChatSessionRepository sessionRepository;

    private ChatCommands chatCommands;
    private ChatSession session;

    @BeforeEach
    void setUp() {
        chatCommands = new ChatCommands(chatOrchestrator, sessionRepository);
        session = new ChatSession(
            UUID.randomUUID().toString(), "user@test.com",
            AgentLevel.SINGLE, false,
            Map.of(), List.of(), LocalDateTime.now(), LocalDateTime.now()
        );
    }

    @Test
    @DisplayName("handleBuiltinCommand /mode single switches session to SINGLE mode")
    void handleBuiltinCommand_modeSingle_switchesMode() {
        ChatSession result = chatCommands.handleBuiltinCommand("/mode single", session);
        assertThat(result.currentMode()).isEqualTo(AgentLevel.SINGLE);
        assertThat(result.modeOverridden()).isTrue();
    }

    @Test
    @DisplayName("handleBuiltinCommand /mode multi switches session to SAMPLE_MULTIPLE mode")
    void handleBuiltinCommand_modeMulti_switchesMode() {
        ChatSession result = chatCommands.handleBuiltinCommand("/mode multi", session);
        assertThat(result.currentMode()).isEqualTo(AgentLevel.SAMPLE_MULTIPLE);
        assertThat(result.modeOverridden()).isTrue();
    }

    @Test
    @DisplayName("handleBuiltinCommand /mode auto restores auto routing")
    void handleBuiltinCommand_modeAuto_clearsOverride() {
        ChatSession overridden = session.withMode(AgentLevel.SAMPLE_MULTIPLE, true);
        ChatSession result = chatCommands.handleBuiltinCommand("/mode auto", overridden);
        assertThat(result.modeOverridden()).isFalse();
    }

    @Test
    @DisplayName("handleBuiltinCommand /clear resets context while preserving sessionId")
    void handleBuiltinCommand_clear_resetsContext() {
        ChatSession withContext = new ChatSession(
            session.sessionId(), session.userId(), session.currentMode(), false,
            Map.of("commitHash", "abc"), session.history(), session.createdAt(), session.lastActiveAt()
        );
        ChatSession result = chatCommands.handleBuiltinCommand("/clear", withContext);
        assertThat(result.context()).isEmpty();
        assertThat(result.sessionId()).isEqualTo(session.sessionId());
    }

    @Test
    @DisplayName("isBuiltinCommand returns true for slash-prefixed commands")
    void isBuiltinCommand_slashPrefixed_returnsTrue() {
        assertThat(chatCommands.isBuiltinCommand("/mode single")).isTrue();
        assertThat(chatCommands.isBuiltinCommand("/context")).isTrue();
        assertThat(chatCommands.isBuiltinCommand("/exit")).isTrue();
        assertThat(chatCommands.isBuiltinCommand("/help")).isTrue();
    }

    @Test
    @DisplayName("isBuiltinCommand returns false for regular user input")
    void isBuiltinCommand_normalInput_returnsFalse() {
        assertThat(chatCommands.isBuiltinCommand("generate report")).isFalse();
        assertThat(chatCommands.isBuiltinCommand("hello")).isFalse();
    }
}
