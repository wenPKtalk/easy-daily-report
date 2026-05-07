package com.topsion.easy_daily_report.application.usecase;

import com.topsion.easy_daily_report.domain.model.DailyReport;
import com.topsion.easy_daily_report.domain.model.ReportRequest;

public interface GenerateAgent {
    DailyReport execute(ReportRequest request);
}
