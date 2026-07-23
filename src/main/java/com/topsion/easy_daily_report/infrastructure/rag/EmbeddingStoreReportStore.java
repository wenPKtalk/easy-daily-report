package com.topsion.easy_daily_report.infrastructure.rag;

import com.topsion.easy_daily_report.domain.model.DailyReport;
import com.topsion.easy_daily_report.domain.port.ReportStore;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 LangChain4j {@link EmbeddingStore} 的日报存储（Adapter）。
 * <p>
 * 实现 {@link ReportStore} 端口，与具体向量库无关：使用注入的 {@code EmbeddingStore} bean
 * （当前为嵌入式 DuckDB，见 {@code DuckDBConfig}）。
 *
 * 设计模式：
 * - Repository Pattern — 封装存储细节
 * - Adapter Pattern — 将 LangChain4j EmbeddingStore 适配为 Domain 端口
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmbeddingStoreReportStore implements ReportStore {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    /** 最低余弦相似度阈值：低于此分数的历史日报视为不相关，不注入（避免"塞坏文档"）。可调。 */
    @Value("${rag.min-score:0.5}")
    private double minScore;

    @Override
    public void save(DailyReport report) {
        log.info("保存日报到向量数据库，日期: {}", report.date());

        TextSegment segment = TextSegment.from(
                report.rawMarkdown(),
                Metadata.metadata("date", report.date().toString())
                        .put("type", "daily-report")
        );

        Embedding embedding = embeddingModel.embed(segment).content();
        embeddingStore.add(embedding, segment);

        log.info("日报已保存到向量数据库");
    }

    @Override
    public List<String> searchSimilar(String query, int maxResults) {
        log.info("检索相似日报，query: {}", query);

        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchRequest embeddingSearchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(embeddingSearchRequest).matches();
        return matches.stream()
                .map(match -> match.embedded().text())
                .toList();
    }
}
