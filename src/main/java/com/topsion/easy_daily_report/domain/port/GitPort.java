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
}
