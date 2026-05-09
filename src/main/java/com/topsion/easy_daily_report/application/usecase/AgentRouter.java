package com.topsion.easy_daily_report.application.usecase;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentRouter {

    private final GenerateReportUseCase singleAgent;
    private final MultiAgentOrchestrator multiAgent;

    public GenerateAgent route(AgentLevel level) {
        return switch (level) {
            case SINGLE -> singleAgent;
            case SAMPLE_MULTIPLE -> multiAgent;
            case COORDINATOR_AGENT ->
                throw new UnsupportedOperationException("COORDINATOR_AGENT is reserved for MVP2");
        };
    }
}
