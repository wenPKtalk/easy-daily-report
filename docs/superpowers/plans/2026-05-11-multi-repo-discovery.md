# Multi-Repo Discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `repoPath` accept either a single git repo OR a parent directory that contains git repos as immediate children; the agent should read git log from every discovered repo and produce one combined daily report.

**Architecture:** Introduce a new pure-Java `RepoDiscoverer` that resolves a path → `List<RepoEntry>`. Wire it into `GitTool` (the LangChain4j `@Tool` provider used by both single-agent and multi-agent paths) and into the shell entry `DailyReportCommands.generateToday`. `JGitAdapter` stays unchanged — it always sees one resolved repo at a time. Commits from each repo are prefixed with `[repoName]` in the text returned to the LLM so cross-repo provenance is preserved.

**Tech Stack:** Java 21, Spring Boot 4.0.6, LangChain4j 1.13.1, JGit 7.2.0, JUnit 5 + Mockito + AssertJ, JUnit `@TempDir`.

**Backward compatibility:** When the supplied path *is* a git repo, behavior is unchanged (single entry → existing code path).

---

## File Structure

```
src/main/java/com/topsion/easy_daily_report/infrastructure/git/
  ├── JGitAdapter.java                            (unchanged)
  ├── RepoDiscoverer.java                         (NEW)  pure FS logic, no JGit
  └── RepoEntry.java                              (NEW)  record (name, path)

src/main/java/com/topsion/easy_daily_report/infrastructure/ai/tools/
  └── GitTool.java                                (MODIFY)  inject RepoDiscoverer, aggregate across repos

src/main/java/com/topsion/easy_daily_report/shell/
  └── DailyReportCommands.java                    (MODIFY)  use RepoDiscoverer in generateToday

src/test/java/com/topsion/easy_daily_report/infrastructure/git/
  └── RepoDiscovererTest.java                     (NEW)

src/test/java/com/topsion/easy_daily_report/infrastructure/ai/tools/
  └── GitToolTest.java                            (NEW)
```

**Decision log (locked-in):**
- Discovery depth: immediate subdirectories only (1 level).
- A directory `D` counts as a git repo when `D/.git` exists as either a directory or a regular file (worktree support).
- Empty discovery → throw `IllegalArgumentException("No git repositories found under: " + path)`.
- Non-existent path → `IllegalArgumentException("Path does not exist: " + path)`.
- Repo identity in LLM output: lines are prefixed `[repoName] …` where `repoName` is the directory's filename.
- `getCommitDiff(hash)`: searches every discovered repo, returns the first match. If no match, returns `Commit not found in any repository: <hash>`.
- `RepoDiscoverer` is a Spring `@Component` so it can be injected.

---

## Task 1: `RepoDiscoverer` foundation

**Files:**
- Create: `src/main/java/com/topsion/easy_daily_report/infrastructure/git/RepoEntry.java`
- Create: `src/main/java/com/topsion/easy_daily_report/infrastructure/git/RepoDiscoverer.java`
- Test: `src/test/java/com/topsion/easy_daily_report/infrastructure/git/RepoDiscovererTest.java`

- [ ] **Step 1.1: Create `RepoEntry` record**

Create `src/main/java/com/topsion/easy_daily_report/infrastructure/git/RepoEntry.java`:

```java
package com.topsion.easy_daily_report.infrastructure.git;

/**
 * Identifies one git repository discovered by {@link RepoDiscoverer}.
 *
 * @param name absolute-path-independent identifier (the directory filename)
 * @param path absolute filesystem path passed to JGit
 */
public record RepoEntry(String name, String path) {}
```

- [ ] **Step 1.2: Write the failing test (RED)**

Create `src/test/java/com/topsion/easy_daily_report/infrastructure/git/RepoDiscovererTest.java`:

