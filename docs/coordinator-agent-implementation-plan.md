# COORDINATOR_AGENT 分阶段实施计划

> 状态：草稿 / 待批准
> 目标：实现 `AgentLevel.COORDINATOR_AGENT` 模式，让 LLM-Driven Master Agent 通过 DelegationTool 动态调度 4 个 Sub-Agent。
> 设计蓝图：[`docs/sample_multiple_sub_agent.md`](./sample_multiple_sub_agent.md)（lines 36–161）
> 当前阻塞点：`application/usecase/AgentRouter.java:18` 对 COORDINATOR_AGENT 抛 `UnsupportedOperationException`。

---

## Phase 0 — 文档发现与决策（已完成，本阶段产物）

### 0.1 已盘点的事实（不要在后续阶段再次重复调研）

| 项目 | 事实 | 位置 |
|---|---|---|
| **GenerateAgent 接口** | `DailyReport execute(ReportRequest request)` | `application/usecase/GenerateAgent.java`（全文 9 行） |
| **AgentLevel 枚举** | `SINGLE / SAMPLE_MULTIPLE / COORDINATOR_AGENT` 三值 | `application/usecase/AgentLevel.java`（全文 11 行） |
| **AgentRouter 装配** | `@Component @RequiredArgsConstructor`，注入 `GenerateReportUseCase` + `MultiAgentOrchestrator`，switch 表达式分发 | `application/usecase/AgentRouter.java`（全文 21 行） |
| **GitDiffAnalyzerAgent** | `String analyze(@UserMessage String gitDiff)` — 返回结构化 JSON | `agent/subagents/GitDiffAnalyzerAgent.java` |
| **JiraAnalyzerAgent** | `String analyze(@UserMessage String jiraIssueContent)` — 返回结构化 JSON | `agent/subagents/JiraAnalyzerAgent.java` |
| **ReportGeneratorAgent** | `String generate(@UserMessage String prompt, @V("gitAnalysisJson") String, @V("jiraAnalysisJson") String, @V("todayDate") String)` — 返回 Markdown | `agent/subagents/ReportGeneratorAgent.java` |
| **ReportStore 端口** | `void save(DailyReport)` + `List<String> searchSimilar(String query, int maxResults)` | `domain/port/ReportStore.java` |
| **RAG 实现** | `PgVectorReportStore.searchSimilar()` 使用 `EmbeddingModel + EmbeddingStore<TextSegment> + EmbeddingSearchRequest` | `infrastructure/rag/PgVectorReportStore.java:51-64` |
| **AiService 装配模板** | `AiServices.builder(X.class).chatModel(...).chatMemory(...).tools(...).contentRetriever(...).build()` | `infrastructure/config/MultiAgentConfig.java:23-53` |
| **Tool 类模板** | `@Component @RequiredArgsConstructor`，`@Tool("desc") public String foo(...)` | `infrastructure/ai/tools/GitTool.java:20-49` |
| **MultiAgent 错误处理模板** | 每个 sub-agent 调用 try/catch，失败返回降级 JSON（`createErrorGitAnalysis` / `createErrorJiraAnalysis`） | `application/usecase/MultiAgentOrchestrator.java:92-210` |
| **Shell 命令** | `DailyReportCommands` 当前直接注入 `GenerateReportUseCase`，需改为注入 `AgentRouter` | `shell/DailyReportCommands.java:34-50` |
| **ChatModel Bean** | `ChatModelConfig.chatModel(LlmProperties)` 已可被任何 Agent 注入 | `infrastructure/config/ChatModelConfig.java:23-29` |

### 0.2 蓝图原文锚点（COORDINATOR_AGENT 章节）

- CoordinatorAgent 接口与 SystemMessage：`docs/sample_multiple_sub_agent.md:36-56`
- RAGRetrieverAgent 定义：`docs/sample_multiple_sub_agent.md:102-112`
- AiServices.builder + `DelegationTool` 装配示例：`docs/sample_multiple_sub_agent.md:140-161`

