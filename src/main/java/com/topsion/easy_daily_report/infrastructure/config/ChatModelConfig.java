package com.topsion.easy_daily_report.infrastructure.config;

import com.topsion.easy_daily_report.infrastructure.config.properties.LlmProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * ChatModel 配置
 *
 * 根据配置灵活创建不同的 LLM 客户端
 * 支持: OpenAI 兼容 API (ZhipuAI, OpenAI, Moonshot等) / Ollama
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class ChatModelConfig {

    @Bean
    public ChatModel chatModel(LlmProperties llmProperties) {
        return switch (llmProperties.provider()) {
            case OPENAI_COMPATIBLE -> createOpenAiCompatibleModel(llmProperties);
            case OLLAMA -> createOllamaModel(llmProperties);
        };
    }

    /**
     * 创建 OpenAI 兼容模型客户端
     * 支持: ZhipuAI, OpenAI, Moonshot, DeepSeek, Azure OpenAI 等
     */
    private ChatModel createOpenAiCompatibleModel(LlmProperties props) {
        return OpenAiChatModel.builder()
                .baseUrl(props.baseUrl())
                .apiKey(props.apiKey())
                .modelName(props.modelName())
                .temperature(props.temperature())
                .maxTokens(props.maxTokens())
                .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                .logRequests(props.logRequests())
                .logResponses(props.logResponses())
                .build();
    }

    /**
     * 创建 Ollama 本地模型客户端
     */
    private ChatModel createOllamaModel(LlmProperties props) {
        return OllamaChatModel.builder()
                .baseUrl(props.baseUrl())
                .modelName(props.modelName())
                .temperature(props.temperature())
                .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                .build();
    }
}
