package com.topsion.easy_daily_report.infrastructure.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15.BgeSmallZhV15EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embedding 模型配置（与向量存储解耦，始终生效）。
 * <p>
 * 使用 <b>BGE small 中文 v1.5</b>（512 维，进程内 ONNX，随 jar 打包）。
 * 日报语料是中文（固定中文模板），此前的 all-MiniLM-L6-v2 是英文模型，对中文文本近乎无效——
 * 换成中文模型是检索质量的最大杠杆。保证已存向量与新查询向量同源可比。
 * <p>
 * 注意：维度由 384（MiniLM）变为 512（bge-zh），旧的 DuckDB 向量文件需清空后重建。
 */
@Configuration
public class EmbeddingModelConfig {

    @Bean
    public EmbeddingModel embeddingModel() {
        return new BgeSmallZhV15EmbeddingModel();
    }
}
