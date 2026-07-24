package com.topsion.easy_daily_report.domain.port;

import com.topsion.easy_daily_report.domain.model.CodeChange;

import java.util.List;

/**
 * Git 操作端口（Port）
 * 依赖倒置：Domain 定义接口，Infrastructure 实现
 */
public interface GitPort {

    CodeChange getCommitDetail(String repositoryPath, String commitHash);

    List<CodeChange> getCommitRange(String repositoryPath, String fromCommit, String toCommit);

    String getDiff(String repositoryPath, String commitHash);

    List<CodeChange> getRecentCommits(String repositoryPath, int count);

    List<CodeChange> getTodayCommits(String repositoryPath);

    /**
     * 从 rootPath 递归查找其下所有 Git 仓库（含 .git 的目录）。
     * 跳过软连接与已访问过的目录（按 canonical 路径去重，避免环），找到仓库后不再深入其子树。
     *
     * @return 各仓库根目录的路径列表；rootPath 本身是仓库时返回单元素列表
     */
    List<String> findGitRepositories(String rootPath);
}
