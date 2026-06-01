package com.topsion.easy_daily_report.application.usecase;

import com.topsion.easy_daily_report.agent.coordinator.CoordinatorAgent;
import com.topsion.easy_daily_report.domain.model.DailyReport;
import com.topsion.easy_daily_report.domain.model.ReportRequest;
import com.topsion.easy_daily_report.domain.port.ReportStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoordinatorOrchestratorTest {

    @Mock
    private CoordinatorAgent coordinatorAgent;

    @Mock
    private ReportStore reportStore;

    private CoordinatorOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new CoordinatorOrchestrator(coordinatorAgent, reportStore);
    }

    @Test
    @DisplayName("execute forwards commit/jira/date to coordinator and saves the result")
    void execute_happyPath_savesAndReturns() {
        when(coordinatorAgent.coordinate(anyString(), anyString(), anyString()))
                .thenReturn("# Coordinator Markdown");

        DailyReport report = orchestrator.execute(
                new ReportRequest("abc123", null, "PROJ-1", "./")
        );

        assertThat(report.rawMarkdown()).isEqualTo("# Coordinator Markdown");
        verify(coordinatorAgent).coordinate("abc123", "PROJ-1",
                report.date().toString());
        verify(reportStore).save(report);
    }

    @Test
    @DisplayName("execute converts null commitHash/jiraKey to empty strings for template safety")
    void execute_nullInputs_passEmptyStrings() {
        when(coordinatorAgent.coordinate(anyString(), anyString(), anyString()))
                .thenReturn("# Md");

        orchestrator.execute(new ReportRequest(null, null, null, null));

        ArgumentCaptor<String> commit = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jira = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> date = ArgumentCaptor.forClass(String.class);
        verify(coordinatorAgent).coordinate(commit.capture(), jira.capture(), date.capture());

        assertThat(commit.getValue()).isEmpty();
        assertThat(jira.getValue()).isEmpty();
        assertThat(date.getValue()).matches("\\d{4}-\\d{2}-\\d{2}");
    }
}