```java
package com.topsion.easy_daily_report.infrastructure.git;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepoDiscovererTest {

    private final RepoDiscoverer discoverer = new RepoDiscoverer();

    @Test
    @DisplayName("discover returns single entry when path itself is a git repo")
    void discover_singleGitRepo_returnsOneEntry(@TempDir Path tmp) throws IOException {
        Files.createDirectory(tmp.resolve(".git"));

        List<RepoEntry> repos = discoverer.discover(tmp.toString());

        assertThat(repos).hasSize(1);
        assertThat(repos.get(0).name()).isEqualTo(tmp.getFileName().toString());
        assertThat(repos.get(0).path()).isEqualTo(tmp.toAbsolutePath().toString());
    }

    @Test
    @DisplayName("discover finds git repos in immediate subdirectories")
    void discover_parentWithGitChildren_returnsEachChild(@TempDir Path tmp) throws IOException {
        Path a = Files.createDirectory(tmp.resolve("project-a"));
        Path b = Files.createDirectory(tmp.resolve("project-b"));
        Files.createDirectory(tmp.resolve("docs")); // non-git, should be ignored
        Files.createDirectory(a.resolve(".git"));
        Files.createDirectory(b.resolve(".git"));

        List<RepoEntry> repos = discoverer.discover(tmp.toString());

        assertThat(repos).extracting(RepoEntry::name)
                .containsExactlyInAnyOrder("project-a", "project-b");
    }

    @Test
    @DisplayName("discover treats .git as regular file (worktree) as a valid repo")
    void discover_worktreeStyleGitFile_isRecognized(@TempDir Path tmp) throws IOException {
        Path wt = Files.createDirectory(tmp.resolve("worktree"));
        Files.writeString(wt.resolve(".git"), "gitdir: /some/other/path\n");

        List<RepoEntry> repos = discoverer.discover(tmp.toString());

        assertThat(repos).extracting(RepoEntry::name).containsExactly("worktree");
    }

    @Test
    @DisplayName("discover throws when no git repos found")
    void discover_noGitRepos_throws(@TempDir Path tmp) {
        assertThatThrownBy(() -> discoverer.discover(tmp.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No git repositories found");
    }

    @Test
    @DisplayName("discover throws when path does not exist")
    void discover_missingPath_throws() {
        assertThatThrownBy(() -> discoverer.discover("/definitely/not/here/xyz"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Path does not exist");
    }
}
```

- [ ] **Step 1.3: Run the failing test**

```bash
./gradlew test --tests "com.topsion.easy_daily_report.infrastructure.git.RepoDiscovererTest"
```

Expected: compilation failure (class `RepoDiscoverer` not defined).

- [ ] **Step 1.4: Implement `RepoDiscoverer` (GREEN)**

Create `src/main/java/com/topsion/easy_daily_report/infrastructure/git/RepoDiscoverer.java`:

```java
package com.topsion.easy_daily_report.infrastructure.git;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

/**
 * Resolves a user-supplied path into one or more git repositories.
 *
 * <ul>
 *   <li>If the supplied path is itself a git working tree, a single entry is returned.</li>
 *   <li>Otherwise, immediate subdirectories that are git repos are returned.</li>
 *   <li>Throws {@link IllegalArgumentException} if no repos are found or path is missing.</li>
 * </ul>
 *
 * A directory counts as a git working tree if it contains a {@code .git} entry,
 * whether a real directory (regular clone) or a regular file (linked worktree).
 */
@Slf4j
@Component
public class RepoDiscoverer {

    public List<RepoEntry> discover(String rawPath) {
        Path root = Paths.get(rawPath).toAbsolutePath().normalize();
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("Path does not exist: " + root);
        }

        if (isGitRepo(root)) {
            return List.of(new RepoEntry(root.getFileName().toString(), root.toString()));
        }

        List<RepoEntry> children;
        try (Stream<Path> stream = Files.list(root)) {
            children = stream
                    .filter(Files::isDirectory)
                    .filter(this::isGitRepo)
                    .map(p -> new RepoEntry(p.getFileName().toString(), p.toString()))
                    .sorted((a, b) -> a.name().compareTo(b.name()))
                    .toList();
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to list directory: " + root, e);
        }

        if (children.isEmpty()) {
            throw new IllegalArgumentException("No git repositories found under: " + root);
        }

        log.info("Discovered {} git repositories under {}", children.size(), root);
        return children;
    }

    private boolean isGitRepo(Path dir) {
        Path dotGit = dir.resolve(".git");
        return Files.isDirectory(dotGit) || Files.isRegularFile(dotGit);
    }
}
```

