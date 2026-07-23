package com.topsion.easy_daily_report.agent.subagents;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C1 回归防护：合成器 user message 必须真的携带两份分析 JSON 与日期，
 * 否则会退回到「历史 @V 无占位符被丢弃 → 报告无依据」的缺陷。
 */
class ReportPromptBuilderTest {

    @Test
    @DisplayName("build embeds date, git JSON and jira JSON into the message")
    void build_embedsAnalysesAndDate() {
        String msg = ReportPromptBuilder.build(
                "2026-07-22",
                "{\"files_count\":3,\"key_changes\":[\"add X\"]}",
                "{\"priority\":\"High\"}",
                null
        );

        assertThat(msg)
                .contains("2026-07-22")
                .contains("{\"files_count\":3,\"key_changes\":[\"add X\"]}")
                .contains("{\"priority\":\"High\"}");
    }

    @Test
    @DisplayName("build appends the historical section only when context is non-blank")
    void build_historicalContextConditional() {
        String withHistory = ReportPromptBuilder.build("2026-07-22", "{}", "{}", "past-report-snippet");
        assertThat(withHistory)
                .contains("历史相似日报")
                .contains("past-report-snippet");

        String blankHistory = ReportPromptBuilder.build("2026-07-22", "{}", "{}", "   ");
        assertThat(blankHistory).doesNotContain("历史相似日报");

        String nullHistory = ReportPromptBuilder.build("2026-07-22", "{}", "{}", null);
        assertThat(nullHistory).doesNotContain("历史相似日报");
    }

    @Test
    @DisplayName("build tolerates null analysis inputs without NPE")
    void build_nullSafe() {
        String msg = ReportPromptBuilder.build(null, null, null, null);
        assertThat(msg).contains("Git 代码变更分析").contains("Jira 业务需求分析");
    }
}
