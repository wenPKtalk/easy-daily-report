# LANGGRAPH_RAG 模式设计文档

> 状态：设计中（§1–§5 已落地，§6 实现计划待补）
> 适用范围：`easy-daily-report` MVP3，新增 `--level LANGGRAPH_RAG` 报告生成模式
> 关键设计点：以受控的状态图（state graph）承载 git+jira 并行分析、RAG 检索循环、生成质量循环和优雅降级

---

## § 1. 设计范围与方案选型

### 1.1 目标

为 `easy-daily-report` 增加第四种生成策略 `LANGGRAPH_RAG`，对应 `AgentLevel.LANGGRAPH_RAG`。在保留 SINGLE / SAMPLE_MULTIPLE / COORDINATOR_AGENT 三档现有模式的前提下，提供一种**确定性可控、可观测、可降级**的多步生成流程，专门用于：

- 跨数据源并行采集（git diff + Jira issue）
- 基于历史报告的 RAG 检索带相关性评分与重写
- 报告草稿质量评分与有限次回炉
- 在任一外部依赖失败时仍能交付一份"尽力而为"的报告

### 1.2 为什么不复用现有三档

| 模式 | 局限 |
| --- | --- |
| `SINGLE` (ReAct + Tool) | LLM 决定调用顺序、不可预测；RAG 仅有一次检索，无相关性评分 |
| `SAMPLE_MULTIPLE` (静态并行) | 不能表达 _"先检索，若不相关则改写查询再来一次"_ 这种条件循环 |
| `COORDINATOR_AGENT` (Master/Sub ReAct) | LLM 动态调度，灵活但成本高、观测困难，且循环靠 LLM 自觉，无硬性上限 |

LANGGRAPH_RAG 解决的正是 **"流程是固定的、条件分支也是固定的，但要能并行 + 循环 + 降级"** 这个夹缝。

### 1.3 方案 A vs 方案 B

| 维度 | 方案 A：线性流水线 + 内嵌 if | **方案 B（选定）：完整 10 节点状态图** |
| --- | --- | --- |
| 表达力 | 适合线性 RAG，循环靠 while loop | 原生支持 fan-out / barrier-join / conditional / loop |
| 并行 | git/jira 用 `CompletableFuture.allOf`，写死在编排器里 | 拓扑里显式声明，merge 行为可单测 |
| 降级 | 散落在 try-catch 里 | 统一通过 `RagMode` 字段 + 条件边表达 |
| 测试 | 难，路径靠注释 | 每个节点纯函数 + 每条边可单测 |
| 与未来 LangGraph4j 接轨 | 改动大 | 自带 DSL，必要时替换执行引擎成本可控 |

**选定方案 B。** 接受多写一层 DSL/执行器的代价，换来后续两件事的低成本：
1. 增加 / 调整流程中的某个判断节点（例如新增 "用户偏好" 节点）；
2. 把执行器从自研换成 LangGraph4j 或别的实现时，节点本身无需改动。

### 1.4 范围与非目标

**Scope（本期）**
- 单次同步执行：`graph.run(initialState)` → 阻塞返回 `DailyReport`
- 节点级超时 + 全图墙钟超时
- RAG 数据源：复用 `PgVectorReportStore`（与 SINGLE/COORDINATOR 模式共享）
- 重试上限：硬编码上限 + 配置可调

**Non-goals（本期不做）**
- 长跑工作流 / 检查点（checkpointing）/ 持久化中间状态
- 人在环（HITL）暂停与恢复
- 跨多次运行的图状态记忆
- 流式输出（节点级 token stream）
- 分布式节点执行（所有节点在同一 JVM）

---

## § 2. GraphState 设计

`GraphState` 是图中唯一被读写的数据载体。所有节点 **纯函数** 地消费旧 state、产出新 state，**绝不就地修改**。

### 2.1 字段定义

