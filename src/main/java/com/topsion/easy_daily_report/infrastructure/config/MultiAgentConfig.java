package com.topsion.easy_daily_report.infrastructure.config;

import com.topsion.easy_daily_report.agent.subagents.GitDiffAnalyzerAgent;
import com.topsion.easy_daily_report.agent.subagents.JiraAnalyzerAgent;
import com.topsion.easy_daily_report.agent.subagents.ReportGeneratorAgent;
import com.topsion.easy_daily_report.infrastructure.ai.tools.GitTool;
import com.topsion.easy_daily_report.infrastructure.ai.tools.JiraTool;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Multiple Agent 配置类
 * 负责创建和配置各个 Sub-Agent
 */
@Slf4j
@Configuration
public class MultiAgentConfig {

    @Bean
    public GitDiffAnalyzerAgent gitDiffAnalyzerAgent(ChatModel chatModel,
                                                     GitTool gitTool) {
        return AiServices.builder(GitDiffAnalyzerAgent.class)
                .chatModel(chatModel)
                .tools(gitTool)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();
    }

    @Bean
    public JiraAnalyzerAgent jiraAnalyzerAgent(ChatModel chatModel,
                                               JiraTool jiraTool) {
        return AiServices.builder(JiraAnalyzerAgent.class)
                .chatModel(chatModel)
                .tools(jiraTool)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();
    }

    @Bean
    public ReportGeneratorAgent reportGeneratorAgent(
            ChatModel chatModel) {

        log.info("初始化 ReportGeneratorAgent");

        return AiServices.builder(ReportGeneratorAgent.class)
                .chatModel(chatModel)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .build();
    }
}