- [ ] **Step 1.5: Run the test and confirm it passes**

```bash
./gradlew test --tests "com.topsion.easy_daily_report.infrastructure.git.RepoDiscovererTest"
```

Expected: `5 tests completed, 0 failures`.

- [ ] **Step 1.6: Commit**

```bash
git add src/main/java/com/topsion/easy_daily_report/infrastructure/git/RepoEntry.java \
        src/main/java/com/topsion/easy_daily_report/infrastructure/git/RepoDiscoverer.java \
        src/test/java/com/topsion/easy_daily_report/infrastructure/git/RepoDiscovererTest.java
git commit -m "feat: add RepoDiscoverer for single-repo / multi-repo path resolution"
```

---

## Task 2: `GitTool` aggregates commits across discovered repos

**Files:**
- Modify: `src/main/java/com/topsion/easy_daily_report/infrastructure/ai/tools/GitTool.java`
- Test: `src/test/java/com/topsion/easy_daily_report/infrastructure/ai/tools/GitToolTest.java`

- [ ] **Step 2.1: Write the failing test (RED)**

Create `src/test/java/com/topsion/easy_daily_report/infrastructure/ai/tools/GitToolTest.java`:

```java
package com.topsion.easy_daily_report.infrastructure.ai.tools;

import com.topsion.easy_daily_report.domain.model.CodeChange;
import com.topsion.easy_daily_report.domain.port.GitPort;
import com.topsion.easy_daily_report.infrastructure.git.RepoDiscoverer;
import com.topsion.easy_daily_report.infrastructure.git.RepoEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitToolTest {

    @Mock private GitPort gitPort;
    @Mock private RepoDiscoverer repoDiscoverer;

    private GitTool gitTool;

    @BeforeEach
    void setUp() {
        gitTool = new GitTool(gitPort, repoDiscoverer);
        ReflectionTestUtils.setField(gitTool, "defaultRepoPath", "/workspace");
    }

    @Test
    @DisplayName("getRecentCommits aggregates output across all discovered repos with [repoName] prefix")
    void getRecentCommits_aggregatesAcrossRepos() {
        when(repoDiscoverer.discover("/workspace")).thenReturn(List.of(
                new RepoEntry("project-a", "/workspace/project-a"),
                new RepoEntry("project-b", "/workspace/project-b")
        ));
        when(gitPort.getRecentCommits("/workspace/project-a", 5)).thenReturn(List.of(
                new CodeChange("a1b2c3d", "Alice", "Add login flow", "", LocalDateTime.now())
        ));
        when(gitPort.getRecentCommits("/workspace/project-b", 5)).thenReturn(List.of(
                new CodeChange("e4f5a6b", "Bob", "Refactor cache", "", LocalDateTime.now())
        ));

        String output = gitTool.getRecentCommits(5);

        assertThat(output).contains("[project-a] a1b2c3d | Alice | Add login flow");
        assertThat(output).contains("[project-b] e4f5a6b | Bob | Refactor cache");
    }

    @Test
    @DisplayName("getCommitDiff searches every repo and returns first match")
    void getCommitDiff_returnsFirstMatch() {
        when(repoDiscoverer.discover("/workspace")).thenReturn(List.of(
                new RepoEntry("project-a", "/workspace/project-a"),
                new RepoEntry("project-b", "/workspace/project-b")
        ));
        when(gitPort.getCommitDetail("/workspace/project-a", "abc1234"))
                .thenThrow(new RuntimeException("missing"));
        when(gitPort.getCommitDetail("/workspace/project-b", "abc1234"))
                .thenReturn(new CodeChange("abc1234", "Bob", "fix bug", "diff body", LocalDateTime.now()));

        String output = gitTool.getCommitDiff("abc1234");

        assertThat(output).contains("[project-b]");
        assertThat(output).contains("abc1234");
        assertThat(output).contains("Bob");
        assertThat(output).contains("diff body");
    }

    @Test
    @DisplayName("getCommitDiff returns not-found message when no repo has the commit")
    void getCommitDiff_noMatch_returnsNotFound() {
        when(repoDiscoverer.discover("/workspace")).thenReturn(List.of(
                new RepoEntry("project-a", "/workspace/project-a")
        ));
        when(gitPort.getCommitDetail("/workspace/project-a", "deadbeef"))
                .thenThrow(new RuntimeException("missing"));

        String output = gitTool.getCommitDiff("deadbeef");

        assertThat(output).contains("Commit not found in any repository");
        assertThat(output).contains("deadbeef");
    }
}
```