```java
public record GraphState(
    // ---- 输入（不可变） ----
    ReportRequest input,

    // ---- 并行分支产物 ----
    GitAnalysis gitAnalysis,       // analyze_git 写入；null 表示分支未跑或失败
    JiraAnalysis jiraAnalysis,     // analyze_jira 写入；null 同上

    // ---- 检索循环相关 ----
    String initialQuery,           // merge_analyses 写入，整图只赋值一次
    String currentQuery,           // retrieve 读取；transform_query 重写
    List<RetrievedDoc> retrievedDocs,
    Boolean docsRelevant,          // grade_docs 写入
    int retrievalAttempts,         // 单调递增

    // ---- 生成循环相关 ----
    String draftReport,            // generate 写入
    ReportGrade reportGrade,       // grade_report 写入
    int generationAttempts,        // 单调递增

    // ---- 全局降级状态 ----
    RagMode ragMode,               // ON / DEGRADED / OFF

    // ---- 终态 ----
    DailyReport finalReport,       // 唯一由 finalize 写入

    // ---- 可观测性 ----
    List<NodeError> errors,        // append-only 累积
    int totalNodeExecutions        // 硬上限保护
) {
    public enum RagMode { ON, DEGRADED, OFF }
}
```

`with*` 方法由 record 衍生，或显式手写一组 `withGitAnalysis(...)` 等帮助函数。

### 2.2 不变量（构造时校验，必要时 assert）

| 时机 | 不变量 |
| --- | --- |
| 任何时刻 | `retrievalAttempts >= 0 && retrievalAttempts <= cfg.maxRetrievalAttempts` |
| 任何时刻 | `generationAttempts >= 0 && generationAttempts <= cfg.maxGenerationAttempts` |
| 任何时刻 | `totalNodeExecutions <= cfg.maxTotalNodeExecutions` |
| `merge_analyses` 之后 | `gitAnalysis != null \|\| jiraAnalysis != null`（详见 §5.4） |
| `grade_docs` 之后 | `docsRelevant != null` |
| `finalize` 之后 | `finalReport != null` |

### 2.3 并行分支的字段级合并（barrier-join 语义）

`analyze_git` 与 `analyze_jira` 同时运行，各自基于同一份 _初始 state_ 派生出新的 state。在 barrier 处合并时，遵循 **字段所有权 (field ownership)** 原则：

> 每个并行分支只允许写它自有的字段，合并时按字段取 _非默认值_ 即可，永不冲突。

```java
static GraphState mergeBranches(GraphState base, GraphState leftBranch, GraphState rightBranch) {
    return base
        .withGitAnalysis(firstNonNull(leftBranch.gitAnalysis(),  rightBranch.gitAnalysis()))
        .withJiraAnalysis(firstNonNull(leftBranch.jiraAnalysis(), rightBranch.jiraAnalysis()))
        .withErrors(concat(base.errors(), leftBranch.errors(), rightBranch.errors()))
        .withTotalNodeExecutions(
            base.totalNodeExecutions()
              + leftBranch.totalNodeExecutions()  - base.totalNodeExecutions()
              + rightBranch.totalNodeExecutions() - base.totalNodeExecutions());
}
```

> 设计取舍：刻意不引入 reducer 注册表。10 节点规模下，硬编码 merge 函数读起来比通用 reducer 直白；如果将来扩到 20+ 节点再抽象。

---

## § 3. State Graph DSL

DSL 的核心是一组 sealed 类型 + 一个 _基于 frontier（执行前沿）的解释执行器_，目标是 **小、可测、不依赖第三方图运行时**。

### 3.1 Edge 类型层级

```java
public sealed interface Edge
        permits Direct, Conditional, FanOut, BarrierJoin, Terminal {
    NodeId from();  // BarrierJoin 例外，详见下
}

public record Direct(NodeId from, NodeId to) implements Edge {}

public record Conditional(
        NodeId from,
        Function<GraphState, NodeId> router,   // 必须返回声明过的下游节点
        Set<NodeId> targets                    // 用于静态校验
) implements Edge {}

public record FanOut(NodeId from, List<NodeId> targets) implements Edge {}

public record BarrierJoin(
        Set<NodeId> sources,
        NodeId to,
        TernaryMerge merger                    // (base, leftResult, rightResult) -> mergedState
) implements Edge {
    public NodeId from() { return NodeId.JOIN; }  // 虚拟节点
}

public record Terminal(NodeId from) implements Edge {}
```

