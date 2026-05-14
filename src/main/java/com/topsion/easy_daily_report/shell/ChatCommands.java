package com.topsion.easy_daily_report.shell;

import com.topsion.easy_daily_report.application.chat.ChatOrchestrator;
import com.topsion.easy_daily_report.application.chat.ChatSession;
import com.topsion.easy_daily_report.application.usecase.AgentLevel;
import com.topsion.easy_daily_report.infrastructure.chat.ChatSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class ChatCommands {

    private final ChatOrchestrator chatOrchestrator;
    private final ChatSessionRepository sessionRepository;
    private final ObjectProvider<LineReader> lineReaderProvider;

    public ChatCommands(
            ChatOrchestrator chatOrchestrator,
            ChatSessionRepository sessionRepository,
            ObjectProvider<LineReader> lineReaderProvider) {
        this.chatOrchestrator = chatOrchestrator;
        this.sessionRepository = sessionRepository;
        this.lineReaderProvider = lineReaderProvider;
    }

    @Command(value = "chat")
    public void chat() {
        startChatLoop(null);
    }

    @Command(value = "@chat")
    public void atChat() {
        startChatLoop(null);
    }

    void startChatLoop(AgentLevel forcedMode) {
        LineReader lineReader = lineReaderProvider.getIfAvailable();
        if (lineReader == null) {
            throw new IllegalStateException("LineReader is not available in this environment");
        }

        String userId = System.getProperty("user.name", "unknown");
        ChatSession session = resolveSession(userId, forcedMode);
        printWelcomeBanner(session);

        while (true) {
            String input;
            try {
                input = lineReader.readLine("you> ");
            } catch (UserInterruptException e) {
                saveAndExit(session);
                return;
            }

            if (input == null || input.isBlank()) continue;

            if (isBuiltinCommand(input)) {
                if (input.trim().equals("/exit")) {
                    saveAndExit(session);
                    return;
                }
                String response = buildBuiltinResponse(input, session);
                ChatSession updatedSession = handleBuiltinCommand(input, session);
                if (updatedSession != session) {
                    try {
                        sessionRepository.save(updatedSession);
                    } catch (Exception e) {
                        log.error("Save session happened error", e);
                        log.warn("Failed to persist session after builtin command: {}", e.getMessage());
                    }
                }
                session = updatedSession;
                System.out.println("assistant> " + response);
            } else {
                String response = chatOrchestrator.handleInput(input, session);
                System.out.println("\nassistant> " + response + "\n");
                session = session
                    .appendTurn("user", input)
                    .appendTurn("assistant", response);
                try {
                    sessionRepository.save(session);
                } catch (Exception e) {
                    log.warn("Failed to persist session turn, continuing in-memory: {}", e.getMessage());
                }
            }
        }
    }

    public boolean isBuiltinCommand(String input) {
        return input != null && input.startsWith("/");
    }

    public ChatSession handleBuiltinCommand(String input, ChatSession session) {
        String[] parts = input.trim().split("\\s+");
        return switch (parts[0]) {
            case "/mode" -> handleModeSwitch(parts, session);
            case "/clear" -> clearContext(session);
            case "/new" -> createNewSession(session.userId());
            default -> session;
        };
    }

    private String buildBuiltinResponse(String input, ChatSession session) {
        String[] parts = input.trim().split("\\s+");
        return switch (parts[0]) {
            case "/mode" -> buildModeResponse(parts);
            case "/context" -> "当前上下文: " + session.contextAsString();
            case "/history" -> buildHistoryOutput(session);
            case "/clear" -> "✅ 上下文已清空，保留会话。";
            case "/new" -> "✅ 已创建新会话。";
            case "/help" -> buildHelp();
            case "/exit" -> "会话已保存。下次输入 chat 可继续。";
            default -> "未知命令，输入 /help 查看可用命令。";
        };
    }

    private ChatSession handleModeSwitch(String[] parts, ChatSession session) {
        if (parts.length < 2) return session;
        return switch (parts[1]) {
            case "single" -> session.withMode(AgentLevel.SINGLE, true);
            case "multi" -> session.withMode(AgentLevel.SAMPLE_MULTIPLE, true);
            case "auto" -> session.withMode(session.currentMode(), false);
            default -> session;
        };
    }

    private String buildModeResponse(String[] parts) {
        if (parts.length < 2) return "用法: /mode single|multi|auto";
        return switch (parts[1]) {
            case "single" -> "✅ 已切换到单 Agent 模式（手动覆盖）";
            case "multi" -> "✅ 已切换到多 Agent 模式（手动覆盖）";
            case "auto" -> "✅ 已恢复 Supervisor 自动路由";
            default -> "未知模式: " + parts[1] + "，可选: single | multi | auto";
        };
    }

    private ChatSession clearContext(ChatSession session) {
        return new ChatSession(
            session.sessionId(), session.userId(), session.currentMode(), session.modeOverridden(),
            Map.of(), session.history(), session.createdAt(), LocalDateTime.now()
        );
    }

    private ChatSession createNewSession(String userId) {
        return new ChatSession(
            UUID.randomUUID().toString(), userId,
            AgentLevel.SINGLE, false,
            Map.of(), List.of(), LocalDateTime.now(), LocalDateTime.now()
        );
    }

    private String buildHistoryOutput(ChatSession session) {
        if (session.history().isEmpty()) return "（暂无对话记录）";
        int start = Math.max(0, session.history().size() - 10);
        StringBuilder sb = new StringBuilder();
        session.history().subList(start, session.history().size())
            .forEach(t -> sb.append(t.role()).append("> ").append(t.content()).append("\n"));
        return sb.toString();
    }

    private ChatSession resolveSession(String userId, AgentLevel forcedMode) {
        ChatSession session = sessionRepository.findActiveSession(userId)
            .orElseGet(() -> new ChatSession(
                UUID.randomUUID().toString(), userId,
                AgentLevel.SINGLE, false,
                Map.of(), List.of(), LocalDateTime.now(), LocalDateTime.now()
            ));
        if (forcedMode != null) {
            session = session.withMode(forcedMode, true);
        }
        return session;
    }

    private void printWelcomeBanner(ChatSession session) {
        boolean isResumed = !session.history().isEmpty() || !session.context().isEmpty();
        String info = isResumed
            ? "已恢复上次会话（" + session.lastActiveAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "）"
            : "新会话已创建";
        System.out.printf("""
            ╔══════════════════════════════════════════════════╗
            ║  进入对话模式 · 输入 /help 查看可用命令          ║
            ║  %-48s║
            ╚══════════════════════════════════════════════════╝
            %n""", info);
    }

    private void saveAndExit(ChatSession session) {
        try {
            sessionRepository.save(session);
        } catch (Exception e) {
            log.warn("Failed to persist session on exit: {}", e.getMessage());
        }
        System.out.println("assistant> 会话已保存。下次输入 chat 可继续。");
    }

    private String buildHelp() {
        return """
            /mode single    — 强制单 Agent 模式
            /mode multi     — 强制多 Agent 模式
            /mode auto      — 恢复 Supervisor 自动路由
            /context        — 显示当前已提取的上下文变量
            /clear          — 清空上下文，保留 session
            /new            — 创建全新 session
            /history        — 显示最近 10 条对话
            /exit           — 保存并退出 chat 模式
            /help           — 显示此帮助""";
    }
}
