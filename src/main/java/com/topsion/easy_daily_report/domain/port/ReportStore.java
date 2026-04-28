package com.topsion.easy_daily_report.domain.port;

import com.topsion.easy_daily_report.domain.model.DailyReport;

import java.util.List;

/**
 * 日报存储端口（Port）— Repository Pattern
 * 负责日报的持久化和基于 RAG 的相似日报检索
 */
public interface ReportStore {

    void save(DailyReport report);

    List<String> searchSimilar(String query, int maxResults);
}
