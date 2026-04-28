package com.topsion.easy_daily_report.application.usecase;

import com.topsion.easy_daily_report.domain.model.DailyReport;
import com.topsion.easy_daily_report.domain.model.ReportRequest;
import com.topsion.easy_daily_report.domain.port.ReportGenerator;
import com.topsion.easy_daily_report.domain.port.ReportStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 生成日报用例（Application Service）
 *
 * 职责：编排 Domain 端口，协调日报生成流程
 * - Single Responsibility: 只负责日报生成的编排逻辑
 * - Open/Closed: 通过 ReportGenerator 接口扩展不同生成策略
 * - Dependency Inversion: 依赖 Domain 端口接口，不依赖具体实现
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GenerateReportUseCase {

    private final ReportGenerator reportGenerator;
    private final ReportStore reportStore;

    public DailyReport execute(ReportRequest request) {
        log.info("开始生成日报，commit: {}, jira: {}",
                request.commitHash(), request.jiraIssueKey());

        DailyReport report = reportGenerator.generate(request);

        reportStore.save(report);
        log.info("日报已生成并保存");

        return report;
    }
}
