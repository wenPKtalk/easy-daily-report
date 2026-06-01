package com.topsion.easy_daily_report.infrastructure.ai.tools;

import com.topsion.easy_daily_report.agent.subagents.GitDiffAnalyzerAgent;
import com.topsion.easy_daily_report.agent.subagents.JiraAnalyzerAgent;
import com.topsion.easy_daily_report.agent.subagents.ReportGeneratorAgent;
import com.topsion.easy_daily_report.domain.port.ReportStore;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * COORDINATOR_AGENT 的 DelegationTool：把 sub-agent / RAG 端口包装为 @Tool 方法，
 * 供 Master CoordinatorAgent 通过 ReAct 循环按需调用。
 * <p>
 * Fail-soft 约定：分析类工具在底层异常时返回降级 JSON，让 Coordinator 自主判断；
 * 仅 {@link #composeFinalReport} 失败上浮（终态失败必须暴露）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubAgentDelegationTool {

    private static final int DEFAULT_RAG_MAX_RESULTS = 3;

    private final GitTool gitTool;
    private final JiraTool jiraTool;
    private final GitDiffAnalyzerAgent gitDiffAnalyzerAgent;
    private final JiraAnalyzerAgent jiraAnalyzerAgent;
    private final ReportGeneratorAgent reportGeneratorAgent;
    private final ReportStore reportStore;

    @Tool("分析指定 commit 的代码变更，返回 JSON 格式的技术分析（包含变更文件、关键变更、技术摘要、影响范围、潜在风险）")
    public String analyzeGitChanges(String commitHash) {
        log.info("[Coordinator/Tool] analyzeGitChanges commit={}", commitHash);
        try {
            String diff = gitTool.getCommitDiff(commitHash);
            if (diff == null || diff.isBlank()) {
                log.warn("[Coordinator/Tool] commit diff 为空: {}", commitHash);
                return EMPTY_GIT_ANALYSIS;
            }
            return gitDiffAnalyzerAgent.analyze(diff);
        } catch (Exception e) {
            log.error("[Coordinator/Tool] Git 分析失败 commit={}", commitHash, e);
            return errorGitAnalysis(e.getMessage());
        }
    }

    @Tool("分析 Jira Issue 的业务需求，返回 JSON 格式的业务分析（包含 issue 类型、优先级、业务背景、需求点、验收标准、业务价值）")
    public String analyzeJiraIssue(String jiraKey) {
        log.info("[Coordinator/Tool] analyzeJiraIssue jira={}", jiraKey);
        try {
            String issueContent = jiraTool.getJiraIssue(jiraKey);
            if (issueContent == null || issueContent.isBlank()) {
                log.warn("[Coordinator/Tool] Jira issue 为空: {}", jiraKey);
                return emptyJiraAnalysis(jiraKey);
            }
            return jiraAnalyzerAgent.analyze(issueContent);
        } catch (Exception e) {
            log.error("[Coordinator/Tool] Jira 分析失败 jira={}", jiraKey, e);
            return errorJiraAnalysis(jiraKey, e.getMessage());
        }
    }

    @Tool("检索与查询语义相似的历史日报，用作生成新日报时的参考上下文；查询为空或检索失败时返回空字符串")
    public String retrieveSimilarReports(String query) {
        log.info("[Coordinator/Tool] retrieveSimilarReports query={}", query);
        try {
            if (query == null || query.isBlank()) {
                return "";
            }
            List<String> similar = reportStore.searchSimilar(query, DEFAULT_RAG_MAX_RESULTS);
            if (similar.isEmpty()) {
                return "";
            }
            return String.join("\n---\n", similar);
        } catch (Exception e) {
            log.warn("[Coordinator/Tool] RAG 检索失败，继续无历史上下文", e);
            return "";
        }
    }

    @Tool("整合 Git 分析、Jira 分析与历史上下文，生成最终的 Markdown 工作日报；这是流程的终态工具，失败将直接上浮")
    public String composeFinalReport(
            String gitAnalysisJson,
            String jiraAnalysisJson,
            String historicalContext,
            String todayDate
    ) {
        log.info("[Coordinator/Tool] composeFinalReport todayDate={}, historyLen={}",
                todayDate, historicalContext == null ? 0 : historicalContext.length());
        String prompt = buildReportPrompt(historicalContext);
        return reportGeneratorAgent.generate(
                prompt,
                nullSafe(gitAnalysisJson),
                nullSafe(jiraAnalysisJson),
                nullSafe(todayDate)
        );
    }

    private static String buildReportPrompt(String historicalContext) {
        if (historicalContext == null || historicalContext.isBlank()) {
            return "请基于提供的 Git 与 Jira 分析结果生成结构化工作日报。";
        }
        return """
                请基于提供的 Git 与 Jira 分析结果生成结构化工作日报。

                可参考的历史相似日报片段（仅供风格与模式参考，不要直接抄录）：
                %s
                """.formatted(historicalContext);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String errorGitAnalysis(String errorMessage) {
        return """
                {
                  "files_changed": [],
                  "files_count": 0,
                  "key_changes": ["分析失败: %s"],
                  "technical_summary": "代码分析失败",
                  "technologies_used": [],
                  "impact_analysis": "无法评估",
                  "potential_risks": ["请手动检查代码变更"]
                }
                """.formatted(safeJson(errorMessage));
    }

    private static String emptyJiraAnalysis(String jiraKey) {
        return """
                {
                  "issue_type": "Unknown",
                  "priority": "Medium",
                  "business_context": "未找到 Issue: %s",
                  "user_story": "无",
                  "key_requirements": [],
                  "acceptance_criteria": [],
                  "business_value": "未知",
                  "stakeholders": []
                }
                """.formatted(safeJson(jiraKey));
    }

    private static String errorJiraAnalysis(String jiraKey, String errorMessage) {
        return """
                {
                  "issue_type": "Error",
                  "priority": "Unknown",
                  "business_context": "分析失败: %s",
                  "user_story": "%s",
                  "key_requirements": [],
                  "acceptance_criteria": [],
                  "business_value": "未知",
                  "stakeholders": []
                }
                """.formatted(safeJson(errorMessage), safeJson(jiraKey));
    }

    private static String safeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final String EMPTY_GIT_ANALYSIS = """
            {
              "files_changed": [],
              "files_count": 0,
              "key_changes": ["未找到代码变更"],
              "technical_summary": "无代码变更",
              "technologies_used": [],
              "impact_analysis": "无影响",
              "potential_risks": []
            }
            """;
}
