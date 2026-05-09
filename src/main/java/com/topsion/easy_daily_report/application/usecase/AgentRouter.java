package com.topsion.easy_daily_report.application.usecase;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AgentRouter {
    List<GenerateAgent> generateAgents;

    public GenerateAgent selectAgent(){
        throw new UnsupportedOperationException("AgentRouter.selectAgent not yet implemented");
    }

}
