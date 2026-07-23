package com.topsion.easy_daily_report.infrastructure.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embedding 模型配置（与具体向量存储无关，始终生效）。
 * <p>
 * 无论 report.store.type 选 duckdb 还是 pgvector，都复用同一个 384 维
 * All-MiniLM-L6-v2 本地 ONNX 模型，保证已存向量与新查询向量同源可比。
 */
@Configuration
public class EmbeddingModelConfig {

    @Bean
    public EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2EmbeddingModel();
    }
}
