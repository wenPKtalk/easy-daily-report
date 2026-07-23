package com.topsion.easy_daily_report.infrastructure.config;

import com.topsion.easy_daily_report.infrastructure.ai.DailyReportAgent;
import com.topsion.easy_daily_report.infrastructure.ai.tools.GitTool;
import com.topsion.easy_daily_report.infrastructure.ai.tools.JiraTool;
import dev.langchain4j.data.segment.TextSegment;
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
 * 负责组装 Agent 的所有组件：LLM + Tools + RAG
 *
 * 设计模式：
 * - Factory Method — 通过 @Bean 工厂方法创建复杂对象
 * - Builder Pattern — 使用 AiServices.builder() 构建 Agent
 * - Composite Pattern — 组合 Tools + RAG 形成完整 Agent
 *
 * 说明：不绑定 chatMemory。每次 generateReport() 即完成一份日报，LangChain4j 在单次调用内部
 * 维护工具循环消息；若作为单例共享一个 MessageWindowChatMemory，相邻两次生成会相互串味。
 */
@Configuration
public class LangChain4jConfig {

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
            ContentRetriever contentRetriever,
            GitTool gitTool,
            JiraTool jiraTool
    ) {
        return AiServices.builder(DailyReportAgent.class)
                .chatModel(chatModel)
                .contentRetriever(contentRetriever)
                .tools(gitTool, jiraTool)
                .build();
    }
}
