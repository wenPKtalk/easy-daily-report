package com.topsion.easy_daily_report.infrastructure.ai;

import com.topsion.easy_daily_report.domain.model.DailyReport;
import com.topsion.easy_daily_report.domain.model.ReportRequest;
import com.topsion.easy_daily_report.domain.port.ReportGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Agent 驱动的日报生成器（Adapter）
 * 实现 ReportGenerator 端口，委托给 LangChain4j DailyReportAgent
 *
 * 设计模式：
 * - Adapter Pattern — 将 LangChain4j Agent 适配为 Domain 端口
 * - Strategy Pattern — ReportGenerator 接口允许替换不同生成策略
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentReportGenerator implements ReportGenerator {

    private final DailyReportAgent agent;

    @Override
    public DailyReport generate(ReportRequest request) {
        log.info("Agent 开始生成日报...");

        String userMessage = buildUserMessage(request);
        String markdown = agent.generateReport(userMessage);

        log.info("Agent 日报生成完成");
        return DailyReport.fromMarkdown(markdown);
    }

    private String buildUserMessage(ReportRequest request) {
        var sb = new StringBuilder("请生成工作日报。\n");

        if (request.commitHash() != null && !request.commitHash().isBlank()) {
            sb.append("请分析 Git Commit: ").append(request.commitHash()).append("\n");
        }

        if (request.hasCommitRange()) {
            sb.append("Commit 范围: ").append(request.commitRange()).append("\n");
        }

        if (request.hasJiraIssue()) {
            sb.append("关联 Jira Issue: ").append(request.jiraIssueKey()).append("\n");
        }

        return sb.toString();
    }
}
