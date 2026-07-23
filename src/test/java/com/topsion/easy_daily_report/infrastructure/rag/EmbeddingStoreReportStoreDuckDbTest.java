package com.topsion.easy_daily_report.infrastructure.rag;

import com.topsion.easy_daily_report.domain.model.DailyReport;
import dev.langchain4j.community.store.embedding.duckdb.DuckDBEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DuckDB 嵌入式向量存储的运行时集成测试（无需 Postgres / LLM / 网络）。
 * <p>
 * 目的：证明 langchain4j-community-duckdb:1.0.0-beta5 与 core 1.13.1 在运行期兼容
 * （社区模块与 core 不同发布线，存在版本偏差风险），并验证 {@link EmbeddingStoreReportStore}
 * 的 save → searchSimilar 全链路在 DuckDB 单文件持久化下正确工作。
 */
class EmbeddingStoreReportStoreDuckDbTest {

    @Test
    @DisplayName("saves a report to a file-backed DuckDB store and retrieves it by semantic similarity")
    void saveAndSearch_roundTrips(@TempDir Path tmp) {
        EmbeddingModel embeddingModel = new AllMiniLmL6V2EmbeddingModel();
        EmbeddingStore<TextSegment> store = DuckDBEmbeddingStore.builder()
                .filePath(tmp.resolve("reports.duckdb").toString())
                .tableName("report_embeddings")
                .build();

        EmbeddingStoreReportStore reportStore = new EmbeddingStoreReportStore(store, embeddingModel);

        reportStore.save(DailyReport.fromMarkdown(
                "# 工作日报\n完成了订单支付模块的重构，修复了并发下单的库存超卖问题。"));
        reportStore.save(DailyReport.fromMarkdown(
                "# 工作日报\n编写了用户注册页面的前端表单校验。"));

        List<String> results = reportStore.searchSimilar("库存超卖 并发 支付", 1);

        assertThat(results).hasSize(1);
        assertThat(results.get(0)).contains("库存超卖");
    }
}
