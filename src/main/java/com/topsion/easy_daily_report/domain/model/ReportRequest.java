package com.topsion.easy_daily_report.domain.model;

import java.util.List;

/**
 * 日报生成请求（Value Object）
 * 封装用户的命令输入参数
 */
public record ReportRequest(
        String commitHash,
        String commitRange,
        String jiraIssueKey,
        String repositoryPath
) {
    public boolean hasCommitRange() {
        return commitRange != null && !commitRange.isBlank();
    }

    public boolean hasJiraIssue() {
        return jiraIssueKey != null && !jiraIssueKey.isBlank();
    }
}