`router` 是一个纯函数，输入当前 state、返回下一个节点 ID。`Conditional.targets` 仅用于 _图构建期_ 静态校验（"router 不会指向未声明的节点"）。

### 3.2 Node 接口

```java
public interface Node {
    NodeId id();

    /** 同步执行；超时由执行器在外层包装。纯函数语义：禁止修改入参。 */
    GraphState execute(GraphState state) throws NodeExecutionException;
}
```

节点不感知图结构，不感知配置以外的全局状态，便于单测。

### 3.3 Graph 与 Frontier 执行器

```java
public final class Graph {
    private final NodeId start;
    private final Map<NodeId, Node> nodes;
    private final Map<NodeId, Edge> outEdges;    // 每个非 BarrierJoin 节点对应一条出边
    private final List<BarrierJoin> barriers;

    public GraphState run(GraphState initial, GraphConfig cfg) {
        Set<NodeId> frontier = Set.of(start);
        GraphState state = initial;
        long deadline = System.nanoTime() + cfg.totalTimeout().toNanos();

        while (!frontier.isEmpty()) {
            checkGlobalLimits(state, cfg, deadline);

            // 1) 并行执行 frontier 上的所有节点
            Map<NodeId, GraphState> branchOutputs = executeFrontier(frontier, state, cfg);

            // 2) 合并：单节点直接接管；多节点走对应 BarrierJoin
            state = mergeFrontier(state, frontier, branchOutputs);

            // 3) 计算下一个 frontier
            frontier = advance(frontier, state);
        }
        return state;
    }
}
```

关键约束：
- **frontier 内所有节点视为并行可执行**；单节点时退化为顺序。
- **conditional 边在 advance 阶段求值**，仅当当前节点位于 frontier 中。
- **回边（loop-back）合法**：`transform_query → retrieve`、`grade_report → generate` 由 conditional 边自然表达，不需要额外构造。
- **超时与执行计数**：在 `checkGlobalLimits` 中统一防御，超限直接退化为 `finalize` frontier。

### 3.4 静态校验（图构建期一次性完成）

- 每个声明过的节点都有进入边和出边（除 start / Terminal.from）。
- `Conditional.router` 的可能返回值必须 ⊆ `Conditional.targets`，且 `targets` 内的节点都已注册。
- `BarrierJoin.sources` 必须是某个 `FanOut.targets` 的子集（或在同一 frontier 上）。
- 节点 ID 全集与边引用 ID 全集一致，无悬挂引用。

校验失败抛 `IllegalGraphException`，由配置类在 Spring 启动阶段触发，**绝不进入运行时**。

---

## § 4. 拓扑：10 节点 + 8 边

### 4.1 节点速览

| # | NodeId | 读取字段 | 写入字段 | 说明 |
| --- | --- | --- | --- | --- |
| 1 | `ANALYZE_GIT` | `input` | `gitAnalysis` | 走 `GitPort` 提取 commit/diff 摘要 |
| 2 | `ANALYZE_JIRA` | `input` | `jiraAnalysis` | 走 `JiraPort` 拉 issue 摘要 |
| 3 | `MERGE_ANALYSES` | `gitAnalysis`, `jiraAnalysis` | `initialQuery`, `currentQuery` | 拼接生成首次检索 query |
| 4 | `RETRIEVE` | `currentQuery` | `retrievedDocs`, `retrievalAttempts++` | 调 `ReportStore.searchSimilar` |
| 5 | `GRADE_DOCS` | `retrievedDocs`, `initialQuery` | `docsRelevant` | LLM 二分相关性评分 |
| 6 | `TRANSFORM_QUERY` | `currentQuery`, `retrievedDocs` | `currentQuery` | LLM 改写 query；attempts 已在 RETRIEVE 累加 |
| 7 | `DEGRADE_NO_RAG` | — | `ragMode = OFF`, `retrievedDocs = []` | 标记 RAG 不可用 |
| 8 | `GENERATE` | `gitAnalysis`, `jiraAnalysis`, `retrievedDocs`, `ragMode` | `draftReport`, `generationAttempts++` | LLM 起草报告 |
| 9 | `GRADE_REPORT` | `draftReport` | `reportGrade` | LLM 二分质量评分 |
| 10 | `FINALIZE` | `draftReport`, `ragMode`, `errors` | `finalReport` | 拼装元数据、收口 |

