package com.topsion.easy_daily_report.infrastructure.ai.tools;

import com.topsion.easy_daily_report.domain.model.JiraIssueInfo;
import com.topsion.easy_daily_report.domain.port.JiraPort;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Jira 工具（LangChain4j @Tool）
 * 供 ReAct Agent 自主调用，获取 Jira Issue 信息
 *
 * 设计模式：Adapter + Facade — 将 JiraPort 封装为 Agent 可调用的 Tool
 */
@Component
@RequiredArgsConstructor
public class JiraTool {

    private final JiraPort jiraPort;

    @Tool("根据 Jira Issue Key 获取该 Issue 的详细信息，包括标题、描述、状态、优先级等")
    public String getJiraIssue(String issueKey) {
        JiraIssueInfo issue = jiraPort.getIssue(issueKey);
        return """
                Issue: %s
                Summary: %s
                Status: %s
                Priority: %s
                Assignee: %s
                Description: %s
                """.formatted(
                issue.issueKey(),
                issue.summary(),
                issue.status(),
                issue.priority(),
                issue.assignee(),
                issue.description()
        );
    }
}
