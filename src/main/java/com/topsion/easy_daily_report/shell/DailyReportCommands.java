package com.topsion.easy_daily_report.shell;

import com.topsion.easy_daily_report.application.usecase.AgentLevel;
import com.topsion.easy_daily_report.application.usecase.AgentRouter;
import com.topsion.easy_daily_report.application.usecase.GenerateAgent;
import com.topsion.easy_daily_report.domain.model.CodeChange;
import com.topsion.easy_daily_report.domain.model.DailyReport;
import com.topsion.easy_daily_report.domain.model.ReportRequest;
import com.topsion.easy_daily_report.domain.port.GitPort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Spring Shell 命令层（Interface Adapter）
 * 负责接收用户 CLI 输入，转换为 Domain 对象，通过 AgentRouter 委托给对应 Agent 执行
 * 设计模式：
 * - Facade Pattern — 简化复杂系统的入口
 * - Adapter Pattern — 将 CLI 输入适配为 Domain ReportRequest
 * - Strategy Pattern — 通过 --level 选择 SINGLE / SAMPLE_MULTIPLE / COORDINATOR_AGENT
 */
@Component
@RequiredArgsConstructor
public class DailyReportCommands {

    private static final String DEFAULT_LEVEL = "SINGLE";

    private final AgentRouter agentRouter;
    private final GitPort gitPort;

    @Value("${git.default-repo-path:./}")
    private String defaultRepoPath;

    @Command(value = "report generate")
    public String generateReport(
            @Option(longName = "commit", shortName = 'c', description = "Git Commit Hash") String commitHash,
            @Option(longName = "range", shortName = 'r', description = "Commit 范围 (from..to)") String commitRange,
            @Option(longName = "jira", shortName = 'j', description = "Jira Issue Key") String jiraIssueKey,
            @Option(longName = "repo", shortName = 'p', description = "Git 仓库路径") String repoPath,
            @Option(longName = "level", shortName = 'l',
                    description = "Agent 级别: SINGLE | SAMPLE_MULTIPLE | COORDINATOR_AGENT (默认 SINGLE)",
                    defaultValue = DEFAULT_LEVEL) String level
    ) {
        AgentLevel agentLevel = parseLevel(level);
        if (agentLevel == null) {
            return invalidLevelMessage(level);
        }

        ReportRequest request = new ReportRequest(
                commitHash,
                commitRange,
                jiraIssueKey,
                repoPath
        );

        GenerateAgent agent = agentRouter.route(agentLevel);
        DailyReport report = agent.execute(request);
        return report.rawMarkdown();
    }

    @Command(value = "report generate-today")
    public String generateToday(
            @Option(longName = "jira", shortName = 'j', description = "Jira Issue Key (可选)") String jiraIssueKey,
            @Option(longName = "repo", shortName = 'p', description = "Git 仓库路径") String repoPath,
            @Option(longName = "level", shortName = 'l',
                    description = "Agent 级别: SINGLE | SAMPLE_MULTIPLE | COORDINATOR_AGENT (默认 SINGLE)",
                    defaultValue = DEFAULT_LEVEL) String level
    ) {
        AgentLevel agentLevel = parseLevel(level);
        if (agentLevel == null) {
            return invalidLevelMessage(level);
        }

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

        GenerateAgent agent = agentRouter.route(agentLevel);
        DailyReport report = agent.execute(request);
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
                ║    -j, --jira  <key>     Jira Issue (可选)    ║
                ║    -p, --repo  <path>    Git 仓库路径 (可选)   ║
                ║    -l, --level <level>   Agent 级别 (可选)    ║
                ║                                              ║
                ║  示例: report generate-today -j PROJ-123     ║
                ║                                              ║
                ║  ───────────────────────────────────────────  ║
                ║                                              ║
                ║  report generate                             ║
                ║    -c, --commit <hash>   Git Commit Hash     ║
                ║    -r, --range  <range>  Commit 范围          ║
                ║    -j, --jira   <key>    Jira Issue Key      ║
                ║    -p, --repo   <path>   Git 仓库路径          ║
                ║    -l, --level  <level>  Agent 级别 (可选)    ║
                ║                                              ║
                ║  示例: report generate -c abc123 -j PROJ-456 ║
                ║                                              ║
                ║  ───────────────────────────────────────────  ║
                ║                                              ║
                ║  Agent 级别 (--level):                       ║
                ║    SINGLE            单 Agent + ReAct (默认)  ║
                ║    SAMPLE_MULTIPLE   并行多 Agent + 静态编排  ║
                ║    COORDINATOR_AGENT Master/Sub + 动态调度    ║
                ║                                              ║
                ╚══════════════════════════════════════════════╝
                """;
    }

    private static AgentLevel parseLevel(String raw) {
        if (raw == null || raw.isBlank()) {
            return AgentLevel.SINGLE;
        }
        try {
            return AgentLevel.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String invalidLevelMessage(String raw) {
        String supported = Arrays.stream(AgentLevel.values())
                .map(Enum::name)
                .collect(Collectors.joining(" | "));
        return "❌ 未知的 Agent 级别: \"" + raw + "\"。请使用以下之一: " + supported;
    }
}