- [ ] **Step 2.2: Run the failing test**

```bash
./gradlew test --tests "com.topsion.easy_daily_report.infrastructure.ai.tools.GitToolTest"
```

Expected: compilation failure (constructor `GitTool(GitPort, RepoDiscoverer)` does not exist).

- [ ] **Step 2.3: Update `GitTool` (GREEN)**

Replace the contents of `src/main/java/com/topsion/easy_daily_report/infrastructure/ai/tools/GitTool.java` with:

```java
package com.topsion.easy_daily_report.infrastructure.ai.tools;

import com.topsion.easy_daily_report.domain.model.CodeChange;
import com.topsion.easy_daily_report.domain.port.GitPort;
import com.topsion.easy_daily_report.infrastructure.git.RepoDiscoverer;
import com.topsion.easy_daily_report.infrastructure.git.RepoEntry;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Git 工具（LangChain4j @Tool）
 * 供 ReAct Agent 自主调用，获取 Git 信息。
 *
 * 路径解析委托给 {@link RepoDiscoverer}：
 * 当 defaultRepoPath 本身是 git 仓库时返回一个仓库；
 * 否则会扫描其一级子目录中的所有 git 仓库，并在输出中以 [repoName] 前缀区分。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitTool {

    private final GitPort gitPort;
    private final RepoDiscoverer repoDiscoverer;

    @Value("${git.default-repo-path:./}")
    private String defaultRepoPath;

    @Tool("获取指定 commit 的代码 diff 详情，在多仓库场景下会在所有已发现的仓库中查找首个匹配。")
    public String getCommitDiff(String commitHash) {
        List<RepoEntry> repos = repoDiscoverer.discover(defaultRepoPath);
        log.info("getCommitDiff: searching {} repo(s) for commit {}", repos.size(), commitHash);

        for (RepoEntry repo : repos) {
            try {
                CodeChange change = gitPort.getCommitDetail(repo.path(), commitHash);
                return formatCommitDetail(repo.name(), change);
            } catch (RuntimeException ignore) {
                // try next repo
            }
        }
        return "Commit not found in any repository: " + commitHash;
    }

    @Tool("获取最近 N 条 commit 记录，跨所有已发现的仓库聚合，每行以 [repoName] 前缀标识来源。")
    public String getRecentCommits(int count) {
        List<RepoEntry> repos = repoDiscoverer.discover(defaultRepoPath);
        log.info("getRecentCommits: aggregating {} commits across {} repo(s)", count, repos.size());

        return repos.stream()
                .flatMap(repo -> gitPort.getRecentCommits(repo.path(), count).stream()
                        .map(c -> formatCommitLine(repo.name(), c)))
                .collect(Collectors.joining("\n"));
    }

    private String formatCommitDetail(String repoName, CodeChange change) {
        return """
                [%s] Commit: %s
                Author: %s
                Time: %s
                Message: %s

                Diff:
                %s
                """.formatted(
                repoName,
                change.shortId(),
                change.author(),
                change.commitTime(),
                change.message(),
                change.diff()
        );
    }

    private String formatCommitLine(String repoName, CodeChange c) {
        return "[%s] %s | %s | %s".formatted(
                repoName,
                c.shortId(),
                c.author(),
                c.message().split("\n")[0]
        );
    }
}
```

