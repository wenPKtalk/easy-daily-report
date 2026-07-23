package com.topsion.easy_daily_report.infrastructure.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * PGVector 向量存储配置（Configuration）。
 * <p>
 * 仅当 {@code report.store.type=pgvector} 时生效；默认走 {@link DuckDBConfig}（进程内、无需服务器）。
 * 选它意味着需要一个独立运行的 PostgreSQL(pgvector) 服务（见 docker-compose）。
 *
 * 设计模式：Factory Method — 通过 @Bean 工厂方法创建基础设施对象
 */
@Configuration
@ConditionalOnProperty(name = "report.store.type", havingValue = "pgvector")
public class PgVectorConfig {

    @Bean
    @Lazy
    public EmbeddingStore<TextSegment> embeddingStore(
            @Value("${pgvector.host:localhost}") String host,
            @Value("${pgvector.port:5432}") int port,
            @Value("${pgvector.database:daily_report}") String database,
            @Value("${pgvector.user:postgres}") String user,
            @Value("${pgvector.password:123456}") String password,
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