### 4.2 边定义（8 条）

| # | 类型 | 关系 | 路由逻辑 |
| --- | --- | --- | --- |
| E1 | FanOut | start → {ANALYZE_GIT, ANALYZE_JIRA} | — |
| E2 | BarrierJoin | {ANALYZE_GIT, ANALYZE_JIRA} → MERGE_ANALYSES | `mergeBranches` |
| E3 | Direct | MERGE_ANALYSES → RETRIEVE | — |
| E4 | Direct | RETRIEVE → GRADE_DOCS | — |
| E5 | **Conditional (3-way)** | GRADE_DOCS → {GENERATE, TRANSFORM_QUERY, DEGRADE_NO_RAG} | 见下方 router |
| E6 | Direct | TRANSFORM_QUERY → RETRIEVE（**回边**） | — |
| E7 | Direct | DEGRADE_NO_RAG → GENERATE | — |
| E8 | **Conditional (2-way)** | GRADE_REPORT → {FINALIZE, GENERATE（**回边**）} | 见下方 router |

> 备注：`GENERATE → GRADE_REPORT` 和 `FINALIZE → Terminal` 是出边声明的一部分，但归类为 _节点自带出口_，不计入这 8 条业务边。下文 §4.3 给出完整 outEdges map。

### 4.3 路由器伪代码

```java
// E5：检索结果分诊
NodeId routeAfterGradeDocs(GraphState s, GraphConfig cfg) {
    if (Boolean.TRUE.equals(s.docsRelevant())) {
        return NodeId.GENERATE;                       // 相关 → 进入生成
    }
    if (s.retrievalAttempts() < cfg.maxRetrievalAttempts()) {
        return NodeId.TRANSFORM_QUERY;                // 还有预算 → 改写后重试
    }
    return NodeId.DEGRADE_NO_RAG;                     // 预算耗尽 → 无 RAG 兜底
}

// E8：生成质量分诊
NodeId routeAfterGradeReport(GraphState s, GraphConfig cfg) {
    if (s.reportGrade() == ReportGrade.PASS) {
        return NodeId.FINALIZE;
    }
    if (s.generationAttempts() < cfg.maxGenerationAttempts()) {
        return NodeId.GENERATE;                       // 回炉重写
    }
    return NodeId.FINALIZE;                           // 用尽预算仍交付，但带 lowConfidence 标记
}
```

### 4.4 配置项（externalized to `application.yml`）

```yaml
easy-daily-report:
  langgraph:
    max-retrieval-attempts: 3        # E5 触发 TRANSFORM_QUERY 的上限
    max-generation-attempts: 2       # E8 触发 GENERATE 回炉的上限
    max-total-node-executions: 50    # 硬性兜底，防止图层 bug 导致死循环
    total-timeout: 5m                # 全图墙钟超时
    node-timeout: 60s                # 单节点超时
    retrieve:
      top-k: 5
      min-score: 0.3
    grade-docs:
      enabled: true                  # 关掉则视为永远 relevant
    grade-report:
      enabled: true
```

### 4.5 典型路径（happy path）

