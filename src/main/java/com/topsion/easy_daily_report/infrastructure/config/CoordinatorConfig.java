package com.topsion.easy_daily_report.infrastructure.config;

import com.topsion.easy_daily_report.agent.coordinator.CoordinatorAgent;
import com.topsion.easy_daily_report.infrastructure.ai.tools.SubAgentDelegationTool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * COORDINATOR_AGENT 装配。
 * <p>
 * 关键设计：
 * - 不绑定 chatMemory：一次 coordinate() 调用即完成一份日报，LangChain4j 会在单次调用内部
 *   维护工具循环消息；作为单例若共享一个 MessageWindowChatMemory 反而会让相邻两次生成串味。
 * - 仅注入 {@link SubAgentDelegationTool}，其内部的 4 个 @Tool 方法会被 AiServices 自动扫描注册
 * - 不注入 ContentRetriever：RAG 已通过 retrieveSimilarReports 工具暴露，避免双通道
 */
@Slf4j
@Configuration
public class CoordinatorConfig {

    @Bean
    public CoordinatorAgent coordinatorAgent(
            ChatModel chatModel,
            SubAgentDelegationTool delegationTool
    ) {
        log.info("初始化 CoordinatorAgent");
        return AiServices.builder(CoordinatorAgent.class)
                .chatModel(chatModel)
                .tools(delegationTool)
                .build();
    }
}
