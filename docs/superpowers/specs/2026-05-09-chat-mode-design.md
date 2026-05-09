# Chat Mode Design Spec

**日期:** 2026-05-09  
**状态:** 已批准，待实现  
**作者:** Pengkunwen  

---

## 1. 目标

将 easy-daily-report 从固定命令模式（`report generate --commit xxx`）升级为自由对话模式：

- 输入 `chat` 或 `@chat` 进入持续对话状态（类 ChatGPT 体验）
- 支持多轮对话，Agent 记住跨会话上下文（commit、jiraKey、历史分析结果）
- SupervisorAgent（LLM 驱动）自动路由 Single/Multi Agent，用户可 `/mode` 强制覆盖
- 对话能力：核心为日报生成 + 自然语言追问（改语言、展开细节等）

---

## 2. 架构概览

```
ChatCommands
    └─► ChatOrchestrator
          ├─► SupervisorAgent          (LLM 决策 + AgenticScope)
          │     ├─► SessionContextTool  (读写 ChatSession.context)
          │     └─► GitTool            (主动查询 Git，补全缺失信息)
          ├─► AgentRouter              (按 AgentLevel 路由)
          │     ├─► GenerateReportUseCase  → DailyReportAgent (单 Agent ReAct)
          │     └─► MultiAgentOrchestrator → 3 Sub-Agents (并行)
          └─► ChatSessionRepository    (PostgreSQL 持久化)
```

### AgenticScope 定义

SupervisorAgent 的 LangChain4j `AiServices` 注入以下工具，形成自主执行空间：

| Tool | 作用 |
|------|------|
| `SessionContextTool.getContext()` | 读取当前会话的 commit、jira、历史分析结果 |
| `SessionContextTool.updateContext(key, value)` | 更新会话变量 |
| `GitTool.getRecentCommits()` | 信息不足时主动获取最近提交 |

---

## 3. 新增文件清单

| 文件 | 包 | 说明 |
|------|----|------|
| `ChatCommands.java` | `shell/` | `chat` / `@chat` 命令入口，readline 子循环 |
| `ChatOrchestrator.java` | `application/chat/` | 对话循环管理 + session 生命周期 |
| `ChatSession.java` | `application/chat/` | 会话领域对象（record） |
| `ConversationTurn.java` | `application/chat/` | 单条对话记录（record） |
| `SupervisorAgent.java` | `agent/supervisor/` | LLM 路由决策 AiService 接口 |
| `SupervisorDecision.java` | `agent/supervisor/` | 决策结果 record |
| `SessionContextTool.java` | `infrastructure/ai/tools/` | `@Tool`：读写会话上下文 |
| `ChatSessionRepository.java` | `infrastructure/chat/` | PostgreSQL 持久化 |
| `SupervisorConfig.java` | `infrastructure/config/` | SupervisorAgent Bean 装配 |

**修改现有文件：**

| 文件 | 变更 |
|------|------|
| `AgentRouter.java` | 补全 `route(AgentLevel)` 方法，注入两个 GenerateAgent 实现 |

---

## 4. 领域对象

### ChatSession

```java
public record ChatSession(
    String sessionId,              // UUID
    String userId,                 // git user.email
    AgentLevel currentMode,        // 当前路由模式
    boolean modeOverridden,        // 是否用户强制覆盖
    Map<String, String> context,   // commitHash, jiraKey, repoPath 等
    List<ConversationTurn> history,
    LocalDateTime createdAt,
    LocalDateTime lastActiveAt
)
```

### ConversationTurn

```java
public record ConversationTurn(
    String role,       // "user" | "assistant"
    String content,
    LocalDateTime at
)
```

### SupervisorDecision

```java
public record SupervisorDecision(
    Intent intent,           // GENERATE_REPORT | FOLLOW_UP | CLARIFY | MODE_SWITCH
    AgentLevel agentLevel,   // SINGLE | SAMPLE_MULTIPLE（intent=GENERATE_REPORT 时有效）
    String commitHash,       // 从对话中提取（可为 null）
    String jiraKey,          // 从对话中提取（可为 null）
    String directResponse,   // intent=FOLLOW_UP 时直接返回
    String clarifyQuestion   // intent=CLARIFY 时向用户提问
)
```

---

## 5. SupervisorAgent 接口

```java
public interface SupervisorAgent {
    @SystemMessage("""
        你是日报生成系统的智能协调者。

        你的职责：
        1. 理解用户的自然语言意图
        2. 从对话上下文中提取关键信息（commitHash、jiraKey、repoPath）
        3. 决定路由策略：SINGLE 或 SAMPLE_MULTIPLE
        4. 对追问（如"改成英文"、"展开风险点"）直接基于上下文回答，不调用子 Agent
        5. 若信息不足，向用户提问补全

        路由规则（可被 /mode 覆盖）：
        - 同时提供 commit + jira → SAMPLE_MULTIPLE
        - 只有单一数据源 → SINGLE
        - 纯追问/修改请求 → FOLLOW_UP，直接回答

        始终用中文回复，除非用户要求其他语言。
        """)
    SupervisorDecision decide(
        @UserMessage String userInput,
        @V("sessionContext") String sessionContext
    );
}
```

### Bean 装配（SupervisorConfig）

