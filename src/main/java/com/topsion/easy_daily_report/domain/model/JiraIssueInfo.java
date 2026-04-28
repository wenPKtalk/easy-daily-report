package com.topsion.easy_daily_report.domain.model;

/**
 * Jira Issue 信息（Value Object）
 * 封装从 Jira 获取的 Issue 核心数据
 */
public record JiraIssueInfo(
        String issueKey,
        String summary,
        String description,
        String status,
        String assignee,
        String priority
) {
    public String businessContext() {
        return "[%s] %s — %s".formatted(issueKey, summary, description);
    }
}
