package com.topsion.easy_daily_report.e2e;

import com.topsion.easy_daily_report.agent.coordinator.CoordinatorAgent;
import com.topsion.easy_daily_report.agent.subagents.GitDiffAnalyzerAgent;
import com.topsion.easy_daily_report.agent.subagents.JiraAnalyzerAgent;
import com.topsion.easy_daily_report.agent.subagents.ReportGeneratorAgent;
import com.topsion.easy_daily_report.application.usecase.CoordinatorOrchestrator;
import com.topsion.easy_daily_report.application.usecase.GenerateReportUseCase;
import com.topsion.easy_daily_report.application.usecase.MultiAgentOrchestrator;
import com.topsion.easy_daily_report.domain.model.DailyReport;
import com.topsion.easy_daily_report.domain.model.ReportRequest;
import com.topsion.easy_daily_report.domain.port.ReportGenerator;
import com.topsion.easy_daily_report.infrastructure.ai.tools.GitTool;
import com.topsion.easy_daily_report.infrastructure.ai.tools.JiraTool;
import com.topsion.easy_daily_report.infrastructure.ai.tools.SubAgentDelegationTool;
import com.topsion.easy_daily_report.infrastructure.rag.EmbeddingStoreReportStore;
import dev.langchain4j.community.store.embedding.duckdb.DuckDBEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 日报生成全链路自动化端到端测试（无需 LLM / Postgres / Docker / 网络）。
 * <p>
 * 边界（LLM 调用、Git、Jira）用 Mockito 桩替换以保证确定性；但向量存储用<b>真实的
 * 嵌入式 DuckDB + 真实 All-MiniLM-L6-v2</b>，因此这条流程真正验证了本轮改动的核心：
 * <ul>
 *   <li>三种模式（SINGLE / SAMPLE_MULTIPLE / COORDINATOR）的编排链路</li>
 *   <li><b>C1 数据流</b>：上游分析确实进入最终合成消息（合成器桩回显其入参，断言含分析标记）</li>
 *   <li>DuckDB 持久化 + RAG 检索回读</li>
 *   <li>fail-soft：Git 取数失败时仍产出并落库一份（含降级信息）的报告</li>
 * </ul>
 * 合成器桩采用「回显」策略：若 C1 回归（分析未进入消息），标记不会出现在报告里，测试即失败。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Daily-report pipeline end-to-end (deterministic, real DuckDB)")
class DailyReportPipelineE2ETest {

    private static final String GIT_MARKER = "GIT_MARKER_库存超卖修复";
    private static final String JIRA_MARKER = "JIRA_MARKER_支付需求";

    private static EmbeddingModel embeddingModel;

    @TempDir
    Path tmp;

    private EmbeddingStore<TextSegment> embeddingStore;
    private EmbeddingStoreReportStore reportStore;

    @Mock
    private GitTool gitTool;
    @Mock
    private JiraTool jiraTool;
    @Mock
    private GitDiffAnalyzerAgent gitAnalyzerAgent;
    @Mock
    private JiraAnalyzerAgent jiraAnalyzerAgent;
    @Mock
    private ReportGeneratorAgent reportGeneratorAgent;
    @Mock
    private CoordinatorAgent coordinatorAgent;
    @Mock
    private ReportGenerator singleReportGenerator;

    @BeforeAll
    static void loadModel() {
        // 本地 ONNX 模型，加载一次（重）
        embeddingModel = new AllMiniLmL6V2EmbeddingModel();
    }

    @BeforeEach
    void setUpStore() {
        embeddingStore = DuckDBEmbeddingStore.builder()
                .filePath(tmp.resolve("e2e.duckdb").toString())
                .tableName("report_embeddings")
                .build();
        reportStore = new EmbeddingStoreReportStore(embeddingStore, embeddingModel);
    }

    /** 合成器桩：回显最终 user message —— 用于断言分析数据确实抵达模型（C1 守卫）。 */
    private void stubSynthesizerEcho() {
        when(reportGeneratorAgent.generate(anyString()))
                .thenAnswer(inv -> "# 工作日报\n" + inv.getArgument(0, String.class));
    }

    @Test
    @DisplayName("SAMPLE_MULTIPLE: 并行分析 → 合成(含分析数据) → 落库 → 可检索")
    void sampleMultiple_endToEnd() {
        when(gitTool.getCommitDiff("abc123")).thenReturn("diff --git a/Stock.java ...");
        when(jiraTool.getJiraIssue("PROJ-1")).thenReturn("Issue: PROJ-1 ...");
        when(gitAnalyzerAgent.analyze(anyString()))
                .thenReturn("{\"technical_summary\":\"" + GIT_MARKER + "\"}");
        when(jiraAnalyzerAgent.analyze(anyString()))
                .thenReturn("{\"business_value\":\"" + JIRA_MARKER + "\"}");
        stubSynthesizerEcho();

        MultiAgentOrchestrator orchestrator = new MultiAgentOrchestrator(
                gitAnalyzerAgent, jiraAnalyzerAgent, reportGeneratorAgent,
                gitTool, jiraTool, reportStore);

        DailyReport report = orchestrator.execute(new ReportRequest("abc123", null, "PROJ-1", "./"));

        // C1: 两份分析都真正进入了合成消息
        assertThat(report.rawMarkdown()).contains(GIT_MARKER).contains(JIRA_MARKER);
        // 落库 + RAG 回读
        assertThat(reportStore.searchSimilar("库存 超卖", 1))
                .anySatisfy(text -> assertThat(text).contains(GIT_MARKER));
    }