### 0.3 三个关键决策（推荐方案 — 在 Phase 1 启动前确认）

| 决策点 | 推荐方案 | 理由 |
|---|---|---|
| **D1: 用 langchain4j-agentic 还是纯 AiService** | **纯 `AiServices.builder()` + 自定义 `@Tool` 包装 Sub-Agent** | (a) 当前 BOM 没有 agentic 依赖；(b) 既有 `SupervisorAgent`、3 个 sub-agent 全用纯 AiService 模式，一致性最强；(c) 蓝图 lines 28-30 明确允许这种 fallback。 |
| **D2: RAG 是否暴露给 Coordinator** | **暴露为 `@Tool retrieveSimilarReports(String query)`**，直接调 `ReportStore.searchSimilar()` | 蓝图把 RAG 当独立 Sub-Agent 设计，但实际上 `searchSimilar()` 已是干净的查询接口、不需要额外 LLM 推理 — 直接用 Tool 比再包一个 Agent 简单一个数量级，也是 COORDINATOR_AGENT 区别于 SAMPLE_MULTIPLE 的核心差异化能力。 |
| **D3: Sub-Agent 调用失败策略** | **Per-call fail-soft**：DelegationTool 内部 try/catch，失败时返回 JSON 错误对象（不抛异常上浮），让 Coordinator 自行判断后续步骤 | 对齐 `MultiAgentOrchestrator.createErrorGitAnalysis()` 现有模式；让 ReAct 循环不会因为 RAG/Jira 中断而整体失败。 |

### 0.4 Allowed APIs（仅引用确认存在的 API）

- `AiServices.builder(Class).chatModel(ChatModel).chatMemory(ChatMemory).tools(Object...).contentRetriever(ContentRetriever).build()` — 当前项目 1.13.1 已验证
- `MessageWindowChatMemory.withMaxMessages(int)` — 验证位置：`MultiAgentConfig.java:30`
- `@SystemMessage("...")` / `@UserMessage` / `@V("key")` / `@Tool("...")` — 验证位置：`agent/subagents/*.java`, `infrastructure/ai/tools/*.java`
- `EmbeddingStoreContentRetriever.builder()` — 验证位置：`LangChain4jConfig.java:42-52`
- `ReportStore.searchSimilar(String, int)` — 验证位置：`domain/port/ReportStore.java`

### 0.5 Anti-patterns（明令禁止）

- ❌ 不要引入 `langchain4j-agentic` 依赖（除非 D1 被推翻）
- ❌ 不要新建 `RAGRetrieverAgent` AiService 接口 — 直接 Tool 化（除非 D2 被推翻）
- ❌ 不要在 DelegationTool 里抛 unchecked exception — fail-soft 是契约（除非 D3 被推翻）
- ❌ 不要改动既有 sub-agent 的 SystemMessage 或方法签名 — Coordinator 是新增层
- ❌ 不要在 `AgentRouter` switch 之外加分发逻辑（`if/else` / `instanceof`）
- ❌ 不要复制蓝图里 `DelegationTool(gitAgent, "analyzeGitDiff")` 这种构造器写法 — 那是 langchain4j-agentic 的语法，纯 AiService 下我们写 `@Tool` 方法包装

---

## Phase 1 — 委托工具层（SubAgentDelegationTool + 可选 RAG Tool）

### 1.1 任务

新建 `infrastructure/ai/tools/SubAgentDelegationTool.java`，把 3 个 sub-agent + RAG 检索包装为 4 个 `@Tool` 方法。

### 1.2 必读模板

| 要参照的模板 | 文件:行 | 复制什么 |
|---|---|---|
| 类骨架（注解、字段、构造器） | `GitTool.java:20-29` | `@Component @RequiredArgsConstructor` + final 字段 + 日志 |
| `@Tool` 方法签名 + 错误降级 | `GitTool.java:30-49` 配 `MultiAgentOrchestrator.java:92-114` (analyzeGitChanges) | `@Tool("desc")`、try/catch、log.error、返回 String fallback |
| sub-agent 方法名 | `GitDiffAnalyzerAgent.java`、`JiraAnalyzerAgent.java`、`ReportGeneratorAgent.java` | `.analyze(...)` / `.generate(...)` 实际方法名（不要猜） |
| RAG 调用方式 | `domain/port/ReportStore.java`（接口）+ `PgVectorReportStore.java:51-64`（实现） | `reportStore.searchSimilar(query, maxResults)` |

