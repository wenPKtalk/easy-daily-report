package com.topsion.easy_daily_report.application.chat;

import com.topsion.easy_daily_report.agent.supervisor.SupervisorAgent;
import com.topsion.easy_daily_report.agent.supervisor.SupervisorDecision;
import com.topsion.easy_daily_report.application.usecase.AgentLevel;
import com.topsion.easy_daily_report.application.usecase.AgentRouter;
import com.topsion.easy_daily_report.domain.model.DailyReport;
import com.topsion.easy_daily_report.domain.model.ReportRequest;
import com.topsion.easy_daily_report.infrastructure.ai.tools.SessionContextTool;
import com.topsion.easy_daily_report.infrastructure.chat.ChatSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ChatOrchestrator {

    private final SupervisorAgent supervisorAgent;
    private final AgentRouter agentRouter;
    private final SessionContextTool sessionContextTool;
    private final ChatSessionRepository sessionRepository;
    private final String defaultRepoPath;

    public ChatOrchestrator(
            SupervisorAgent supervisorAgent,
            AgentRouter agentRouter,
            SessionContextTool sessionContextTool,
            ChatSessionRepository sessionRepository,
            @Value("${git.default-repo-path:./}") String defaultRepoPath) {

        this.supervisorAgent = supervisorAgent;
        this.agentRouter = agentRouter;
        this.sessionContextTool = sessionContextTool;
        this.sessionRepository = sessionRepository;
        this.defaultRepoPath = defaultRepoPath;
    }

    public String handleInput(String userInput, ChatSession session) {
        sessionContextTool.setCurrentSession(session);

        SupervisorDecision decision = supervisorAgent.decide(
            userInput, session.contextAsString()
        );

        ChatSession updatedSession = sessionContextTool.getUpdatedSession();

        return switch (decision.intent()) {
            case FOLLOW_UP -> decision.directResponse();
            case CLARIFY -> decision.clarifyQuestion();
            case GENERATE_REPORT -> executeReport(decision, updatedSession);
            case MODE_SWITCH -> "Mode switch handled by caller.";
        };
    }

    private String executeReport(SupervisorDecision decision, ChatSession session) {
        AgentLevel level = session.modeOverridden()
            ? session.currentMode()
            : decision.agentLevel();

        String commitHash = decision.commitHash() != null
            ? decision.commitHash()
            : session.context().get("commitHash");
        String jiraKey = decision.jiraKey() != null
            ? decision.jiraKey()
            : session.context().get("jiraKey");
        String repoPath = session.context().getOrDefault("repoPath", defaultRepoPath);

        ReportRequest request = new ReportRequest(commitHash, null, jiraKey, repoPath);
        DailyReport report = agentRouter.route(level).execute(request);
        return report.rawMarkdown();
    }
}
