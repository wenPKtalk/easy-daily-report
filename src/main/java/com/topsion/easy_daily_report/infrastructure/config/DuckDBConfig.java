package com.topsion.easy_daily_report.infrastructure.config;

import dev.langchain4j.community.store.embedding.duckdb.DuckDBEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * DuckDB 嵌入式向量存储配置（默认）。
 * <p>
 * 进程内运行、单文件持久化，无需任何独立数据库服务器（去掉 Postgres/Docker 依赖）。
 * {@code report.store.type} 未设置或为 {@code duckdb} 时生效；设为 {@code pgvector} 则切回 {@link PgVectorConfig}。
 * <p>
 * 注意：DuckDB 文件为本地单进程、单写者存储——每个运行实例拥有各自隔离的报告历史，
 * 适合单用户 / 本地场景；若需多进程并发写的共享知识库，请改用 pgvector。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "report.store.type", havingValue = "duckdb", matchIfMissing = true)
public class DuckDBConfig {

    @Bean
    @Lazy
    public EmbeddingStore<TextSegment> embeddingStore(
            @Value("${duckdb.file-path:./data/report_embeddings.duckdb}") String filePath,
            @Value("${duckdb.table:report_embeddings}") String tableName
    ) {
        ensureParentDir(filePath);
        log.info("初始化 DuckDB 嵌入式向量存储: file={}, table={}", filePath, tableName);
        return DuckDBEmbeddingStore.builder()
                .filePath(filePath)
                .tableName(tableName)
                .build();
    }

    private static void ensureParentDir(String filePath) {
        Path parent = Path.of(filePath).toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建 DuckDB 数据目录: " + parent, e);
        }
    }
}