### 1.3 工具方法清单（按蓝图，4 个）

| 工具方法 | 入参 | 返回 | 包装的 sub-agent / 端口 | 失败降级返回 |
|---|---|---|---|---|
| `analyzeGitChanges(String commitHash)` | commitHash | String (JSON) | 先调 `GitTool.getCommitDiff()` 拿 diff 文本，再喂给 `GitDiffAnalyzerAgent.analyze()` | `{"error":"git_analysis_failed", "reason":"..."}` |
| `analyzeJiraIssue(String jiraKey)` | jiraKey | String (JSON) | 先调 `JiraTool.getJiraIssue()` 拿 issue 文本，再喂给 `JiraAnalyzerAgent.analyze()` | `{"error":"jira_analysis_failed", "reason":"..."}` |
| `retrieveSimilarReports(String query)` | query | String | `reportStore.searchSimilar(query, 3)`，结果用 `\n---\n` 拼接 | `""`（空字符串，不阻断 ReAct） |
| `composeFinalReport(String gitAnalysisJson, String jiraAnalysisJson, String historicalContext)` | 3 个 JSON/text | String (Markdown) | 调 `ReportGeneratorAgent.generate()`，把 `historicalContext` 拼入 prompt | 抛异常（这是终态，失败必须上浮）|

> **注意**：`analyzeGitChanges` / `analyzeJiraIssue` 内部仍调 `GitTool` / `JiraTool` 拿原始数据 — 复用现有 Adapter，不要重新读 Git/Jira。这是 `MultiAgentOrchestrator.analyzeGitChanges`（line 92-114）的现成做法，逐字搬过来即可，只是把 `gitDiff` 替换成 commitHash 入口。

### 1.4 验证检查表

- [ ] `grep -n "@Tool" src/main/java/com/topsion/easy_daily_report/infrastructure/ai/tools/SubAgentDelegationTool.java` 输出 4 行
- [ ] `./gradlew compileJava` 通过
- [ ] 每个工具方法 javadoc 一行说明委托给哪个 sub-agent
- [ ] 4 个方法的 `@Tool("中文描述")` 与蓝图 `lines 41-55` 的能力描述一致（Git 变更分析 / 业务需求分析 / 历史检索 / 报告生成）

### 1.5 Anti-pattern 防护

- ❌ 不要在工具方法签名里加 `@V` 注解（`@V` 只用于 AiService 接口方法）
- ❌ 不要把 ReportRequest / DailyReport 作为入参/返回值 — 工具层不感知领域模型
- ❌ 不要让 `composeFinalReport` 自己拼日期 — 让 Coordinator 通过参数传入

---

## Phase 2 — CoordinatorAgent 接口 + CoordinatorConfig

### 2.1 任务

(a) 新建 `agent/coordinator/CoordinatorAgent.java`（AiService 接口）
(b) 新建 `infrastructure/config/CoordinatorConfig.java`（Spring Bean 装配）

### 2.2 必读模板

| 要参照的模板 | 文件:行 | 复制什么 |
|---|---|---|
| AiService 接口注解风格 | `agent/supervisor/SupervisorAgent.java`（全文 40 行） | `@SystemMessage("""…""")` 多行写法、参数注解组合 |
| SystemMessage 内容 | `docs/sample_multiple_sub_agent.md:41-55` | **逐字** 抄蓝图的 Master Agent prompt，但加上「具体调用哪个 @Tool 方法名」(`analyzeGitChanges` / `analyzeJiraIssue` / `retrieveSimilarReports` / `composeFinalReport`)，让 ReAct 模型知道要用我们 Phase 1 定义的工具名 |
| Bean 装配 | `infrastructure/config/MultiAgentConfig.java:43-53`（`reportGeneratorAgent` — 无 tools 版） + `infrastructure/config/LangChain4jConfig.java:54-68`（`dailyReportAgent` — 带 tools 版） | 选「带 tools」版本，传入 `SubAgentDelegationTool` 单实例（其内部 `@Tool` 方法会被全部扫描） |

