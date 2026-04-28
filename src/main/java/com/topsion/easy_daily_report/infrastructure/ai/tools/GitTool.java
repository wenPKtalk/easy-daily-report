package com.topsion.easy_daily_report.infrastructure.ai.tools;

import com.topsion.easy_daily_report.domain.model.CodeChange;
import com.topsion.easy_daily_report.domain.port.GitPort;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Git 工具（LangChain4j @Tool）
 * 供 ReAct Agent 自主调用，获取 Git 信息
 *
 * 设计模式：Adapter + Facade — 将 GitPort 封装为 Agent 可调用的 Tool
 */
@Component
@RequiredArgsConstructor
public class GitTool {

    private final GitPort gitPort;

    @Value("${git.default-repo-path:./}")
    private String defaultRepoPath;

    @Tool("获取指定 commit 的代码 diff 详情，包括作者、提交信息和代码变更内容")
    public String getCommitDiff(String commitHash) {
        CodeChange change = gitPort.getCommitDetail(defaultRepoPath, commitHash);
        return """
                Commit: %s
                Author: %s
                Time: %s
                Message: %s
                
                Diff:
                %s
                """.formatted(
                change.shortId(),
                change.author(),
                change.commitTime(),
                change.message(),
                change.diff()
        );
    }

    @Tool("获取最近 N 条 commit 记录，默认 5 条")
    public String getRecentCommits(int count) {
        List<CodeChange> commits = gitPort.getRecentCommits(defaultRepoPath, count);
        return commits.stream()
                .map(c -> "%s | %s | %s".formatted(c.shortId(), c.author(), c.message().split("\n")[0]))
                .collect(Collectors.joining("\n"));
    }
}
