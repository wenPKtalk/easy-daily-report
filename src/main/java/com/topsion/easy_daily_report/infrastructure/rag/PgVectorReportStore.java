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
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/**
 * PGVector 日报存储（Adapter）
 * 实现 ReportStore 端口，使用 PGVector 进行日报的存储和相似检索
 *
 * 设计模式：
 * - Repository Pattern — 封装存储细节
 * - Adapter Pattern — 将 LangChain4j EmbeddingStore 适配为 Domain 端口
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PgVectorReportStore implements ReportStore {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

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
                .build();
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(embeddingSearchRequest).matches();
        return matches.stream()
                .map(match -> match.embedded().text())
                .toList();
    }
}