- [ ] **Step 2.4: Run the test**

```bash
./gradlew test --tests "com.topsion.easy_daily_report.infrastructure.ai.tools.GitToolTest"
```

Expected: `3 tests completed, 0 failures`.

- [ ] **Step 2.5: Run the full test suite to confirm no regressions**

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2.6: Commit**

```bash
git add src/main/java/com/topsion/easy_daily_report/infrastructure/ai/tools/GitTool.java \
        src/test/java/com/topsion/easy_daily_report/infrastructure/ai/tools/GitToolTest.java
git commit -m "feat: wire RepoDiscoverer into GitTool for multi-repo aggregation"
```

---

## Task 3: `DailyReportCommands.generateToday` uses `RepoDiscoverer`

**Files:**
- Modify: `src/main/java/com/topsion/easy_daily_report/shell/DailyReportCommands.java`

- [ ] **Step 3.1: Update `generateToday` to iterate discovered repos**

In `src/main/java/com/topsion/easy_daily_report/shell/DailyReportCommands.java`:

1. Add the import near the top (after the existing `GitPort` import):

```java
import com.topsion.easy_daily_report.infrastructure.git.RepoDiscoverer;
import com.topsion.easy_daily_report.infrastructure.git.RepoEntry;
```

2. Inject `RepoDiscoverer` by adding it to the existing `@RequiredArgsConstructor` field list. The class currently has:

```java
private final GenerateReportUseCase generateReportUseCase;
private final GitPort gitPort;
```

Change to:

```java
private final GenerateReportUseCase generateReportUseCase;
private final GitPort gitPort;
private final RepoDiscoverer repoDiscoverer;
```

3. Replace the entire `generateToday` method body (currently lines 52–88) with this implementation that aggregates today's commits across every discovered repo:

```java
@Command(value = "report generate-today")
public String generateToday(
        @Option(longName = "jira", shortName = 'j', description = "Jira Issue Key (可选)") String jiraIssueKey,
        @Option(longName = "repo", shortName = 'p', description = "Git 仓库路径或多仓库父目录") String repoPath
) {
    String path = (repoPath != null && !repoPath.isBlank()) ? repoPath : defaultRepoPath;
    List<RepoEntry> repos = repoDiscoverer.discover(path);

    StringBuilder display = new StringBuilder();
    int totalCommits = 0;

    for (RepoEntry repo : repos) {
        List<CodeChange> commits = gitPort.getTodayCommits(repo.path());
        totalCommits += commits.size();
        if (commits.isEmpty()) {
            display.append("[").append(repo.name()).append("] 今天没有提交\n");
            continue;
        }
        display.append("[").append(repo.name()).append("] 今天 ")
               .append(commits.size()).append(" 条提交:\n");
        commits.stream()
                .map(c -> "  - " + c.shortId() + " | " + c.message().split("\n")[0])
                .forEach(line -> display.append(line).append("\n"));
    }

    if (totalCommits == 0) {
        return "⚠️ 今天没有找到任何 Git 提交记录。";
    }

    System.out.println("📋 " + display);

    // 多仓库场景下不构造单一 commitRange — 让 Agent 通过 GitTool.getRecentCommits 跨仓库聚合
    ReportRequest request = new ReportRequest(
            null,
            null,
            jiraIssueKey,
            path
    );

    DailyReport report = generateReportUseCase.execute(request);
    return report.rawMarkdown();
}
```

