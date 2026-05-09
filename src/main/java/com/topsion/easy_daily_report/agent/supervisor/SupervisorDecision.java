package com.topsion.easy_daily_report.agent.supervisor;

import com.topsion.easy_daily_report.application.usecase.AgentLevel;

public record SupervisorDecision(
    Intent intent, AgentLevel agentLevel, String commitHash, String jiraKey,
    String directResponse, String clarifyQuestion
) {}
