package com.topsion.easy_daily_report.infrastructure.config;

import com.topsion.easy_daily_report.infrastructure.ai.DailyReportAgent;
import com.topsion.easy_daily_report.infrastructure.ai.tools.GitTool;
import com.topsion.easy_daily_report.infrastructure.ai.tools.JiraTool;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * LangChain4j 配置（Configuration）
 *
 * 负责组装 Agent 的所有组件：LLM + Tools + Memory + RAG
 *
 * 设计模式：
 * - Factory Method — 通过 @Bean 工厂方法创建复杂对象
 * - Builder Pattern — 使用 AiServices.builder() 构建 Agent
 * - Composite Pattern — 组合 Tools + RAG + Memory 形成完整 Agent
 */
@Configuration
public class LangChain4jConfig {

    @Bean
    public ChatMemory chatMemory(
            @Value("${langchain4j.chat-memory.max-messages:20}") int maxMessages
    ) {
        return MessageWindowChatMemory.withMaxMessages(maxMessages);
    }

    @Bean
    @Lazy
    public ContentRetriever contentRetriever(
            EmbeddingStore<TextSegment> embeddingStore,
            EmbeddingModel embeddingModel,
            @Value("${langchain4j.rag.max-results:3}") int maxResults
    ) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(maxResults)
                .build();
    }

    @Bean
    public DailyReportAgent dailyReportAgent(
            ChatModel chatModel,
            ChatMemory chatMemory,
            ContentRetriever contentRetriever,
            GitTool gitTool,
            JiraTool jiraTool
    ) {
        return AiServices.builder(DailyReportAgent.class)
                .chatModel(chatModel)
                .chatMemory(chatMemory)
                .contentRetriever(contentRetriever)
                .tools(gitTool, jiraTool)
                .build();
    }
}
