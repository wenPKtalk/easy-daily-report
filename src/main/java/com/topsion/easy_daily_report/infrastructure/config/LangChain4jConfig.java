package com.topsion.easy_daily_report.infrastructure.config;

import com.topsion.easy_daily_report.infrastructure.ai.DailyReportAgent;
import com.topsion.easy_daily_report.infrastructure.ai.tools.GitTool;
import com.topsion.easy_daily_report.infrastructure.ai.tools.JiraTool;
import com.topsion.easy_daily_report.infrastructure.ai.tools.SimilarReportsTool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j 配置（Configuration）
 *
 * 负责组装 SINGLE 模式的 Agent：LLM + Tools（Git / Jira / 历史日报检索）
 *
 * 设计模式：
 * - Factory Method — 通过 @Bean 工厂方法创建复杂对象
 * - Builder Pattern — 使用 AiServices.builder() 构建 Agent
 *
 * 说明：
 * - 不绑定 chatMemory：每次 generateReport() 即完成一份日报，LangChain4j 在单次调用内部维护工具循环消息。
 * - RAG 以 @Tool（{@link SimilarReportsTool}）暴露，而非 eager ContentRetriever：
 *   旧的 eager 方式在 Agent 调用工具前就用「commit hash + jira key」检索，query 语义近乎为空；
 *   改为工具后由 LLM 在拿到 diff / issue 后用有意义的短句检索（与 COORDINATOR 一致）。
 */
@Configuration
public class LangChain4jConfig {

    @Bean
    public DailyReportAgent dailyReportAgent(
            ChatModel chatModel,
            GitTool gitTool,
            JiraTool jiraTool,
            SimilarReportsTool similarReportsTool
    ) {
        return AiServices.builder(DailyReportAgent.class)
                .chatModel(chatModel)
                .tools(gitTool, jiraTool, similarReportsTool)
                .build();
    }
}
