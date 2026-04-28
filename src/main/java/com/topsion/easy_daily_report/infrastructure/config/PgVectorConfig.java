package com.topsion.easy_daily_report.infrastructure.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PGVector 配置（Configuration）
 *
 * 负责创建 Embedding 模型和向量存储
 *
 * 设计模式：Factory Method — 通过 @Bean 工厂方法创建基础设施对象
 */
@Configuration
public class PgVectorConfig {

    @Bean
    public EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2EmbeddingModel();
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(
            @Value("${pgvector.host:localhost}") String host,
            @Value("${pgvector.port:5432}") int port,
            @Value("${pgvector.database:daily_report}") String database,
            @Value("${pgvector.user:postgres}") String user,
            @Value("${pgvector.password:postgres}") String password,
            @Value("${pgvector.table:report_embeddings}") String table,
            @Value("${pgvector.dimension:384}") int dimension
    ) {
        return PgVectorEmbeddingStore.builder()
                .host(host)
                .port(port)
                .database(database)
                .user(user)
                .password(password)
                .table(table)
                .dimension(dimension)
                .createTable(true)
                .build();
    }
}
