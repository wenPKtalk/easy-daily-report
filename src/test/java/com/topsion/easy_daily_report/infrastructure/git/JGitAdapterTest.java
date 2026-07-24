package com.topsion.easy_daily_report.infrastructure.git;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * findGitRepositories 的递归/跳过逻辑验证（纯文件系统，无 JGit / 无网络）。
 */
class JGitAdapterTest {

    @Test
    @DisplayName("递归发现所有 Git 仓库：跳过软连接、噪音目录、已入仓库的子树")
    void findGitRepositories_discoversAndSkips(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("repoA/.git"));
        Files.createDirectories(tmp.resolve("repoA/src"));               // 仓库子目录 → 不算独立仓库
        Files.createDirectories(tmp.resolve("group/repoB/.git"));        // 更深一层的仓库也要找到
        Files.createDirectories(tmp.resolve("plain/sub"));               // 无 .git
        Files.createDirectories(tmp.resolve("node_modules/pkg/.git"));   // 噪音目录 → 跳过
        try {
            Files.createSymbolicLink(tmp.resolve("link-to-A"), tmp.resolve("repoA")); // 软连接 → 跳过
        } catch (Exception ignored) {
            // 某些环境不允许建软连接；其余断言仍有效
        }

        JGitAdapter adapter = new JGitAdapter();
        List<String> repos = adapter.findGitRepositories(tmp.toString());

        assertThat(repos)
                .as("应只发现 repoA 和 group/repoB")
                .hasSize(2)
                .anySatisfy(p -> assertThat(p).endsWith("repoA"))
                .anySatisfy(p -> assertThat(p).endsWith("repoB"));
        // node_modules/pkg/.git 被跳过；link-to-A 软连接被跳过；repoA/src 未被当作仓库
    }

    @Test
    @DisplayName("rootPath 本身就是仓库时返回单元素")
    void findGitRepositories_rootIsRepo(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve(".git"));

        List<String> repos = new JGitAdapter().findGitRepositories(tmp.toString());

        assertThat(repos).hasSize(1);
        assertThat(repos.get(0)).endsWith(tmp.getFileName().toString());
    }
}
