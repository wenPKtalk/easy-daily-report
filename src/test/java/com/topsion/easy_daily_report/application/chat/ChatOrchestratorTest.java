package com.topsion.easy_daily_report.application.chat;

import com.topsion.easy_daily_report.agent.supervisor.Intent;
import com.topsion.easy_daily_report.agent.supervisor.SupervisorAgent;
import com.topsion.easy_daily_report.agent.supervisor.SupervisorDecision;
import com.topsion.easy_daily_report.application.usecase.AgentLevel;
import com.topsion.easy_daily_report.application.usecase.AgentRouter;
import com.topsion.easy_daily_report.application.usecase.GenerateAgent;
import com.topsion.easy_daily_report.domain.model.DailyReport;
import com.topsion.easy_daily_report.domain.model.ReportRequest;
import com.topsion.easy_daily_report.infrastructure.ai.tools.SessionContextTool;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatOrchestratorTest {

    @Mock private SupervisorAgent supervisorAgent;
    @Mock private AgentRouter agentRouter;
    @Mock private SessionContextTool sessionContextTool;
    @Mock private ChatSessionRepository sessionRepository;
    @Mock private GenerateAgent generateAgent;

    private ChatOrchestrator orchestrator;
    private ChatSession baseSession;

    @BeforeEach
    void setUp() {
        orchestrator = new ChatOrchestrator(
            supervisorAgent, agentRouter, sessionContextTool, sessionRepository, "./"
        );
        baseSession = new ChatSession(
            UUID.randomUUID().toString(), "user@test.com",
            AgentLevel.SINGLE, false,
            Map.of(), List.of(), LocalDateTime.now(), LocalDateTime.now()
        );
    }

    @Test
    @DisplayName("handleInput with FOLLOW_UP intent returns directResponse directly")
    void handleInput_followUpIntent_returnsDirectResponse() {
        SupervisorDecision decision = new SupervisorDecision(
            Intent.FOLLOW_UP, null, null, null, "Here is English version", null
        );
        when(supervisorAgent.decide(anyString(), anyString())).thenReturn(decision);
        when(sessionContextTool.getUpdatedSession()).thenReturn(baseSession);

        String output = orchestrator.handleInput("translate to English", baseSession);

        assertThat(output).isEqualTo("Here is English version");
        verifyNoInteractions(agentRouter);
    }

    @Test
    @DisplayName("handleInput with CLARIFY intent returns clarifyQuestion")
    void handleInput_clarifyIntent_returnsClarifyQuestion() {
        SupervisorDecision decision = new SupervisorDecision(
            Intent.CLARIFY, null, null, null, null, "Please provide a commit hash"
        );
        when(supervisorAgent.decide(anyString(), anyString())).thenReturn(decision);
        when(sessionContextTool.getUpdatedSession()).thenReturn(baseSession);

        String output = orchestrator.handleInput("generate report", baseSession);

        assertThat(output).isEqualTo("Please provide a commit hash");
    }

    @Test
    @DisplayName("handleInput with GENERATE_REPORT routes to AgentRouter and returns report markdown")
    void handleInput_generateReportIntent_routesAndReturnsMarkdown() {
        SupervisorDecision decision = new SupervisorDecision(
            Intent.GENERATE_REPORT, AgentLevel.SINGLE, "abc123", null, null, null
        );
        DailyReport report = DailyReport.fromMarkdown("# Daily Report\n content");
        when(supervisorAgent.decide(anyString(), anyString())).thenReturn(decision);
        when(sessionContextTool.getUpdatedSession()).thenReturn(baseSession);
        when(agentRouter.route(AgentLevel.SINGLE)).thenReturn(generateAgent);
        when(generateAgent.execute(any(ReportRequest.class))).thenReturn(report);

        String output = orchestrator.handleInput("analyze commit abc123", baseSession);

        assertThat(output).contains("Daily Report");
        verify(agentRouter).route(AgentLevel.SINGLE);
    }

    @Test
    @DisplayName("handleInput with modeOverridden=true ignores supervisor agentLevel")
    void handleInput_modeOverridden_usesForcedMode() {
        ChatSession overriddenSession = baseSession.withMode(AgentLevel.SAMPLE_MULTIPLE, true);
        SupervisorDecision decision = new SupervisorDecision(
            Intent.GENERATE_REPORT, AgentLevel.SINGLE, "abc123", null, null, null
        );
        DailyReport report = DailyReport.fromMarkdown("# Report");
        when(supervisorAgent.decide(anyString(), anyString())).thenReturn(decision);
        when(sessionContextTool.getUpdatedSession()).thenReturn(overriddenSession);
        when(agentRouter.route(AgentLevel.SAMPLE_MULTIPLE)).thenReturn(generateAgent);
        when(generateAgent.execute(any(ReportRequest.class))).thenReturn(report);

        orchestrator.handleInput("analyze commit abc123", overriddenSession);

        verify(agentRouter).route(AgentLevel.SAMPLE_MULTIPLE);
    }
}
