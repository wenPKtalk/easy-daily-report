package com.topsion.easy_daily_report.infrastructure.ai.tools;

import com.topsion.easy_daily_report.application.chat.ChatSession;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Component
public class SessionContextTool {

    private final ThreadLocal<ChatSession> currentSession = new ThreadLocal<>();

    public void setCurrentSession(ChatSession session) {
        currentSession.set(session);
    }

    public ChatSession getUpdatedSession() {
        return currentSession.get();
    }

    public void clearSession() {
        currentSession.remove();
    }

    @Tool("获取当前对话会话中已提取的上下文变量（commitHash、jiraKey、repoPath 等）")
    public String getContext() {
        ChatSession session = currentSession.get();
        if (session == null) return "{}";
        return session.contextAsString();
    }

    @Tool("在当前会话上下文中更新或新增一个键值对，例如 updateContext('commitHash', 'abc123')")
    public void updateContext(String key, String value) {
        ChatSession session = currentSession.get();
        if (session == null) return;
        Map<String, String> next = new HashMap<>(session.context());
        next.put(key, value);
        currentSession.set(new ChatSession(
            session.sessionId(), session.userId(), session.currentMode(), session.modeOverridden(),
            Collections.unmodifiableMap(next), session.history(),
            session.createdAt(), LocalDateTime.now()
        ));
    }
}