    @Test
    @DisplayName("SINGLE: 用例生成 → 落库 → 可检索")
    void single_endToEnd() {
        when(singleReportGenerator.generate(any(ReportRequest.class)))
                .thenReturn(DailyReport.fromMarkdown("# 单Agent日报\nSINGLE_完成登录鉴权模块"));

        GenerateReportUseCase useCase = new GenerateReportUseCase(singleReportGenerator, reportStore);

        DailyReport report = useCase.execute(new ReportRequest("h1", null, "K-1", "./"));

        assertThat(report.rawMarkdown()).contains("SINGLE_完成登录鉴权模块");
        assertThat(reportStore.searchSimilar("登录 鉴权", 1))
                .anySatisfy(text -> assertThat(text).contains("SINGLE_完成登录鉴权模块"));
    }

    @Test
    @DisplayName("COORDINATOR: 委派工具按序执行 → composeFinalReport 含分析数据(C1)")
    void coordinatorDelegation_composesWithAnalyses() {
        when(gitTool.getCommitDiff("abc123")).thenReturn("diff ...");
        when(jiraTool.getJiraIssue("PROJ-1")).thenReturn("Issue ...");
        when(gitAnalyzerAgent.analyze(anyString()))
                .thenReturn("{\"technical_summary\":\"" + GIT_MARKER + "\"}");
        when(jiraAnalyzerAgent.analyze(anyString()))
                .thenReturn("{\"business_value\":\"" + JIRA_MARKER + "\"}");
        stubSynthesizerEcho();

        SubAgentDelegationTool tool = new SubAgentDelegationTool(
                gitTool, jiraTool, gitAnalyzerAgent, jiraAnalyzerAgent, reportGeneratorAgent, reportStore);

        // 模拟 Master Agent 在 ReAct 循环中的调用顺序
        String git = tool.analyzeGitChanges("abc123");
        String jira = tool.analyzeJiraIssue("PROJ-1");
        String history = tool.retrieveSimilarReports("支付 库存");
        String markdown = tool.composeFinalReport(git, jira, history, "2026-07-23");

        // C1: composeFinalReport 把两份分析真正交给了合成器
        assertThat(markdown).contains(GIT_MARKER).contains(JIRA_MARKER);
    }

    @Test
    @DisplayName("COORDINATOR: 编排层保存协调结果 → 可检索")
    void coordinatorOrchestrator_savesAndRetrievable() {
        when(coordinatorAgent.coordinate(anyString(), anyString(), anyString()))
                .thenReturn("# 协调日报\nCOORD_多智能体动态编排完成");

        CoordinatorOrchestrator orchestrator = new CoordinatorOrchestrator(coordinatorAgent, reportStore);

        DailyReport report = orchestrator.execute(new ReportRequest("abc123", null, "PROJ-1", "./"));

        assertThat(report.rawMarkdown()).contains("COORD_多智能体动态编排完成");
        assertThat(reportStore.searchSimilar("动态编排", 1))
                .anySatisfy(text -> assertThat(text).contains("COORD_多智能体动态编排完成"));
    }

    @Test
    @DisplayName("fail-soft: Git 取数异常 → 降级信息进入报告 → 仍落库")
    void failSoft_gitError_stillProducesAndPersists() {
        when(gitTool.getCommitDiff(anyString())).thenThrow(new RuntimeException("git down"));
        when(jiraTool.getJiraIssue("PROJ-1")).thenReturn("Issue ...");
        when(jiraAnalyzerAgent.analyze(anyString()))
                .thenReturn("{\"business_value\":\"" + JIRA_MARKER + "\"}");
        stubSynthesizerEcho();

        MultiAgentOrchestrator orchestrator = new MultiAgentOrchestrator(
                gitAnalyzerAgent, jiraAnalyzerAgent, reportGeneratorAgent,
                gitTool, jiraTool, reportStore);

        DailyReport report = orchestrator.execute(new ReportRequest("bad", null, "PROJ-1", "./"));

        // fail-soft：报告仍生成，含 Git 降级信息 + 正常的 Jira 分析
        assertThat(report.rawMarkdown()).contains("分析失败").contains(JIRA_MARKER);
        assertThat(reportStore.searchSimilar(JIRA_MARKER, 1)).isNotEmpty();
    }

    @Test
    @DisplayName("RAG: 多份日报入库后按语义召回最相关的一份")
    void rag_retrievesMostSimilarAcrossReports() {
        reportStore.save(DailyReport.fromMarkdown("# 日报\n支付并发下单的库存超卖问题已修复"));
        reportStore.save(DailyReport.fromMarkdown("# 日报\n完成了用户注册页面的前端表单校验"));

        List<String> hits = reportStore.searchSimilar("库存 超卖 并发 支付", 1);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0)).contains("超卖");
    }
}
