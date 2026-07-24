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
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    @Override
    public List<CodeChange> getTodayCommits(String repositoryPath) {
        try (Repository repo = openRepository(repositoryPath)) {
            try (Git git = new Git(repo)) {
                // 计算今天的时间范围
                LocalDate today = LocalDate.now();
                LocalDateTime startOfDay = today.atStartOfDay();
                LocalDateTime startOfTomorrow = today.plusDays(1).atStartOfDay();

                long startEpoch = startOfDay.atZone(ZoneId.systemDefault()).toEpochSecond();
                long endEpoch = startOfTomorrow.atZone(ZoneId.systemDefault()).toEpochSecond();

                log.info("获取今天的提交: {} 至 {}", startOfDay, startOfTomorrow);

                // 遍历所有提交，筛选今天内的（空仓库 / 无 HEAD 时优雅返回空）
                Iterable<RevCommit> commits;
                try {
                    commits = git.log().call();
                } catch (org.eclipse.jgit.api.errors.NoHeadException e) {
                    log.warn("仓库无 HEAD（空仓库 / 无提交），返回空: {}", repositoryPath);
                    return List.of();
                }
                List<CodeChange> changes = new ArrayList<>();

                for (RevCommit commit : commits) {
                    long commitTime = commit.getCommitTime();
                    if (commitTime >= startEpoch && commitTime < endEpoch) {
                        changes.add(toCodeChange(commit, null));
                    }
                }

                log.info("找到 {} 条今天的提交", changes.size());
                return changes;
            }
        } catch (Exception e) {
            log.error("获取今天的提交失败", e);
            throw new RuntimeException("获取今天的提交失败", e);
        }
    }

    /** 递归查找 rootPath 下的所有 Git 仓库；跳过软连接、已访问目录与噪音目录，找到仓库后不深入。 */
    @Override
    public List<String> findGitRepositories(String rootPath) {
        Path root = new File(rootPath == null || rootPath.isBlank() ? "." : rootPath).getAbsoluteFile().toPath();
        List<String> repos = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        // 深度大 / 无关的目录直接跳过（性能 + 避免把 vendored 仓库当独立仓库）
        Set<String> skip = Set.of(".git", "node_modules", "target", "build", ".gradle", ".idea",
                "dist", "out", "vendor", ".venv", "venv", "__pycache__");
        try {
            // EnumSet.noneOf → 不跟随软连接
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            try {
                                if (Files.isSymbolicLink(dir)) {
                                    return FileVisitResult.SKIP_SUBTREE;               // 跳过软连接（避免环）
                                }
                                if (!visited.add(dir.toRealPath().toString())) {
                                    return FileVisitResult.SKIP_SUBTREE;               // 已遍历过（canonical 去重）
                                }
                                if (skip.contains(dir.getFileName() == null ? "" : dir.getFileName().toString())) {
                                    return FileVisitResult.SKIP_SUBTREE;               // 噪音目录
                                }
                                if (Files.isDirectory(dir.resolve(".git"))) {
                                    repos.add(dir.toString());
                                    return FileVisitResult.SKIP_SUBTREE;               // 找到仓库，不再深入其子树
                                }
                            } catch (IOException e) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;                            // 权限等问题跳过，不中断
                        }
                    });
        } catch (IOException e) {
            log.warn("扫描 Git 仓库失败: {}", root, e);
        }
        log.info("在 {} 下发现 {} 个 Git 仓库", root, repos.size());
        return repos;
    }

    private Repository openRepository(String path) throws IOException {
        File start = new File(path == null || path.isBlank() ? "." : path).getAbsoluteFile();
        FileRepositoryBuilder builder = new FileRepositoryBuilder()
                .findGitDir(start)          // 从 path 向上查找 .git，稳健定位仓库
                .readEnvironment();
        // 必须在 build() 之前判空：findGitDir 没找到时 build() 会抛 "must call setGitDir/setWorkTree"
        if (builder.getGitDir() == null) {
            throw new IOException(start.getPath() + " 不是 Git 仓库（未找到 .git）。"
                    + "若它是包含多个仓库的父目录，请用 /generate-today（会自动扫描子仓库）；"
                    + "若要分析单个 commit，请把 GIT_REPO_PATH 或 -p 指向具体仓库。");
        }
        return builder.build();
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
