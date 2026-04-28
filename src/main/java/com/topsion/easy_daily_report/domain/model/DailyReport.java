package com.topsion.easy_daily_report.domain.model;

import java.time.LocalDate;
import java.util.List;

/**
 * 工作日报（Aggregate Root）
 * 表示一份完整的日报输出
 */
public record DailyReport(
        LocalDate date,
        String taskOverview,
        List<String> codeHighlights,
        String businessValue,
        List<String> risksAndSuggestions,
        String tomorrowPlan,
        String rawMarkdown
) {

    public static DailyReport fromMarkdown(String markdown) {
        return new DailyReport(
                LocalDate.now(),
                null,
                List.of(),
                null,
                List.of(),
                null,
                markdown
        );
    }
}
