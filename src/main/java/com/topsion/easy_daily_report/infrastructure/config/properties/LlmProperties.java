package com.topsion.easy_daily_report.infrastructure.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 配置属性
 *
 * 支持灵活配置不同 AI 提供商（OpenAI、ZhipuAI、Ollama 等）
 */
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(
        /**
         * LLM 提供商类型
         * 可选值: openai-compatible (默认), ollama
         */
        Provider provider,

        /**
         * API 基础 URL
         * - ZhipuAI: https://open.bigmodel.cn/api/paas/v4/
         * - OpenAI: https://api.openai.com/v1
         * - Ollama: http://localhost:11434/v1
         */
        String baseUrl,

        /**
         * API 密钥
         */
        String apiKey,

        /**
         * 模型名称
         * - ZhipuAI: glm-4-flash, glm-4, glm-4-plus
         * - OpenAI: gpt-4o, gpt-4o-mini, gpt-4-turbo
         * - Ollama: llama3, mistral, qwen2 等
         */
        String modelName,

        /**
         * 生成温度 (0.0 - 2.0)
         * 越低越严谨，越高越创造性
         */
        Double temperature,

        /**
         * 最大生成令牌数
         */
        Integer maxTokens,

        /**
         * 请求超时时间（秒）
         */
        Integer timeoutSeconds,

        /**
         * 是否启用日志记录
         */
        Boolean logRequests,

        /**
         * 是否启用响应日志记录
         */
        Boolean logResponses
) {

    public LlmProperties {
        if (provider == null) {
            provider = Provider.OPENAI_COMPATIBLE;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://open.bigmodel.cn/api/paas/v4/";
        }
        if (apiKey == null) {
            apiKey = "demo";
        }
        if (modelName == null || modelName.isBlank()) {
            modelName = "glm-4-flash";
        }
        if (temperature == null) {
            temperature = 0.3;
        }
        if (maxTokens == null) {
            maxTokens = 2000;
        }
        if (timeoutSeconds == null) {
            timeoutSeconds = 60;
        }
        if (logRequests == null) {
            logRequests = true;
        }
        if (logResponses == null) {
            logResponses = true;
        }
    }

    /**
     * LLM 提供商枚举
     */
    public enum Provider {
        /**
         * OpenAI 兼容 API（包括 ZhipuAI、Moonshot、DeepSeek 等）
         */
        OPENAI_COMPATIBLE,

        /**
         * Ollama 本地模型
         */
        OLLAMA
    }
}
