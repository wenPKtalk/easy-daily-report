package com.topsion.easy_daily_report.shell;

import com.topsion.easy_daily_report.application.usecase.GenerateReportUseCase;
import com.topsion.easy_daily_report.domain.model.DailyReport;
import com.topsion.easy_daily_report.domain.model.ReportRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.shell.command.annotation.Command;
import org.springframework.shell.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * Spring Shell 命令层（Interface Adapter）
 *
 * 负责接收用户 CLI 输入，转换为 Domain 对象，委托给 UseCase 执行
 *
 * 设计模式：
 * - Facade Pattern — 简化复杂系统的入口
 * - Adapter Pattern — 将 CLI 输入适配为 Domain ReportRequest
 */
@Component
@Command(group = "Daily Report")
@RequiredArgsConstructor
public class DailyReportCommands {

    private final GenerateReportUseCase generateReportUseCase;

    @Command(command = "report generate", description = "生成工作日报")
    public String generateReport(
            @Option(longNames = "commit", shortNames = 'c', description = "Git Commit Hash") String commitHash,
            @Option(longNames = "range", shortNames = 'r', description = "Commit 范围 (from..to)") String commitRange,
            @Option(longNames = "jira", shortNames = 'j', description = "Jira Issue Key") String jiraIssueKey,
            @Option(longNames = "repo", shortNames = 'p', description = "Git 仓库路径") String repoPath
    ) {
        ReportRequest request = new ReportRequest(
                commitHash,
                commitRange,
                jiraIssueKey,
                repoPath
        );

        DailyReport report = generateReportUseCase.execute(request);
        return report.rawMarkdown();
    }

    @Command(command = "report help", description = "显示日报生成帮助信息")
    public String help() {
        return """
                ╔══════════════════════════════════════════════╗
                ║        Easy Daily Report - 智能日报生成       ║
                ╠══════════════════════════════════════════════╣
                ║                                              ║
                ║  report generate                             ║
                ║    -c, --commit <hash>   Git Commit Hash     ║
                ║    -r, --range <range>   Commit 范围          ║
                ║    -j, --jira <key>      Jira Issue Key      ║
                ║    -p, --repo <path>     Git 仓库路径          ║
                ║                                              ║
                ║  示例：                                       ║
                ║  report generate -c abc123 -j PROJ-456       ║
                ║                                              ║
                ╚══════════════════════════════════════════════╝
                """;
    }
}
