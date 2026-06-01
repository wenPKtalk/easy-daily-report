package com.topsion.easy_daily_report.infrastructure.config;

import com.topsion.easy_daily_report.agent.coordinator.CoordinatorAgent;
import com.topsion.easy_daily_report.infrastructure.ai.tools.SubAgentDelegationTool;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * COORDINATOR_AGENT 装配。
 * <p>
 * 关键设计：
 * - 不共享 chatMemory bean，使用 Coordinator 自己的窗口（避免与 SINGLE 路径的 RAG 对话上下文交叉）
 * - 仅注入 {@link SubAgentDelegationTool}，其内部的 4 个 @Tool 方法会被 AiServices 自动扫描注册
 * - 不注入 ContentRetriever：RAG 已通过 retrieveSimilarReports 工具暴露，避免双通道
 */
@Slf4j
@Configuration
public class CoordinatorConfig {

    @Bean
    public CoordinatorAgent coordinatorAgent(
            ChatModel chatModel,
            SubAgentDelegationTool delegationTool,
            @Value("${langchain4j.coordinator.chat-memory.max-messages:20}") int maxMessages
    ) {
        log.info("初始化 CoordinatorAgent (chatMemory.maxMessages={})", maxMessages);
        return AiServices.builder(CoordinatorAgent.class)
                .chatModel(chatModel)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(maxMessages))
                .tools(delegationTool)
                .build();
    }
}