### 2.3 接口签名（最终形态）

```java
package com.topsion.easy_daily_report.agent.coordinator;

public interface CoordinatorAgent {
    @SystemMessage("""
        你是日报生成系统的协调者（Master Agent）。
        ... (按 2.2 SystemMessage 部分组装) ...
        """)
    String coordinate(
        @V("commitHash") String commitHash,
        @V("jiraKey") String jiraKey,
        @V("todayDate") String todayDate
    );
}
```

> 返回 String（Markdown） — 与 `ReportGeneratorAgent.generate()` 一致，让上层 `DailyReport.fromMarkdown()` 统一接收。

### 2.4 Bean 装配（CoordinatorConfig）

最小化 builder 链：

```java
@Bean
public CoordinatorAgent coordinatorAgent(
    ChatModel chatModel,
    SubAgentDelegationTool delegationTool
) {
    return AiServices.builder(CoordinatorAgent.class)
        .chatModel(chatModel)
        .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
        .tools(delegationTool)
        .build();
}
```

> 不注入 `ContentRetriever` — RAG 已通过 `retrieveSimilarReports` 工具暴露，避免双通道。
> 不共享 `chatMemory` bean — Coordinator 用独立 window（蓝图也是各自独立）。

### 2.5 验证检查表

- [ ] `./gradlew compileJava` 通过
- [ ] `grep -r "CoordinatorAgent" src/main/java/com/topsion/easy_daily_report/agent/coordinator/` 找到接口
- [ ] `grep "coordinatorAgent" src/main/java/com/topsion/easy_daily_report/infrastructure/config/CoordinatorConfig.java` 找到 bean
- [ ] SystemMessage 中明确列出 4 个工具的中文名 — 与 Phase 1 的 `@Tool("...")` 描述一对一对应（grep 验证）
- [ ] Spring Boot 启动不报循环依赖：`./gradlew bootRun` 起来后 Ctrl+C

### 2.6 Anti-pattern 防护

- ❌ 不要在 Coordinator 接口加 `@Tool` 注解（只有工具类才有）
- ❌ 不要让 `coordinate()` 返回 POJO — 上层 Orchestrator 已经基于 String 统一处理
- ❌ 不要在 SystemMessage 里描述工具的 *实现细节*（"调用 ReportStore"）— 只描述 *能力*（"检索历史日报"）

---

## Phase 3 — CoordinatorOrchestrator + AgentRouter 接线

### 3.1 任务

(a) 新建 `application/usecase/CoordinatorOrchestrator.java`（实现 `GenerateAgent`）
(b) 修改 `application/usecase/AgentRouter.java`，把 COORDINATOR_AGENT 接到 (a)

### 3.2 必读模板

| 要参照的模板 | 文件:行 | 复制什么 |
|---|---|---|
| GenerateAgent 实现骨架 | `application/usecase/MultiAgentOrchestrator.java:18-34` | `@Service @RequiredArgsConstructor @Slf4j`、`implements GenerateAgent`、`execute(ReportRequest)` 方法体 |
| ReportRequest → 字段提取 | `application/usecase/MultiAgentOrchestrator.java:28-34` | `request.commitHash()` / `request.jiraIssueKey()` |
| DailyReport.fromMarkdown 用法 | 同上 line 33 | `DailyReport.fromMarkdown(result)` |
| AgentRouter 改动 | `application/usecase/AgentRouter.java:10-19` | 加一个 final 字段 `coordinatorAgent`，把 switch 的 COORDINATOR_AGENT 分支从 throw 改成 `-> coordinatorAgent` |

