package com.topsion.easy_daily_report.application.chat;

import com.topsion.easy_daily_report.agent.supervisor.SupervisorDecision;
import com.topsion.easy_daily_report.application.usecase.AgentLevel;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

public record ChatSession(
    String sessionId, String userId, AgentLevel currentMode, boolean modeOverridden,
    Map<String, String> context, List<ConversationTurn> history,
    LocalDateTime createdAt, LocalDateTime lastActiveAt
) {
    public ChatSession withMode(AgentLevel mode, boolean overridden) {
        return new ChatSession(sessionId, userId, mode, overridden, context, history, createdAt, LocalDateTime.now());
    }

    public ChatSession updateContext(SupervisorDecision decision) {
        Map<String, String> next = new HashMap<>(context);
        if (decision.commitHash() != null) next.put("commitHash", decision.commitHash());
        if (decision.jiraKey() != null) next.put("jiraKey", decision.jiraKey());
        return new ChatSession(sessionId, userId, currentMode, modeOverridden,
            Collections.unmodifiableMap(next), history, createdAt, LocalDateTime.now());
    }

    public ChatSession appendTurn(String role, String content) {
        List<ConversationTurn> next = new ArrayList<>(history);
        next.add(new ConversationTurn(role, content, LocalDateTime.now()));
        return new ChatSession(sessionId, userId, currentMode, modeOverridden,
            context, Collections.unmodifiableList(next), createdAt, LocalDateTime.now());
    }

    public String contextAsString() {
        if (context.isEmpty()) return "{}";
        return context.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining(", ", "{", "}"));
    }
}
