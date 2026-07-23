package com.topsion.easy_daily_report.infrastructure.ai.tools;

import com.topsion.easy_daily_report.domain.port.ReportStore;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 历史日报检索工具（LangChain4j @Tool），供 SINGLE 模式的 Agent 在收集完 Git/Jira 信息后
 * 用「业务背景 / 技术要点短句」按需检索历史相似日报作参考。
 * <p>
 * 取代此前的 eager ContentRetriever：旧方式在 Agent 调用任何工具之前，就用
 * 「commit hash + jira key」这种近乎无语义的字符串做检索。改为工具后，由 LLM 在拿到
 * diff / issue 之后自行组织有意义的查询（与 COORDINATOR 的 retrieveSimilarReports 一致）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SimilarReportsTool {

    private static final int MAX_RESULTS = 3;

    private final ReportStore reportStore;

    @Tool("检索与查询语义相似的历史日报，用作参考上下文（借鉴风格与结构，不要照抄）；查询请用业务背景或技术要点的短句，不要用 commit hash")
    public String retrieveSimilarReports(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        log.info("[SINGLE/Tool] retrieveSimilarReports query={}", query);
        List<String> similar = reportStore.searchSimilar(query, MAX_RESULTS);
        return similar.isEmpty() ? "" : String.join("\n---\n", similar);
    }
}