### 3.3 CoordinatorOrchestrator 实现要点

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class CoordinatorOrchestrator implements GenerateAgent {

    private final CoordinatorAgent coordinatorAgent;

    @Override
    public DailyReport execute(ReportRequest request) {
        String todayDate = LocalDate.now().toString();
        String markdown = coordinatorAgent.coordinate(
            request.commitHash(),
            request.jiraIssueKey(),
            todayDate
        );
        return DailyReport.fromMarkdown(markdown);
    }
}
```

> Orchestrator 极薄 — 因为编排逻辑下沉到了 CoordinatorAgent 的 ReAct 循环里，这正是 COORDINATOR_AGENT 区别于 SAMPLE_MULTIPLE 的核心。
> 注意：不再做 RAG `reportStore.save()` — 这块逻辑现在归属于 GenerateReportUseCase（SINGLE 路径）；Coordinator 路径如果需要持久化结果，**单独**在 orchestrator 调用后追加一行 save。建议加上（保持与 SINGLE 模式的功能对等）。

### 3.4 AgentRouter 改动（精确 diff 描述）

```java
// 现状（AgentRouter.java:10-19）
@Component
@RequiredArgsConstructor
public class AgentRouter {
    private final GenerateReportUseCase singleAgent;
    private final MultiAgentOrchestrator multiAgent;

    public GenerateAgent route(AgentLevel level) {
        return switch (level) {
            case SINGLE -> singleAgent;
            case SAMPLE_MULTIPLE -> multiAgent;
            case COORDINATOR_AGENT ->
                throw new UnsupportedOperationException("COORDINATOR_AGENT is reserved for MVP2");
        };
    }
}
```

```java
// 目标
@Component
@RequiredArgsConstructor
public class AgentRouter {
    private final GenerateReportUseCase singleAgent;
    private final MultiAgentOrchestrator multiAgent;
    private final CoordinatorOrchestrator coordinatorAgent;  // ← 新增

    public GenerateAgent route(AgentLevel level) {
        return switch (level) {
            case SINGLE -> singleAgent;
            case SAMPLE_MULTIPLE -> multiAgent;
            case COORDINATOR_AGENT -> coordinatorAgent;       // ← 替换 throw
        };
    }
}
```

### 3.5 验证检查表

- [ ] `grep "UnsupportedOperationException" src/main/java/com/topsion/easy_daily_report/application/usecase/AgentRouter.java` 应该空（throw 已移除）
- [ ] `./gradlew compileJava` 通过
- [ ] `./gradlew bootRun` 起来无 Bean 装配错误
- [ ] CoordinatorOrchestrator 是否要 `reportStore.save()` — Phase 3 启动前与负责人确认（默认建议保留）

### 3.6 Anti-pattern 防护

- ❌ 不要在 CoordinatorOrchestrator 里再写 `CompletableFuture` 并发 — 并发交给 LLM 的 ReAct 决定（这是与 SAMPLE_MULTIPLE 的关键区别）
- ❌ 不要在 Router 里写 `if/else` — 严格 switch
- ❌ 不要把 `singleAgent` / `multiAgent` 字段名改成更"通用"的名字 — 这是无意义重构

---

## Phase 4 — Shell 层入口

### 4.1 任务

修改 `shell/DailyReportCommands.java`：
(a) 把构造器注入从 `GenerateReportUseCase` 换成 `AgentRouter`
(b) 给 `report generate` / `report generate-today` 加 `--level` 选项（默认 SINGLE，保持向后兼容）
(c) 在 `report help` 里补充 level 说明

### 4.2 必读模板

| 要参照的模板 | 文件:行 | 复制什么 |
|---|---|---|
| 现有命令骨架 | `shell/DailyReportCommands.java:34-50`（generateReport） | `@Command` / `@Option` 风格 |
| 现有 help 文本 | `shell/DailyReportCommands.java:90-116` | 帮助文本格式（多行字符串） |

### 4.3 改动要点

```java
@Option(longName = "level", shortName = 'l',
        description = "Agent 级别: SINGLE | SAMPLE_MULTIPLE | COORDINATOR_AGENT (默认 SINGLE)",
        defaultValue = "SINGLE")