```
start
 ├─▶ ANALYZE_GIT ─┐
 └─▶ ANALYZE_JIRA┘
                 ▼
           MERGE_ANALYSES
                 ▼
              RETRIEVE ◀─────────┐
                 ▼               │
            GRADE_DOCS           │
                 │  irrelevant   │
                 ├──────▶ TRANSFORM_QUERY ┘
                 │
                 │  irrelevant & 预算耗尽
                 ├──────▶ DEGRADE_NO_RAG ───┐
                 │                          ▼
                 │   relevant            GENERATE ◀───┐
                 └────────────────────────▶│         │
                                           ▼         │
                                      GRADE_REPORT   │
                                           │  fail   │
                                           ├─────────┘
                                           │  pass / 预算耗尽
                                           ▼
                                        FINALIZE
                                           ▼
                                          end
```

---

## § 5. 异常处理 / 降级语义 / 边界

> 设计哲学：**节点对失败诚实，图对用户负责。** 节点遇错就如实抛 `NodeExecutionException`；执行器把异常翻译成 _ragMode 降级_ 或 _分支空对象_ 等可继续的状态，必要时短路到 `FINALIZE` 给出一份带警告的报告。

### 5.1 错误分类

| 类别 | 触发场景 | 处理方 | 默认动作 |
| --- | --- | --- | --- |
| **TRANSIENT** | 网络抖动、HTTP 5xx、DB timeout | 节点内 | 指数退避重试（默认 3 次，可配） |
| **PERMANENT_INFRA** | 凭证失效、host 不可达、重试耗尽 | 执行器 | 转换为分支降级（见 §5.3） |
| **BUSINESS** | LLM 返回的 JSON 无法解析、评分越界 | 执行器 | 节点级有限重试，否则用安全默认值 |
| **DATA** | 空 commit、Jira issue 不存在 | 节点内 | 返回 _空对象_，状态记笔记，分支视为成功完成 |
| **GRAPH** | router 指向未声明节点、节点超 frontier 频度 | 执行器 | 直接 `IllegalGraphException`，整图失败 |

`NodeError` 字段：

```java
public record NodeError(
    NodeId nodeId,
    ErrorCategory category,   // 上表
    String message,
    Throwable cause,          // nullable
    Instant occurredAt
) {}
```

### 5.2 节点级契约：`NodeResult`

在节点接口层显式区分成功 / 业务失败：

```java
public sealed interface NodeResult permits NodeResult.Success, NodeResult.Failure {
    record Success(GraphState state) implements NodeResult {}
    record Failure(NodeError error, GraphState fallbackState) implements NodeResult {}
}
```

执行器调用约定：

1. 节点首选返回 `Success`。
2. 节点遇可恢复业务错误时返回 `Failure(error, fallbackState)`：执行器把错误 append 到 `state.errors()`，并采用 `fallbackState`（通常等同于 input + 空对象）。
3. 节点遇不可恢复错误时抛 `NodeExecutionException`：执行器按节点类型决定 _分支降级_ 还是 _整图短路到 FINALIZE_。

> 注：节点内的 TRANSIENT 重试 **不向上抛**，对执行器透明；执行器只看终态。

### 5.3 分支级降级

| 节点 | 抛出永久异常时的降级动作 |
| --- | --- |
| `ANALYZE_GIT` | `gitAnalysis = GitAnalysis.empty(note="git unavailable")`；分支视为成功完成 |
| `ANALYZE_JIRA` | `jiraAnalysis = JiraAnalysis.empty(note="jira unavailable")` |
| `RETRIEVE` | `retrievedDocs = []`，`docsRelevant = false`；进入 E5 → 触发 TRANSFORM_QUERY 或 DEGRADE_NO_RAG |
| `GRADE_DOCS` | 兜底为 `docsRelevant = true`，跳过怀疑分支（避免阻塞）；记 WARN |
| `TRANSFORM_QUERY` | 兜底为 _identity 改写_，`retrievalAttempts` 仍递增（否则死循环） |
| `GENERATE` | `generationAttempts` 已耗尽则短路到 `FINALIZE`，输出带 `error-only` 报告；否则 router 会再次触发它 |
| `GRADE_REPORT` | 兜底为 `ReportGrade.PASS`；下游 router 进入 `FINALIZE` |
| `FINALIZE` | 兜底输出 minimal report（只含 ReportRequest 摘要 + errors 列表） |

