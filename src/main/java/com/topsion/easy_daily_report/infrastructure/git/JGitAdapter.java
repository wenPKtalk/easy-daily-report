package com.topsion.easy_daily_report.infrastructure.git;

import com.topsion.easy_daily_report.domain.model.CodeChange;
import com.topsion.easy_daily_report.domain.port.GitPort;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * JGit 适配器（Adapter）
 * 实现 GitPort 端口，使用 JGit 访问 Git 仓库
 *
 * 设计模式：Adapter Pattern — 将 JGit API 适配为 Domain 端口接口
 */
@Component
@Slf4j
public class JGitAdapter implements GitPort {

    @Override
    public CodeChange getCommitDetail(String repositoryPath, String commitHash) {
        try (Repository repo = openRepository(repositoryPath)) {
            ObjectId commitId = repo.resolve(commitHash);
            try (Git git = new Git(repo)) {
                RevCommit commit = git.log().add(commitId).setMaxCount(1).call().iterator().next();
                String diff = getDiff(repositoryPath, commitHash);
                return toCodeChange(commit, diff);
            }
        } catch (Exception e) {
            log.error("获取 commit 详情失败: {}", commitHash, e);
            throw new RuntimeException("获取 commit 详情失败: " + commitHash, e);
        }
    }

    @Override
    public List<CodeChange> getCommitRange(String repositoryPath, String fromCommit, String toCommit) {
        try (Repository repo = openRepository(repositoryPath)) {
            ObjectId from = repo.resolve(fromCommit);
            ObjectId to = repo.resolve(toCommit);
            try (Git git = new Git(repo)) {
                Iterable<RevCommit> commits = git.log().addRange(from, to).call();
                List<CodeChange> changes = new ArrayList<>();
                for (RevCommit commit : commits) {
                    changes.add(toCodeChange(commit, null));
                }
                return changes;
            }
        } catch (Exception e) {
            log.error("获取 commit 范围失败: {} .. {}", fromCommit, toCommit, e);
            throw new RuntimeException("获取 commit 范围失败", e);
        }
    }

    @Override
    public String getDiff(String repositoryPath, String commitHash) {
        try (Repository repo = openRepository(repositoryPath)) {
            ObjectId commitId = repo.resolve(commitHash);
            try (Git git = new Git(repo)) {
                RevCommit commit = git.log().add(commitId).setMaxCount(1).call().iterator().next();
                if (commit.getParentCount() == 0) {
                    return "(initial commit)";
                }
                RevCommit parent = commit.getParent(0);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (DiffFormatter formatter = new DiffFormatter(out)) {
                    formatter.setRepository(repo);
                    List<DiffEntry> diffs = formatter.scan(parent.getTree(), commit.getTree());
                    for (DiffEntry entry : diffs) {
                        formatter.format(entry);
                    }
                }
                return out.toString();
            }
        } catch (Exception e) {
            log.error("获取 diff 失败: {}", commitHash, e);
            throw new RuntimeException("获取 diff 失败: " + commitHash, e);
        }
    }

    @Override
    public List<CodeChange> getRecentCommits(String repositoryPath, int count) {
        try (Repository repo = openRepository(repositoryPath)) {
            try (Git git = new Git(repo)) {
                Iterable<RevCommit> commits = git.log().setMaxCount(count).call();
                List<CodeChange> changes = new ArrayList<>();
                for (RevCommit commit : commits) {
                    changes.add(toCodeChange(commit, null));
                }
                return changes;
            }
        } catch (Exception e) {
            log.error("获取最近 commits 失败", e);
            throw new RuntimeException("获取最近 commits 失败", e);
        }
    }

    private Repository openRepository(String path) throws IOException {
        return new FileRepositoryBuilder()
                .setGitDir(new File(path, ".git"))
                .readEnvironment()
                .findGitDir()
                .build();
    }

    private CodeChange toCodeChange(RevCommit commit, String diff) {
        return new CodeChange(
                commit.getId().getName(),
                commit.getAuthorIdent().getName(),
                commit.getFullMessage(),
                diff,
                LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(commit.getCommitTime()),
                        ZoneId.systemDefault()
                )
        );
    }
}
