package com.topsion.easy_daily_report.application.usecase;

import com.topsion.easy_daily_report.agent.coordinator.CoordinatorAgent;
import com.topsion.easy_daily_report.domain.model.DailyReport;
import com.topsion.easy_daily_report.domain.model.ReportRequest;
import com.topsion.easy_daily_report.domain.port.ReportStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * COORDINATOR_AGENT 模式的极薄编排层。
 * <p>
 * 编排逻辑下沉到 {@link CoordinatorAgent} 的 ReAct 循环中，本类只负责：
 * 1. 把 {@link ReportRequest} 转成 Coordinator 的输入参数（null → 空字符串，便于模板插值）
 * 2. 调用 Coordinator 拿到 Markdown 结果
 * 3. 持久化到 {@link ReportStore}（与 SINGLE 路径行为对齐）
 * 4. 包装为 {@link DailyReport} 返回
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CoordinatorOrchestrator implements GenerateAgent {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final CoordinatorAgent coordinatorAgent;
    private final ReportStore reportStore;

    @Override
    public DailyReport execute(ReportRequest request) {
        String commitHash = nullSafe(request.commitHash());
        String jiraKey = nullSafe(request.jiraIssueKey());
        String todayDate = LocalDate.now().format(DATE_FORMAT);

        log.info("[Coordinator] 开始生成日报 commit={}, jira={}", commitHash, jiraKey);
        String markdown = coordinatorAgent.coordinate(commitHash, jiraKey, todayDate);
        log.info("[Coordinator] 日报生成完成，长度 {} 字符", markdown == null ? 0 : markdown.length());

        DailyReport report = DailyReport.fromMarkdown(markdown);
        reportStore.save(report);
        return report;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