**`MERGE_ANALYSES` 的双失败联防（§2.2 不变量）**：
- 若 `gitAnalysis == null && jiraAnalysis == null`，节点抛 `NodeExecutionException(category=DATA)`，**整图短路到 FINALIZE**，输出 `DailyReport.degraded(reason="no source data")`。
- 这是唯一一个永久性失败导致图不继续推进生成的情况。

### 5.4 全局降级：`RagMode` 状态机

```
              ┌─────────── retrieve 失败/无结果 (E5 兜底) ──────────┐
              ▼                                                     │
        ┌──────────┐   docs 相关                ┌──────────┐         │
  ON ──▶│   ON     │──────────────▶ generate──▶│   ON     │         │
        └────┬─────┘                            └──────────┘         │
             │ docs 不相关且预算耗尽                                  │
             ▼                                                       │
        ┌──────────┐                                                 │
        │ DEGRADED │ ──── generate ────────▶  finalize 带提示        │
        └──────────┘                                                 │
                                                                     │
        ┌──────────┐                                                 │
        │   OFF    │ ◀────────────────────────────────────────────── ┘
        └──────────┘
        retrieve 节点抛永久异常 → 跳过 grade_docs，直接 OFF
```

转移规则：
- `ON → DEGRADED`：检索成功但相关性评分始终 false 且 `retrievalAttempts == max`
- `ON → OFF`：`RETRIEVE` 节点彻底失败 / 配置关闭 RAG / `maxRetrievalAttempts == 0`
- `DEGRADED → *` 与 `OFF → *`：**单向**，不再回升

`FINALIZE` 根据 `ragMode` 生成的报告元数据：

| ragMode | 报告 footer |
| --- | --- |
| ON | `Sources: N similar past reports` |
| DEGRADED | `Sources: similar reports found but low relevance — synthesized with caution` |
| OFF | `Sources: none — RAG unavailable; report based on git/jira only` |

### 5.5 超时与硬上限

| 控制 | 默认 | 失败时行为 |
| --- | --- | --- |
| `node-timeout` (单节点) | 60s | 节点视为永久异常 → 走 §5.3 分支降级 |
| `total-timeout` (整图墙钟) | 5min | 执行器在 frontier 推进前检查；超时则把 frontier 替换为 `{FINALIZE}` |
| `max-total-node-executions` | 50 | 同上，硬兜底防止 router bug |
| `max-retrieval-attempts` | 3 | E5 router 切换到 `DEGRADE_NO_RAG` |
| `max-generation-attempts` | 2 | E8 router 切换到 `FINALIZE` |

> 关键不变量：**任何时候，frontier 必须能在有限步内到达 FINALIZE**。
> 由 2 条业务计数器 + 1 条总执行次数 + 1 条墙钟超时共同保证，四者任一触顶都会强制收口。

### 5.6 边界条件清单（已知 & 测试目标）

| 边界 | 期望行为 |
| --- | --- |
| 空 commit range（git diff 为空） | `ANALYZE_GIT` 返回 `GitAnalysis.empty(reason="no changes")`；图继续推进，最终报告标注 "no code changes" |
| Jira issue key 缺失 / 找不到 | `ANALYZE_JIRA` 返回空对象 + warning；图继续 |
| git + jira 都为空 | `MERGE_ANALYSES` 短路到 FINALIZE（degraded report） |
| `maxRetrievalAttempts = 0` | E5 永远进入 `DEGRADE_NO_RAG`，等同于关掉 RAG |
| `grade-docs.enabled = false` | `GRADE_DOCS` 直接返回 `docsRelevant = true` |
| `grade-report.enabled = false` | `GRADE_REPORT` 直接返回 `PASS` |
| LLM 输出超长 / 编码异常 | 节点内捕获并归类为 BUSINESS，按 §5.2 兜底 |
| 历史报告库为空（冷启动） | `RETRIEVE` 返回空列表，进入 E5 兜底，最终走 OFF |

### 5.7 可观测性接入点

