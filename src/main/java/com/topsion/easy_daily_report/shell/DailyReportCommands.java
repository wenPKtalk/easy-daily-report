package com.topsion.easy_daily_report.shell;

import com.topsion.easy_daily_report.agent.subagents.MultiRepoReportAgent;
import com.topsion.easy_daily_report.application.usecase.AgentLevel;
import com.topsion.easy_daily_report.application.usecase.AgentRouter;
import com.topsion.easy_daily_report.application.usecase.GenerateAgent;
import com.topsion.easy_daily_report.domain.model.CodeChange;
import com.topsion.easy_daily_report.domain.model.DailyReport;
import com.topsion.easy_daily_report.domain.model.ReportRequest;
import com.topsion.easy_daily_report.domain.port.GitPort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
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

    private final AgentRouter agentRouter;
    private final GitPort gitPort;
    private final MultiRepoReportAgent multiRepoReportAgent;

    @Value("${git.default-repo-path:./}")
    private String defaultRepoPath;

    public String generateReport(
            String commitHash, String commitRange, String jiraIssueKey, String repoPath, String level
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

    /**
     * 多仓库今日日报：递归扫描 root 下所有 Git 仓库 → 收集各仓库今天的提交 →
     * 交给 LLM 按仓库分卡片分类归纳。level 在此路径不生效（多仓库聚合有独立流程）。
     */
    public String generateToday(String jiraIssueKey, String repoPath, String level) {
        String root = (repoPath != null && !repoPath.isBlank()) ? repoPath : defaultRepoPath;

        List<String> repos = gitPort.findGitRepositories(root);
        if (repos.isEmpty()) {
            return "⚠️ 未在 " + root + " 下找到任何 Git 仓库。";
        }

        StringBuilder materials = new StringBuilder();
        int reposWithCommits = 0;
        for (String repo : repos) {
            List<CodeChange> commits;
            try {
                commits = gitPort.getTodayCommits(repo);
            } catch (Exception e) {
                continue;   // 单个仓库失败不影响整体
            }
            if (commits.isEmpty()) {
                continue;
            }
            reposWithCommits++;
            materials.append("## 仓库：").append(new File(repo).getName())
                    .append("（").append(repo).append("）\n");
            for (CodeChange c : commits) {
                materials.append("- ").append(c.shortId())
                        .append(" | ").append(c.author())
                        .append(" | ").append(c.message().split("\n")[0].trim())
                        .append("\n");
            }
            materials.append("\n");
        }

        if (reposWithCommits == 0) {
            return "⚠️ 今天在扫描到的 " + repos.size() + " 个仓库中都没有提交。";
        }

        System.out.println("📋 扫描到 " + repos.size() + " 个 Git 仓库，其中 " + reposWithCommits
                + " 个今天有提交，正在生成分卡片日报...\n");

        return multiRepoReportAgent.generate(materials.toString());
    }

    public String help() {
        return """
                ╔══════════════════════════════════════════════╗
                ║        Easy Daily Report - 智能日报生成       ║
                ╠══════════════════════════════════════════════╣
                ║  提示：输入 / 后按 TAB 可弹出命令菜单选择      ║
                ║                                              ║
                ║  📌 /generate-today                          ║
                ║    自动生成今天的日报                         ║
                ║    -j, --jira  <key>     Jira Issue (可选)    ║
                ║    -p, --repo  <path>    Git 仓库路径 (可选)   ║
                ║    -l, --level <level>   Agent 级别 (可选)    ║
                ║                                              ║
                ║  示例: /generate-today -j PROJ-123           ║
                ║                                              ║
                ║  ───────────────────────────────────────────  ║
                ║                                              ║
                ║  /generate                                   ║
                ║    -c, --commit <hash>   Git Commit Hash     ║
                ║    -r, --range  <range>  Commit 范围          ║
                ║    -j, --jira   <key>    Jira Issue Key      ║
                ║    -p, --repo   <path>   Git 仓库路径          ║
                ║    -l, --level  <level>  Agent 级别 (可选)    ║
                ║                                              ║
                ║  示例: /generate -c abc123 -j PROJ-456       ║
                ║                                              ║
                ║  ───────────────────────────────────────────  ║
                ║                                              ║
                ║  /chat  进入对话模式（多轮，自动路由）         ║
                ║                                              ║
                ║  Agent 级别 (--level):                       ║
                ║    SINGLE            单 Agent (默认)          ║
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
