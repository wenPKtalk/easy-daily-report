package com.topsion.easy_daily_report.infrastructure.config;

import com.topsion.easy_daily_report.agent.subagents.GitDiffAnalyzerAgent;
import com.topsion.easy_daily_report.agent.subagents.JiraAnalyzerAgent;
import com.topsion.easy_daily_report.agent.subagents.ReportGeneratorAgent;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Multiple Agent 配置类
 * 负责创建和配置各个 Sub-Agent。
 * <p>
 * 三个子 agent 都是「一次输入 → 一次输出」的无状态转换：
 * - 不绑定 chatMemory：每次调用相互独立，避免作为 Spring 单例时跨次生成串味
 *   （LangChain4j 在单次调用内部自行维护工具循环消息，无需 chatMemory）。
 * - 分析类 agent 不绑定 @Tool：diff / issue 已由 {@code MultiAgentOrchestrator} 预取并作为
 *   user message 传入，绑定工具属于死接线。
 */
@Slf4j
@Configuration
public class MultiAgentConfig {

    @Bean
    public GitDiffAnalyzerAgent gitDiffAnalyzerAgent(ChatModel chatModel) {
        return AiServices.builder(GitDiffAnalyzerAgent.class)
                .chatModel(chatModel)
                .build();
    }

    @Bean
    public JiraAnalyzerAgent jiraAnalyzerAgent(ChatModel chatModel) {
        return AiServices.builder(JiraAnalyzerAgent.class)
                .chatModel(chatModel)
                .build();
    }

    @Bean
    public ReportGeneratorAgent reportGeneratorAgent(ChatModel chatModel) {

        log.info("初始化 ReportGeneratorAgent");

        return AiServices.builder(ReportGeneratorAgent.class)
                .chatModel(chatModel)
                .build();
    }
}