```java
@Bean
public SupervisorAgent supervisorAgent(
        ChatModel chatModel,
        SessionContextTool sessionContextTool,
        GitTool gitTool) {

    return AiServices.builder(SupervisorAgent.class)
            .chatModel(chatModel)
            .tools(sessionContextTool, gitTool)
            .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
            .build();
}
```

---

## 6. ChatOrchestrator 核心逻辑

```java
public class ChatOrchestrator {

    public void startLoop(ChatSession session, AgentLevel forcedMode) {
        if (forcedMode != null) {
            session = session.withMode(forcedMode, true);
        }

        while (true) {
            String input = lineReader.readLine("you> ");
            if (input == null) break;

            // 内置命令（不走 LLM）
            if (input.startsWith("/")) {
                handleBuiltinCommand(input, session);
                continue;
            }

            // Supervisor 决策
            SupervisorDecision decision = supervisorAgent.decide(
                input, session.contextAsString()
            );

            // 用户强制模式覆盖 Supervisor 路由
            AgentLevel level = session.modeOverridden()
                ? session.currentMode()
                : decision.agentLevel();

            switch (decision.intent()) {
                case FOLLOW_UP  -> print(decision.directResponse());
                case CLARIFY    -> print(decision.clarifyQuestion());
                case GENERATE_REPORT -> {
                    ReportRequest req = buildRequest(decision, session);
                    DailyReport report = agentRouter.route(level).execute(req);
                    session = session.updateContext(decision);
                    print(report.rawMarkdown());
                }
            }

            session = session.appendTurn("user", input)
                             .appendTurn("assistant", lastOutput());
            sessionRepository.save(session);
        }
    }
}
```

---

## 7. 会话内置命令

| 命令 | 功能 |
|------|------|
| `/mode single` | 强制单 Agent 模式 |
| `/mode multi` | 强制多 Agent 模式 |
| `/mode auto` | 恢复 Supervisor 自动路由 |
| `/context` | 显示当前已提取的上下文变量 |
| `/clear` | 清空上下文，保留 session |
| `/new` | 创建全新 session |
| `/history` | 显示最近 10 条对话 |
| `/exit` | 保存并退出 chat 模式 |
| `/help` | 显示帮助 |

---

## 8. 数据库表结构

```sql
CREATE TABLE chat_sessions (
    session_id      VARCHAR(36) PRIMARY KEY,
    user_id         VARCHAR(255),
    current_mode    VARCHAR(50),
    mode_overridden BOOLEAN DEFAULT FALSE,
    context_json    JSONB,
    created_at      TIMESTAMP,
    last_active_at  TIMESTAMP
);

CREATE TABLE conversation_turns (
    id          BIGSERIAL PRIMARY KEY,
    session_id  VARCHAR(36) REFERENCES chat_sessions(session_id),
    role        VARCHAR(20),
    content     TEXT,
    created_at  TIMESTAMP
);

CREATE INDEX idx_conversation_turns_session
    ON conversation_turns(session_id, created_at DESC);
```

---

## 9. 会话恢复策略

```
chat 命令启动
    │
    ├─► 查找该 user_id 最近 24h 内的活跃 session
    │     ├─► 找到 → 恢复上下文，提示"继续上次对话（时间）"
    │     └─► 未找到 → 创建新 session
    │
    │  对话中每轮：
    ├─► Supervisor 决策 → 执行 → 更新 context → 追加 ConversationTurn → 持久化
    │
    └─► /exit 或 Ctrl+C → 保存，提示"会话已保存"
```

ChatMemory 重建：每次从 `conversation_turns` 加载最近 20 条记录，重建 `MessageWindowChatMemory`。

---

## 10. AgentRouter 补全

```java
@Component
@RequiredArgsConstructor
public class AgentRouter {
    private final GenerateReportUseCase singleAgent;
    private final MultiAgentOrchestrator multiAgent;

    public GenerateAgent route(AgentLevel level) {
        return switch (level) {
            case SINGLE          -> singleAgent;
            case SAMPLE_MULTIPLE -> multiAgent;
            case COORDINATOR_AGENT ->
                throw new UnsupportedOperationException("预留，待 MVP2 实现");
        };
    }
}
```

---

## 11. 交互示例

```
shell:> chat

╔══════════════════════════════════════════════════╗
║  进入对话模式 · 输入 /help 查看可用命令          ║
║  已恢复上次会话（2026-05-08 17:30）              ║
╚══════════════════════════════════════════════════╝

you> 帮我分析今天的提交，关联 DAILY-456

[Supervisor: SAMPLE_MULTIPLE | commit=今日提交, jira=DAILY-456]
[并行: GitDiffAnalyzerAgent + JiraAnalyzerAgent → ReportGeneratorAgent]

assistant> ## 工作日报 - 2026-05-09 ...

you> 把风险部分改成英文

[Supervisor: FOLLOW_UP | 直接回答]

assistant> ## Risk Analysis ...

you> /mode single
assistant> ✅ 已切换到单 Agent 模式（手动覆盖）

you> /exit
assistant> 会话已保存。下次输入 chat 可继续。
```

---

## 12. 不在本期范围内

- `AgentLevel.COORDINATOR_AGENT`（Master-Sub 协调模式）— 预留至 MVP2
- Web UI / REST API
- 多用户并发会话隔离
- 会话搜索 / 历史列表管理界面
