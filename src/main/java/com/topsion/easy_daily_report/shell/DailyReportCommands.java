package com.topsion.easy_daily_report.shell;

import com.topsion.easy_daily_report.application.usecase.GenerateReportUseCase;
import com.topsion.easy_daily_report.domain.model.CodeChange;
import com.topsion.easy_daily_report.domain.model.DailyReport;
import com.topsion.easy_daily_report.domain.model.ReportRequest;
import com.topsion.easy_daily_report.domain.port.GitPort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

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
@RequiredArgsConstructor
public class DailyReportCommands {

    private final GenerateReportUseCase generateReportUseCase;
    private final GitPort gitPort;

    @Value("${git.default-repo-path:./}")
    private String defaultRepoPath;

    @Command(value = "report generate")
    public String generateReport(
            @Option(longName = "commit", shortName = 'c', description = "Git Commit Hash") String commitHash,
            @Option(longName = "range", shortName = 'r', description = "Commit 范围 (from..to)") String commitRange,
            @Option(longName = "jira", shortName = 'j', description = "Jira Issue Key") String jiraIssueKey,
            @Option(longName = "repo", shortName = 'p', description = "Git 仓库路径") String repoPath
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

    @Command(value = "report generate-today")
    public String generateToday(
            @Option(longName = "jira", shortName = 'j', description = "Jira Issue Key (可选)") String jiraIssueKey,
            @Option(longName = "repo", shortName = 'p', description = "Git 仓库路径") String repoPath
    ) {
        String path = (repoPath != null && !repoPath.isBlank()) ? repoPath : defaultRepoPath;

        // 获取今天的所有提交
        List<CodeChange> todayCommits = gitPort.getTodayCommits(path);

        if (todayCommits.isEmpty()) {
            return "⚠️ 今天没有找到任何 Git 提交记录。";
        }

        // 构建提交范围 (最早..最新)
        String oldestCommit = todayCommits.get(todayCommits.size() - 1).shortId();
        String newestCommit = todayCommits.get(0).shortId();
        String commitRange = oldestCommit + ".." + newestCommit;

        // 显示找到的提交
        String commitList = todayCommits.stream()
                .map(c -> "  - " + c.shortId() + " | " + c.message().split("\n")[0])
                .collect(Collectors.joining("\n"));

        System.out.println("📋 找到今天 " + todayCommits.size() + " 条提交:\n" + commitList + "\n");

        // 构建请求，传入 commit 范围
        ReportRequest request = new ReportRequest(
                null,
                commitRange,
                jiraIssueKey,
                path
        );

        DailyReport report = generateReportUseCase.execute(request);
        return report.rawMarkdown();
    }

    @Command(value = "report help")
    public String help() {
        return """
                ╔══════════════════════════════════════════════╗
                ║        Easy Daily Report - 智能日报生成       ║
                ╠══════════════════════════════════════════════╣
                ║                                              ║
                ║  📌 report generate-today                    ║
                ║    自动生成今天的日报                         ║
                ║    -j, --jira <key>      Jira Issue (可选)    ║
                ║    -p, --repo <path>     Git 仓库路径 (可选)    ║
                ║                                              ║
                ║  示例: report generate-today -j PROJ-123     ║
                ║                                              ║
                ║  ───────────────────────────────────────────  ║
                ║                                              ║
                ║  report generate                             ║
                ║    -c, --commit <hash>   Git Commit Hash     ║
                ║    -r, --range <range>   Commit 范围          ║
                ║    -j, --jira <key>      Jira Issue Key      ║
                ║    -p, --repo <path>     Git 仓库路径          ║
                ║                                              ║
                ║  示例: report generate -c abc123 -j PROJ-456 ║
                ║                                              ║
                ╚══════════════════════════════════════════════╝
                """;
    }
}
