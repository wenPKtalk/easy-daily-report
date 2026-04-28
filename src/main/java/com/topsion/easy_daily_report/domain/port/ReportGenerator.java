package com.topsion.easy_daily_report.domain.port;

import com.topsion.easy_daily_report.domain.model.DailyReport;
import com.topsion.easy_daily_report.domain.model.ReportRequest;

/**
 * 日报生成端口（Port）— Strategy Pattern
 * AI Agent 实现此接口，可替换不同的生成策略
 */
public interface ReportGenerator {

    DailyReport generate(ReportRequest request);
}
