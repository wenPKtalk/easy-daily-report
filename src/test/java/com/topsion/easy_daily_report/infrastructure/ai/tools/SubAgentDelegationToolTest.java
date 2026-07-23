package com.topsion.easy_daily_report.infrastructure.ai.tools;

import com.topsion.easy_daily_report.agent.subagents.GitDiffAnalyzerAgent;
import com.topsion.easy_daily_report.agent.subagents.JiraAnalyzerAgent;
import com.topsion.easy_daily_report.agent.subagents.ReportGeneratorAgent;
import com.topsion.easy_daily_report.domain.port.ReportStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubAgentDelegationToolTest {

    @Mock
    private GitTool gitTool;
    @Mock
    private JiraTool jiraTool;
    @Mock
    private GitDiffAnalyzerAgent gitDiffAnalyzerAgent;
    @Mock
    private JiraAnalyzerAgent jiraAnalyzerAgent;
    @Mock
    private ReportGeneratorAgent reportGeneratorAgent;
    @Mock
    private ReportStore reportStore;

    private SubAgentDelegationTool tool;

    @BeforeEach
    void setUp() {
        tool = new SubAgentDelegationTool(
                gitTool,
                jiraTool,
                gitDiffAnalyzerAgent,
                jiraAnalyzerAgent,
                reportGeneratorAgent,
                reportStore
        );
    }

    @Nested
    @DisplayName("analyzeGitChanges")
    class AnalyzeGitChanges {

        @Test
        @DisplayName("returns analyzer JSON when diff is available")
        void happyPath() {
            when(gitTool.getCommitDiff("abc123")).thenReturn("diff --git ...");
            when(gitDiffAnalyzerAgent.analyze(anyString())).thenReturn("{\"files_count\":3}");

            String result = tool.analyzeGitChanges("abc123");

            assertThat(result).isEqualTo("{\"files_count\":3}");
        }

        @Test
        @DisplayName("returns empty-analysis JSON when diff is blank")
        void blankDiff_returnsEmptyAnalysis() {
            when(gitTool.getCommitDiff("abc123")).thenReturn("   ");

            String result = tool.analyzeGitChanges("abc123");

            assertThat(result).contains("\"files_count\": 0");
            assertThat(result).contains("未找到代码变更");
            verifyNoInteractions(gitDiffAnalyzerAgent);
        }

        @Test
        @DisplayName("returns error-analysis JSON when GitTool throws (fail-soft)")
        void gitToolThrows_returnsErrorAnalysis() {
            when(gitTool.getCommitDiff("bad")).thenThrow(new RuntimeException("git boom"));

            String result = tool.analyzeGitChanges("bad");

            assertThat(result).contains("分析失败");
            assertThat(result).contains("git boom");
            verifyNoInteractions(gitDiffAnalyzerAgent);
        }

        @Test
        @DisplayName("escapes quotes in error message to keep JSON valid")
        void errorMessageWithQuotes_isEscaped() {
            when(gitTool.getCommitDiff("bad"))
                    .thenThrow(new RuntimeException("error \"quote\" inside"));

            String result = tool.analyzeGitChanges("bad");

            assertThat(result).contains("\\\"quote\\\"");
        }
    }

    @Nested
    @DisplayName("analyzeJiraIssue")
    class AnalyzeJiraIssue {

        @Test
        @DisplayName("returns analyzer JSON when issue content is available")
        void happyPath() {
            when(jiraTool.getJiraIssue("PROJ-1")).thenReturn("Issue: PROJ-1\nSummary: ...");
            when(jiraAnalyzerAgent.analyze(anyString())).thenReturn("{\"priority\":\"High\"}");

            String result = tool.analyzeJiraIssue("PROJ-1");

            assertThat(result).isEqualTo("{\"priority\":\"High\"}");
        }

        @Test
        @DisplayName("returns empty-analysis JSON when issue content is blank")
        void blankIssue_returnsEmptyAnalysis() {
            when(jiraTool.getJiraIssue("PROJ-2")).thenReturn("");

            String result = tool.analyzeJiraIssue("PROJ-2");

            assertThat(result).contains("\"issue_type\": \"Unknown\"");
            assertThat(result).contains("PROJ-2");
            verifyNoInteractions(jiraAnalyzerAgent);
        }

        @Test
        @DisplayName("returns error-analysis JSON when JiraTool throws (fail-soft)")
        void jiraToolThrows_returnsErrorAnalysis() {
            when(jiraTool.getJiraIssue("bad")).thenThrow(new RuntimeException("jira boom"));

            String result = tool.analyzeJiraIssue("bad");

            assertThat(result).contains("\"issue_type\": \"Error\"");
            assertThat(result).contains("jira boom");
            verifyNoInteractions(jiraAnalyzerAgent);
        }
    }

    @Nested
    @DisplayName("retrieveSimilarReports")
    class RetrieveSimilarReports {

        @Test
        @DisplayName("joins reports with separator when matches found")
        void happyPath_joined() {
            when(reportStore.searchSimilar("query", 3))
                    .thenReturn(List.of("report-1", "report-2"));

            String result = tool.retrieveSimilarReports("query");

            assertThat(result).isEqualTo("report-1\n---\nreport-2");
        }

        @Test
        @DisplayName("returns empty string when no matches")
        void noMatches_returnsEmpty() {
            when(reportStore.searchSimilar("query", 3)).thenReturn(List.of());

            assertThat(tool.retrieveSimilarReports("query")).isEmpty();
        }

        @Test
        @DisplayName("returns empty string when query is blank without hitting store")
        void blankQuery_returnsEmpty() {
            assertThat(tool.retrieveSimilarReports("   ")).isEmpty();
            verifyNoInteractions(reportStore);
        }

        @Test
        @DisplayName("returns empty string when ReportStore throws (fail-soft)")
        void storeThrows_returnsEmpty() {
            when(reportStore.searchSimilar(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("rag boom"));

            assertThat(tool.retrieveSimilarReports("query")).isEmpty();
        }
    }

    @Nested
    @DisplayName("composeFinalReport")
    class ComposeFinalReport {

        @Test
        @DisplayName("delegates to ReportGeneratorAgent with a message carrying the analyses + date + history")
        void includesAnalysesAndHistoricalContext() {
            when(reportGeneratorAgent.generate(anyString()))
                    .thenReturn("# Final Markdown");

            String result = tool.composeFinalReport(
                    "{\"git\":1}",
                    "{\"jira\":1}",
                    "history-snippet",
                    "2026-05-28"
            );

            assertThat(result).isEqualTo("# Final Markdown");
            // C1 regression guard: the analysis JSON MUST actually reach the model.
            verify(reportGeneratorAgent).generate(
                    org.mockito.ArgumentMatchers.argThat(msg ->
                            msg.contains("{\"git\":1}")
                                    && msg.contains("{\"jira\":1}")
                                    && msg.contains("2026-05-28")
                                    && msg.contains("history-snippet"))
            );
        }

        @Test
        @DisplayName("omits the historical-context section when it is blank")
        void blankHistoricalContext_omitsHistorySection() {
            when(reportGeneratorAgent.generate(anyString()))
                    .thenReturn("# Final");

            tool.composeFinalReport("{\"git\":1}", "{\"jira\":1}", "  ", "2026-05-28");

            verify(reportGeneratorAgent).generate(
                    org.mockito.ArgumentMatchers.argThat(msg ->
                            !msg.contains("历史相似日报")
                                    && msg.contains("{\"git\":1}")
                                    && msg.contains("{\"jira\":1}")
                                    && msg.contains("2026-05-28"))
            );
        }

        @Test
        @DisplayName("propagates ReportGeneratorAgent exceptions (terminal step is not fail-soft)")
        void generatorThrows_propagates() {
            when(reportGeneratorAgent.generate(anyString()))
                    .thenThrow(new RuntimeException("generator boom"));

            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> tool.composeFinalReport("{}", "{}", "", "2026-05-28"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("generator boom");
        }
    }
}
