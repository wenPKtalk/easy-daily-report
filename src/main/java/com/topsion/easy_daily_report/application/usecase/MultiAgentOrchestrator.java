package com.topsion.easy_daily_report.application.usecase;

import com.topsion.easy_daily_report.agent.subagents.GitDiffAnalyzerAgent;
import com.topsion.easy_daily_report.agent.subagents.JiraAnalyzerAgent;
import com.topsion.easy_daily_report.agent.subagents.ReportGeneratorAgent;
import com.topsion.easy_daily_report.domain.model.DailyReport;
import com.topsion.easy_daily_report.domain.model.ReportRequest;
import com.topsion.easy_daily_report.infrastructure.ai.tools.GitTool;
import com.topsion.easy_daily_report.infrastructure.ai.tools.JiraTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

@RequiredArgsConstructor
@Slf4j
@Service
public class MultiAgentOrchestrator implements GenerateAgent{
    private final GitDiffAnalyzerAgent gitAnalyzerAgent;
    private final JiraAnalyzerAgent jiraAnalyzerAgent;
    private final ReportGeneratorAgent reportGeneratorAgent;
    private final GitTool gitTool;
    private final JiraTool jiraTool;

    @Override
    public DailyReport execute(ReportRequest request){
        String commitHash = request.commitHash();
        String jiraIssueKey = request.jiraIssueKey();
        String result = this.generateReport(commitHash, jiraIssueKey);
        return DailyReport.fromMarkdown(result);
    }

    /**
     * 生成工作日报（Multiple Agent 架构）
     *
     * @param commitHash Git commit hash
     * @param jiraKey Jira issue key
     * @return Markdown 格式的日报
     */
    public String generateReport(String commitHash, String jiraKey) {
        log.info("开始生成日报 使用multiple agent : commit={}, jira={}", commitHash, jiraKey);

        try {
            // 步骤1: 并行执行 Git 和 Jira 分析（利用虚拟线程）
            CompletableFuture<String> gitAnalysisFuture = CompletableFuture
                    .supplyAsync(() -> analyzeGitChanges(commitHash));

            CompletableFuture<String> jiraAnalysisFuture = CompletableFuture
                    .supplyAsync(() -> analyzeJiraIssue(jiraKey));

            // 步骤2: 等待所有分析完成
            CompletableFuture.allOf(gitAnalysisFuture, jiraAnalysisFuture).join();

            String gitAnalysisJson = gitAnalysisFuture.join();
            String jiraAnalysisJson = jiraAnalysisFuture.join();

            log.info("Git 和 Jira 分析完成");
            log.debug("Git Analysis: {}", gitAnalysisJson);
            log.debug("Jira Analysis: {}", jiraAnalysisJson);

            // 步骤3: 生成最终日报
            String todayDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

            String prompt = String.format(
                    "请基于以下分析结果生成工作日报。Jira Key: %s, Commit: %s",
                    jiraKey,
                    commitHash
            );

            String finalReport = reportGeneratorAgent.generate(
                    prompt,
                    gitAnalysisJson,
                    jiraAnalysisJson,
                    todayDate
            );

            log.info("日报生成成功，长度: {} 字符", finalReport.length());
            return finalReport;

        } catch (Exception e) {
            log.error("生成日报失败", e);
            throw new RuntimeException("日报生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 分析 Git 代码变更
     */
    private String analyzeGitChanges(String commitHash) {
        log.info("开始分析 Git Commit: {}", commitHash);

        try {
            // 调用 GitTools 获取 diff
            String diff = gitTool.getCommitDiff(commitHash);

            if (diff == null || diff.isBlank()) {
                log.warn("未找到 commit 的 diff: {}", commitHash);
                return createEmptyGitAnalysis();
            }

            // 调用 GitDiffAnalyzerAgent 分析
            String analysis = gitAnalyzerAgent.analyze(diff);
            log.info("Git 分析完成");

            return analysis;

        } catch (Exception e) {
            log.error("Git 分析失败: {}", commitHash, e);
            return createErrorGitAnalysis(e.getMessage());
        }
    }

    /**
     * 分析 Jira Issue
     */
    private String analyzeJiraIssue(String jiraKey) {
        log.info("开始分析 Jira Issue: {}", jiraKey);

        try {
            // 调用 JiraTools 获取 issue 内容
            String issueContent = jiraTool.getJiraIssue(jiraKey);

            if (issueContent == null || issueContent.isBlank()) {
                log.warn("未找到 Jira Issue: {}", jiraKey);
                return createEmptyJiraAnalysis(jiraKey);
            }

            // 调用 JiraAnalyzerAgent 分析
            String analysis = jiraAnalyzerAgent.analyze(issueContent);
            log.info("Jira 分析完成");

            return analysis;

        } catch (Exception e) {
            log.error("Jira 分析失败: {}", jiraKey, e);
            return createErrorJiraAnalysis(jiraKey, e.getMessage());
        }
    }

    /**
     * 创建空的 Git 分析结果（fallback）
     */
    private String createEmptyGitAnalysis() {
        return """
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

    /**
     * 创建错误的 Git 分析结果（fallback）
     */
    private String createErrorGitAnalysis(String errorMessage) {
        return String.format("""
        {
          "files_changed": [],
          "files_count": 0,
          "key_changes": ["分析失败: %s"],
          "technical_summary": "代码分析失败",
          "technologies_used": [],
          "impact_analysis": "无法评估",
          "potential_risks": ["请手动检查代码变更"]
        }
        """, errorMessage);
    }

    /**
     * 创建空的 Jira 分析结果（fallback）
     */
    private String createEmptyJiraAnalysis(String jiraKey) {
        return String.format("""
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
        """, jiraKey);
    }

    /**
     * 创建错误的 Jira 分析结果（fallback）
     */
    private String createErrorJiraAnalysis(String jiraKey, String errorMessage) {
        return String.format("""
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
        """, errorMessage, jiraKey);
    }
}