String level
```

```java
AgentLevel agentLevel = AgentLevel.valueOf(level.toUpperCase());
GenerateAgent agent = agentRouter.route(agentLevel);
DailyReport report = agent.execute(request);
return report.rawMarkdown();
```

> 入参验证：用户传入未知 level 时，`valueOf` 会抛 `IllegalArgumentException` — 包一个 try/catch，给出友好的错误提示「请使用 SINGLE / SAMPLE_MULTIPLE / COORDINATOR_AGENT 之一」。

### 4.4 验证检查表

- [ ] `grep "GenerateReportUseCase" src/main/java/com/topsion/easy_daily_report/shell/DailyReportCommands.java` 输出空（已替换为 AgentRouter）
- [ ] `./gradlew bootRun` 起来后能执行：
  - `report generate --commit abc123 --jira ABC-1 --level SINGLE`
  - `report generate --commit abc123 --jira ABC-1 --level COORDINATOR_AGENT`
- [ ] 不传 `--level` 时默认走 SINGLE（向后兼容）
- [ ] `report help` 输出中包含 `--level` 参数说明

### 4.5 Anti-pattern 防护

- ❌ 不要新增独立命令 `report generate-coordinator` — `--level` 参数化更简洁，避免命令爆炸
- ❌ 不要在 Shell 层做 ReportRequest 字段校验 — 那是 use case 的职责

---

## Phase 5 — 集成测试

### 5.1 任务

(a) 新建 `agent/coordinator/CoordinatorAgentContractTest.java`（参照 `agent/supervisor/SupervisorAgentContractTest.java`）
(b) 新建 `application/usecase/CoordinatorOrchestratorIntegrationTest.java`
(c) 在现有 router 测试中补 COORDINATOR_AGENT 分支断言（如果有的话；没有就跳过）

### 5.2 必读模板

| 要参照的模板 | 文件:行 | 复制什么 |
|---|---|---|
| Agent 契约测试 | `src/test/java/com/topsion/easy_daily_report/agent/supervisor/SupervisorAgentContractTest.java`（全文 40 行） | mock chatModel / 断言 prompt 含关键字 / 断言返回类型 |
| MultiAgent 集成测试（如果存在） | `src/test/java/com/topsion/easy_daily_report/application/usecase/MultiAgentOrchestratorTest.java`（待 grep 确认） | end-to-end 流程：mock sub-agent → 调 orchestrator → 断言 markdown 非空 |

> 在 Phase 5 启动前先 `find src/test -name "*MultiAgent*"` 确认是否已有同类集成测试，有就抄、没有就建。

### 5.3 测试覆盖目标

- [ ] CoordinatorAgentContractTest：mock chatModel，断言 `coordinate()` 调用时 SystemMessage 包含 4 个工具名
- [ ] CoordinatorOrchestratorIntegrationTest：mock CoordinatorAgent，断言 `execute()` 把 commitHash/jiraKey/today 都传进去、返回值用 `fromMarkdown` 包装
- [ ] SubAgentDelegationToolTest：每个 @Tool 方法的成功路径 + 降级路径（失败时返回错误 JSON / 空字符串）
- [ ] AgentRouterTest（如果已有）：补 COORDINATOR_AGENT 分支不再抛异常的断言

### 5.4 Anti-pattern 防护

- ❌ 不要起真实 LLM 跑端到端 — 用 mock ChatModel（既有契约测试已这么做）
- ❌ 不要在测试里硬编码 prompt 全文 — 只断言关键字（工具名、角色"协调者"等）

---

## Phase 6 — 文档同步

### 6.1 任务

(a) `CLAUDE.md` — 在 Strategy Pattern 章节补充 COORDINATOR_AGENT 已实现，列出 CoordinatorOrchestrator + SubAgentDelegationTool
(b) `docs/technical-documentation.md` — 加 CoordinatorAgent 一节，说明与 SAMPLE_MULTIPLE 的区别（ReAct 动态调度 vs CompletableFuture 静态并发）
(c) `docs/sample_multiple_sub_agent.md` — 在文末加「实际实现说明」，记录三个决策点的最终选型（D1/D2/D3）
(d) `README.md` —（如果列了 CLI 命令）补充 `--level` 参数

### 6.2 验证检查表

- [ ] `grep -n "COORDINATOR_AGENT" CLAUDE.md docs/*.md README.md` — 至少 3 个文件里有更新
- [ ] 文档里描述的 sub-agent 名字 / 方法名 与代码 100% 一致（不要写蓝图里的旧名 `analyzeGitDiff`，代码里实际是 `analyze`）

---

## Phase 7 — 端到端验证

### 7.1 跑通检查

```bash
./gradlew clean build                  # 编译 + 单元测试通过
docker compose up -d                   # 起 PGVector
./gradlew bootRun                      # 启动 Shell
```

在 Shell 内：
```
report generate --commit <hash> --jira <key> --level SINGLE
report generate --commit <hash> --jira <key> --level SAMPLE_MULTIPLE
report generate --commit <hash> --jira <key> --level COORDINATOR_AGENT
```

### 7.2 验证矩阵

| 场景 | 预期 |
|---|---|
| SINGLE / SAMPLE_MULTIPLE 旧路径 | 输出与改造前一致（回归测试） |
| COORDINATOR_AGENT 正常 commit + jira | 生成 Markdown 日报，ReAct trace 显示至少调用 `analyzeGitChanges` + `composeFinalReport` |
| COORDINATOR_AGENT + 无效 jira（不存在） | DelegationTool 返回错误 JSON，Coordinator 仍能生成不含 Jira 部分的报告（fail-soft 生效） |
| COORDINATOR_AGENT + 历史相似日报存在 | ReAct trace 含 `retrieveSimilarReports` 调用，最终报告引用历史模式 |
| --level 拼写错误 | Shell 返回友好错误，不抛栈 |

### 7.3 反模式 grep 扫描

```bash
# 不应该有任何匹配
grep -r "langchain4j-agentic" build.gradle.kts
grep -r "UnsupportedOperationException.*COORDINATOR" src/main/java/
grep -r "new DelegationTool(" src/main/java/   # 蓝图旧语法
```

### 7.4 完成标准

- [ ] Phase 1–6 所有 checklist 打勾
- [ ] Phase 7 验证矩阵全绿
- [ ] D1 / D2 / D3 决策在代码与文档中保持一致
- [ ] 一次 PR，commit 信息符合 `feat:` / `docs:` 约定（参照 `git-workflow.md`）

---

## 阶段顺序与依赖

```
Phase 0 (本文档)
    ↓ 决策批准
Phase 1 (DelegationTool) ─────┐
                              ↓
Phase 2 (CoordinatorAgent + Config)
                              ↓
Phase 3 (Orchestrator + Router 接线)
                              ↓
Phase 4 (Shell --level) ←── 此后可端到端跑
                              ↓
Phase 5 (测试) ────┐
Phase 6 (文档) ────┤  可并行
                  ↓
Phase 7 (验收)
```

每个 Phase 都可在独立的 chat context 中执行 — 只需读本文件对应 Phase 段落即可拿到全部上下文（模板位置、Allowed APIs、Anti-pattern、验证清单都是闭包）。

---

## 决策日志（在执行前填）

| 决策 | 推荐 | 最终选择 | 备注 |
|---|---|---|---|
| D1 | 纯 AiService | _待定_ | |
| D2 | RAG-as-Tool（不建 RAGRetrieverAgent） | _待定_ | |
| D3 | Per-call fail-soft | _待定_ | |

> 三处决策在 Phase 1 开工前确认；如有偏离推荐方案，需补充更新本文件 Anti-pattern 段落。