- 每个节点的 _start / end / duration / error_ 在 SLF4J 以 INFO/WARN 级别打印，含 `runId`、`nodeId`、`retrievalAttempts`、`generationAttempts`。
- `state.errors()` 在 `FINALIZE` 中聚合，写入最终 `DailyReport.metadata.warnings`，方便 RAG 之后回放复盘。
- 全图执行轨迹（frontier 序列）由执行器收集，可选打印；默认仅在 WARN/ERROR 时输出。
- 与 `spring-boot-actuator` 无强耦合；如需 Prometheus 指标，留出 `GraphMetricsRecorder` 接口（暂不实现）。

### 5.8 失败但仍交付报告的语义保证

| 失败组合 | 是否仍交付 `DailyReport` | Footer 提示 |
| --- | --- | --- |
| 任何单分支降级（git or jira 空） | 是 | `partial-source` |
| RAG 全部失败 | 是 | `no-rag` |
| 生成回炉耗尽但有 draft | 是 | `low-confidence` |
| MERGE_ANALYSES 双空 | **是（degraded）** | `no-source-data` |
| 整图超时 | **是（最后已知状态）** | `timeout` |
| 编排器自身抛异常（IllegalGraphException 等） | 否，向上抛 | — |

---

## § 6. 实现计划

> 总原则：domain → infra → application → shell **逐层往上**，每一层先写测试再写实现。
> 中间任何一层都可以用 _stub 节点_（直接返回 fixed state）先打通 DSL，再逐个替换为真实节点。

### 6.1 包结构

```
domain/langgraph/                 ← 纯 Java，零外部依赖
  GraphState.java                 (含 RagMode 嵌套 enum)
  NodeId.java                     (enum：10 个节点 + JOIN 虚节点)
  Node.java                       (interface)
  NodeResult.java                 (sealed + Success/Failure 嵌套)
  NodeError.java                  (含 ErrorCategory enum)
  Edge.java                       (sealed + Direct/Conditional/FanOut/BarrierJoin/Terminal 嵌套)
  Graph.java                      (Builder 构造 + 静态校验)
  GraphRunner.java                (frontier 执行器)
  GraphConfig.java                (record，与 application.yml 绑定)
  exceptions/
    NodeExecutionException.java
    IllegalGraphException.java
  values/
    GitAnalysis.java
    JiraAnalysis.java
    RetrievedDoc.java
    ReportGrade.java

infrastructure/langgraph/         ← 适配现有 Port + LangChain4j
  nodes/
    AnalyzeGitNode.java           (← GitPort)
    AnalyzeJiraNode.java          (← JiraPort)
    MergeAnalysesNode.java        (纯函数)
    RetrieveNode.java             (← ReportStore.searchSimilar)
    GradeDocsNode.java            (← DocsGrader AiService)
    TransformQueryNode.java       (← QueryTransformer AiService)
    DegradeNoRagNode.java         (纯函数)
    GenerateNode.java             (← DailyReportGenerator AiService，复用 SINGLE 现有)
    GradeReportNode.java          (← ReportGrader AiService)
    FinalizeNode.java             (纯函数)
  ai/
    DocsGrader.java               (@AiService，二分相关性)
    ReportGrader.java             (@AiService，二分质量)
    QueryTransformer.java         (@AiService，query 改写)
  LangGraphConfig.java            (@Configuration，组装 Graph bean)

application/
  LangGraphRagOrchestrator.java   (implements GenerateAgent)
  AgentRouter.java                (新增 LANGGRAPH_RAG 分支)

shell/
  ReportCommands.java             (--level LANGGRAPH_RAG 已由 AgentLevel enum 自动支持)
```

### 6.2 分阶段交付

