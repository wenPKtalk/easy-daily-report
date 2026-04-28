package com.topsion.easy_daily_report.domain.model;

import java.time.LocalDateTime;

/**
 * 代码变更信息（Value Object）
 * 表示一次 Git Commit 的核心信息
 */
public record CodeChange(
        String commitId,
        String author,
        String message,
        String diff,
        LocalDateTime commitTime
) {
    public String shortId() {
        return commitId != null && commitId.length() > 7
                ? commitId.substring(0, 7)
                : commitId;
    }
}
