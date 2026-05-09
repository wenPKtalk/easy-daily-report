package com.topsion.easy_daily_report.infrastructure.config;

import com.topsion.easy_daily_report.agent.supervisor.SupervisorAgent;
import com.topsion.easy_daily_report.infrastructure.ai.tools.GitTool;
import com.topsion.easy_daily_report.infrastructure.ai.tools.SessionContextTool;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SupervisorConfig {

    @Bean
    public SupervisorAgent supervisorAgent(
            ChatModel chatModel,
            SessionContextTool sessionContextTool,
            GitTool gitTool) {

        return AiServices.builder(SupervisorAgent.class)
                .chatModel(chatModel)
                .tools(sessionContextTool, gitTool)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .build();
    }
}