| 阶段 | 范围 | 验收 |
| --- | --- | --- |
| **A. DSL 骨架** | `domain/langgraph/` 全部 + `GraphRunner` | `GraphRunnerTest` 用 stub 节点覆盖：直边、条件边、fan-out/barrier、循环回边、节点超时、总执行次数上限 |
| **B. 节点实现** | `infrastructure/langgraph/nodes/` 10 个节点 + 3 个 AiService | 每个节点独立单测；mock 掉 Port / AiService；显式覆盖 §5.3 的降级路径 |
| **C. 图组装** | `LangGraphConfig` 把 10 节点 + 8 边装成 `Graph` bean | Spring 上下文加载测试通过；启动时静态校验跑过 |
| **D. 编排接入** | `LangGraphRagOrchestrator` 实现 `GenerateAgent`；`AgentRouter` 加分支 | `AgentRouterTest` 覆盖 LANGGRAPH_RAG → orchestrator 的路由 |
| **E. CLI + E2E** | `--level LANGGRAPH_RAG` 端到端运行 | 集成测试逐一覆盖 §5.6 的 8 条边界 |

> 阶段 A、B 可以并行：A 用 stub 节点验证 DSL 不依赖 B；B 用单测验证节点不依赖 A 的执行器。

### 6.3 测试矩阵（与 §5.6 对齐）

| 场景 | 阶段 | 类型 | 期望终态 |
| --- | --- | --- | --- |
| Happy path | E | E2E | `ragMode=ON`, footer="N similar past reports" |
| 空 commit range | B+E | unit + E2E | git 分支空对象、报告标注 no code changes |
| Jira issue 缺失 | B+E | unit + E2E | jira 分支空对象、报告标注 |
| git + jira 双空 | E | E2E | `DailyReport.degraded(reason="no source data")` |
| `max-retrieval-attempts=0` | A+E | unit + E2E | 直接走 DEGRADE_NO_RAG、`ragMode=OFF` |
| `grade-docs.enabled=false` | B | unit | GRADE_DOCS 返回 `docsRelevant=true` |
| `grade-report.enabled=false` | B | unit | GRADE_REPORT 返回 PASS |
| 报告库为空（冷启动） | E | E2E | retrieve 返回 []、走 DEGRADE_NO_RAG |
| 整图墙钟超时 | A | unit | frontier 被替换为 {FINALIZE}、footer="timeout" |
| 节点超时 | A | unit | 节点视为永久异常、走对应分支降级 |

### 6.4 实施时不变的设计约束（提醒自己）

1. **节点必须是纯函数语义**：输入 state → 输出 state，不持有可变成员；外部依赖（Port、AiService）通过构造注入。
2. **绝不修改入参 GraphState**：所有变更走 `with*` 方法（参见 §2 + 全局 `coding-style.md` 的 immutability 强制要求）。
3. **节点不感知自己在图中的位置**：不要在节点里读 `currentNodeId` / `frontier`；路由决策只在 router 函数里。
4. **错误一律落到 `state.errors()` 列表**：不静默吞，不抛 RuntimeException 跨节点边界（除 §5.1 的 GRAPH 类）。
5. **配置只在 `GraphConfig` 一处加载**：节点不要直接读 `@Value`；执行器在 `run()` 入口把 cfg 透传给节点。

### 6.5 风险与待澄清

- **AiService 三件套（DocsGrader / ReportGrader / QueryTransformer）的 prompt 设计**：当前文档没给出 prompt 模板，落地时需要专门一轮 prompt engineering。建议先用 zero-shot + 简单 instruction，在 §6.2-B 阶段单测里固化期望输出格式。
- **RetrievedDoc 与现有 `ReportStore.searchSimilar` 的返回类型对齐**：可能需要在 `domain/langgraph/values/RetrievedDoc.java` 与现有 `PgVectorReportStore` 之间加一层映射；视实际类型而定。
- **`GenerateNode` 是否复用 SINGLE 模式的 `DailyReportAgent`**：复用可以省一份 prompt，但 `DailyReportAgent` 自带 Tool 调用、ContentRetriever，行为模型与 LANGGRAPH_RAG 的"已经检索好了，只生成"不一致。建议**新建** `DailyReportGenerator` AiService（无 Tool、无 RAG，纯生成）。
- **观测性集成深度**：本期只到 SLF4J 日志 + `state.errors()`；如果后续要接 OpenTelemetry，`GraphRunner` 需要预留 hook，但本期不动手。
