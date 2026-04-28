package com.topsion.easy_daily_report.domain.port;

import com.topsion.easy_daily_report.domain.model.JiraIssueInfo;

/**
 * Jira 操作端口（Port）
 * 依赖倒置：Domain 定义接口，Infrastructure 实现
 */
public interface JiraPort {

    JiraIssueInfo getIssue(String issueKey);
}
