---
description: AI-judge E2E test for the daily-report pipeline — runs the real app across all 3 agent modes and grades the generated reports against a grounding rubric (end-to-end C1 proof).
argument-hint: "[commit-hash] (default HEAD)"
allowed-tools: Bash, Read, Grep
---

# AI 测试工作流：日报生成全链路（LLM-as-judge）

你现在是这个项目的**测试执行器 + 评审员**。目标：用**真实 LLM** 跑通 `easy-daily-report` 的三种模式，
并判断生成的日报是否**真正基于真实的 Git 变更**（这是对 C1 修复的端到端验证——若上游分析没进入合成消息，
报告会是泛化/幻觉的，评审会抓到）。

被测 commit：`$1`（为空则用当前 `HEAD`）。测试针对本仓自身的提交（app 的 `git.default-repo-path=./`）。

---

## 阶段 0 · 前置检查（失败则中止并说明）

1. 确认 `.env` 存在且配置了 LLM：`grep -qE 'OPENAI_API_KEY|LLM_' .env` —— 没有 key 无法跑真实模型，直接中止并提示用户先配 `.env`。
2. **无需任何数据库服务器**：存储全嵌入式（DuckDB 向量库 + H2 chat 库，均单文件于 `./data/`），确保 `./data/` 可写即可。
3. 解析被测 commit：`COMMIT=$1`；若为空 `COMMIT=$(git rev-parse HEAD)`。记录 `git log -1 --oneline $COMMIT`。
4. 为避免污染正式库，本次用**隔离的文件**：`export DUCKDB_FILE_PATH=./data/ai-test.duckdb CHAT_DB_PATH=./data/ai-test-chat`，并在开始前清理这两组文件。

## 阶段 1 · 确定性门禁（先跑，快、无需 LLM）

先跑确定性 JUnit E2E，作为 AI 评审前的连通性门禁：

```bash
bash scripts/e2e-test.sh
```

若失败：**直接停止**并报告——连编排/持久化/C1 数据流的确定性测试都没过，AI 评审无意义。

## 阶段 2 · 构建可执行 jar

```bash
./gradlew bootJar -q
# 注意 build/libs 下有两个 jar：可执行 boot jar 与 -plain.jar，务必取非 plain 的那个
JAR=$(ls build/libs/*.jar | grep -v plain | head -1); echo "JAR=$JAR"
```

## 阶段 3 · 抓取「真值」——被测 commit 的真实 diff

```bash
git show --stat $COMMIT | head -60
git show $COMMIT | head -400
```
记住其中**真实变更的文件名、关键改动、涉及的技术**——这是评审 grounding 的标尺。

## 阶段 4 · 三种模式各跑一次，捕获真实报告

对 `LEVEL ∈ {SINGLE, SAMPLE_MULTIPLE, COORDINATOR_AGENT}` 依次执行（Spring Shell 非交互模式：传参即运行后退出）：

```bash
# ⚠ 已实测：本项目 spring.shell.interactive.enabled=true，把命令作为「程序参数」传给 java 会被
#   Spring Shell 忽略（日志打印 "Running in interactive mode, arguments will be ignored"）。
#   可靠做法 = 通过 stdin 把「命令 + exit」喂给交互式 REPL（实测 exit 0 且产出命令输出）。
printf 'report generate -c %s -l %s\nexit\n' "$COMMIT" "<LEVEL>" \
  | java -jar "$JAR" > /tmp/report-<LEVEL>.md 2> /tmp/report-<LEVEL>.err
```
- 不传 `-j`（Jira 可选；不配 Jira 时 app 会走降级路径，这本身也是 fail-soft 的一次真实检验）。
- 每次运行后从 `/tmp/report-<LEVEL>.md` 里**提取 Markdown 报告正文**（跳过 Spring Boot banner / 日志行）。
- 若某模式运行异常（非零退出/无报告），记为该模式 FAIL 并附 `.err` 关键行，不要中断其余模式。

## 阶段 5 · 逐模式按 rubric 评审（这是核心）

对每个模式的报告，用你读到的**真实 diff**做标尺打分：

| 维度 | 判据 | 结果 |
|---|---|---|
| **结构完整** | 是否包含日报应有的分节（任务概述 / 代码变更要点 / 业务价值 / 风险与建议 / 明日计划 之类） | PASS/FAIL |
| **Grounding（C1 关键）** | 「代码变更/技术实现」是否**确实对应真实 diff 里的文件与改动**（能从报告里指认出 diff 中真实存在的文件名/改动点），而非泛泛而谈或臆造 | 0–5 分 + 证据 |
| **无幻觉** | 是否杜撰了 diff 中不存在的文件、技术栈或 Jira 内容 | PASS/FAIL |
| **无模板泄漏** | 是否残留字面占位符（`{日期}`、`{{gitAnalysisJson}}`、`{files_count}` 等未被替换的花括号 token） | PASS/FAIL |

**判定基线（重点解释给用户）**：C1 修复前，`SAMPLE_MULTIPLE` 与 `COORDINATOR_AGENT` 的合成器收不到分析数据，
其 Grounding 会明显低（报告与真实 diff 无关）。修复后三种模式都应 Grounding ≥ 4。若任一“高级”模式 Grounding 骤降，
即 C1 端到端回归的信号。

## 阶段 6 · 持久化抽检（best-effort）

```bash
ls -l ./data/ai-test.duckdb   # 存在且非空即说明报告已落库
# 若本机装了 duckdb CLI，可进一步：
command -v duckdb >/dev/null && duckdb ./data/ai-test.duckdb "SELECT count(*) FROM report_embeddings;" || echo "duckdb CLI 未安装，跳过行数校验（JUnit 已确定性覆盖持久化）"
```
（权威的持久化/检索验证由确定性 JUnit E2E 覆盖；此处只做冒烟。）

## 阶段 7 · 汇总

输出一张 **模式 × 维度** 的结果表 + 总判定（PASS/FAIL），并对每个 FAIL/低分给出**具体证据**
（引用报告里的句子 vs. diff 里的真实改动）。最后清理：`rm -f ./data/ai-test.duckdb`。

结尾用一句话点明：确定性 JUnit E2E（阶段 1）保「连通性/正确性」，本 AI 评审（阶段 5）保「质量/grounding」，
两层合起来才是这条流水线的完整测试。