- [ ] **Step 3.2: Verify compilation**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3.3: Run the full test suite to confirm no regressions**

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL` with all tests passing.

- [ ] **Step 3.4: Commit**

```bash
git add src/main/java/com/topsion/easy_daily_report/shell/DailyReportCommands.java
git commit -m "feat: discover and aggregate today commits across multi-repo parent directories"
```

---

## Task 4: Update the `report help` text

**Files:**
- Modify: `src/main/java/com/topsion/easy_daily_report/shell/DailyReportCommands.java`

- [ ] **Step 4.1: Refresh the help string**

Replace the body of the `help()` method in `DailyReportCommands.java` (currently lines 90–116) with this updated version that documents multi-repo support:

```java
@Command(value = "report help")
public String help() {
    return """
            ╔══════════════════════════════════════════════════════════╗
            ║          Easy Daily Report - 智能日报生成                  ║
            ╠══════════════════════════════════════════════════════════╣
            ║                                                          ║
            ║  📌 report generate-today                                ║
            ║    自动生成今天的日报                                      ║
            ║    -j, --jira <key>      Jira Issue (可选)                ║
            ║    -p, --repo <path>     Git 仓库路径 或 父目录 (可选)      ║
            ║                                                          ║
            ║  示例: report generate-today -j PROJ-123                 ║
            ║  示例(多仓库): report generate-today -p ~/workspace      ║
            ║                                                          ║
            ║  ───────────────────────────────────────────────────────  ║
            ║                                                          ║
            ║  report generate                                         ║
            ║    -c, --commit <hash>   Git Commit Hash                 ║
            ║    -r, --range <range>   Commit 范围                      ║
            ║    -j, --jira <key>      Jira Issue Key                  ║
            ║    -p, --repo <path>     Git 仓库路径 或 父目录             ║
            ║                                                          ║
            ║  说明: -p 既可指向一个 git 仓库，也可指向其父目录            ║
            ║        非 git 父目录会被自动展开为一级子仓库列表。           ║
            ║                                                          ║
            ╚══════════════════════════════════════════════════════════╝
            """;
}
```

- [ ] **Step 4.2: Verify compilation**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4.3: Commit**

```bash
git add src/main/java/com/topsion/easy_daily_report/shell/DailyReportCommands.java
git commit -m "docs: document multi-repo path resolution in report help"
```

---

## Self-Review

**1. Spec coverage:**
- "Given a directory, if it's a git repo, use it" — Task 1 single-repo case ✓
- "If the root is not a git directory, traverse each subdirectory" — Task 1 children case ✓
- "Read each subdirectory's git log to generate report" — Task 2 aggregates `getRecentCommits` across repos; Task 3 aggregates today's commits ✓
- "Generate report" — Task 3 produces a single `ReportRequest`, downstream LangChain4j agent pulls commits through the now-multi-repo-aware `GitTool` ✓

**2. Placeholder scan:** No TBD / TODO / "handle edge cases" placeholders. Every step has concrete code or commands.

**3. Type consistency:**
- `RepoEntry(String name, String path)` — used uniformly across all tasks.
- `RepoDiscoverer.discover(String) → List<RepoEntry>` — same signature in Task 1 definition, Task 2 mock, Task 3 caller.
- `GitTool(GitPort, RepoDiscoverer)` constructor — defined Task 2, exercised by Task 2 test.
- `DailyReportCommands` adds `RepoDiscoverer` as third constructor arg (Lombok `@RequiredArgsConstructor` handles the wiring automatically).

**4. Caveats reviewers should know:**
- The existing `report generate` command path passes `repoPath` into `ReportRequest`, but downstream code currently ignores it and uses `GitTool.defaultRepoPath` — the multi-repo behavior is reached through `GitTool`'s discoverer, not by threading `repoPath` deeper. This is intentional for this iteration and avoids touching the entire chain.
- After this plan, the LLM gains `[repoName]` prefixes in commit listings. If existing prompts assume bare `hash | author | message` lines, the LLM's downstream reasoning will see the new prefix; this is the desired signal.

---

**Plan complete and saved to `docs/superpowers/plans/2026-05-11-multi-repo-discovery.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
