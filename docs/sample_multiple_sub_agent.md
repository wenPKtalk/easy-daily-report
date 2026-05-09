# Multiple Agent 架构设计方案

> **实现状态（MVP1）：** ✅ **方案二（纯 LangChain4j 手动编排）已实现。**
> 核心类：`MultiAgentOrchestrator`（`application/usecase/`），Sub-Agent 接口在 `agent/subagents/`，Bean 装配在 `MultiAgentConfig`。
> 方案一（`langchain4j-agentic` CoordinatorAgent）列为 `AgentLevel.COORDINATOR_AGENT`，预留供未来迭代。

## 一、依赖更新

### 1. 添加 langchain4j-agentic 依赖

```gradle
dependencies {
    // ==================== LangChain4j 依赖 ====================
    implementation 'dev.langchain4j:langchain4j'
    
    // 🆕 添加 agentic 模块（支持 multi-agent）
    implementation 'dev.langchain4j:langchain4j-agentic'
    
    implementation 'dev.langchain4j:langchain4j-open-ai'
    implementation 'dev.langchain4j:langchain4j-ollama'
    implementation 'dev.langchain4j:langchain4j-pgvector'
    implementation 'dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2'
    
    // ... 其他依赖保持不变
}
```

**注意：** 如果 `langchain4j-agentic` 在 1.13.1 版本中不可用，可以：
- 升级 BOM 到最新版本
- 或使用纯 langchain4j 实现类似架构（手动编排）

---

## 二、架构设计

### 方案一：使用 langchain4j-agentic (推荐)

```java
// 1. Master Agent (协调者)
public interface CoordinatorAgent {
    @SystemMessage("""
        你是日报生成系统的协调者。
        你的职责：
        1. 分析用户请求（commit hash + jira key）
        2. 将任务委托给专业 sub-agents
        3. 整合所有结果生成最终日报
        
        可用的 sub-agents:
        - GitDiffAnalyzerAgent: 分析代码变更
        - JiraAnalyzerAgent: 分析需求背景
        - RAGRetrieverAgent: 检索历史上下文
        - ReportGeneratorAgent: 生成结构化日报
        
        使用 ReAct 模式思考和委托任务。
        """)
    String coordinate(String commitHash, String jiraKey);
}

// 2. Git Diff Analyzer Sub-Agent
public interface GitDiffAnalyzerAgent {
    @SystemMessage("""
        你是代码变更分析专家。
        职责：
        - 分析 git diff 内容
        - 识别关键代码变更
        - 总结技术要点和影响范围
        - 识别潜在风险
        
        输出格式：JSON
        {
          "files_changed": [...],
          "key_changes": [...],
          "technical_summary": "...",
          "impact_analysis": "...",
          "potential_risks": [...]
        }
        """)
    String analyze(@UserMessage String gitDiff);
}

// 3. Jira Analyzer Sub-Agent
public interface JiraAnalyzerAgent {
    @SystemMessage("""
        你是业务需求分析专家。
        职责：
        - 分析 Jira Issue 描述
        - 提取业务背景和目标
        - 识别关键需求点
        - 评估业务价值
        
        输出格式：JSON
        {
          "business_context": "...",
          "requirements": [...],
          "acceptance_criteria": [...],
          "business_value": "..."
        }
        """)
    String analyze(@UserMessage String jiraDescription);
}

// 4. RAG Retriever Sub-Agent
public interface RAGRetrieverAgent {
    @SystemMessage("""
        你是历史知识检索专家。
        职责：
        - 基于当前任务检索相似历史日报
        - 提取可复用的模式和经验
        - 识别相关项目知识
        """)
    String retrieve(@UserMessage String query);
}

// 5. Report Generator Sub-Agent
public interface ReportGeneratorAgent {
    @SystemMessage("""
        你是专业日报撰写专家。
        职责：
        - 整合代码分析、业务分析、历史知识
        - 生成结构化 Markdown 日报
        - 确保内容专业、准确、易读
        
        日报结构：
        1. 日期与任务概述
        2. 代码变更要点
        3. 业务价值
        4. 潜在风险/优化建议
        5. 明日计划
        """)
    String generate(
        @UserMessage String gitAnalysis,
        @V("jiraAnalysis") String jiraAnalysis,
        @V("historicalContext") String historicalContext
    );
}
```

### 配置类示例

```java
@Configuration
public class MultiAgentConfig {
    
    @Bean
    public CoordinatorAgent coordinatorAgent(
            ChatLanguageModel chatModel,
            GitDiffAnalyzerAgent gitAgent,
            JiraAnalyzerAgent jiraAgent,
            RAGRetrieverAgent ragAgent,
            ReportGeneratorAgent reportAgent) {
        
        return AiServices.builder(CoordinatorAgent.class)
            .chatLanguageModel(chatModel)
            .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
            .tools(
                new DelegationTool(gitAgent, "analyzeGitDiff"),
                new DelegationTool(jiraAgent, "analyzeJira"),
                new DelegationTool(ragAgent, "retrieveHistory"),
                new DelegationTool(reportAgent, "generateReport")
            )
            .build();
    }
    
    @Bean
    public GitDiffAnalyzerAgent gitDiffAnalyzerAgent(
            ChatLanguageModel chatModel,
            GitTools gitTools) {
        
        return AiServices.builder(GitDiffAnalyzerAgent.class)
            .chatLanguageModel(chatModel)
            .tools(gitTools)
            .build();
    }
    
    @Bean
    public JiraAnalyzerAgent jiraAnalyzerAgent(
            ChatLanguageModel chatModel,
            JiraTools jiraTools) {
        
        return AiServices.builder(JiraAnalyzerAgent.class)
            .chatLanguageModel(chatModel)
            .tools(jiraTools)
            .build();
    }
    
    @Bean
    public RAGRetrieverAgent ragRetrieverAgent(
            ChatLanguageModel chatModel,
            ContentRetriever contentRetriever) {
        
        return AiServices.builder(RAGRetrieverAgent.class)
            .chatLanguageModel(chatModel)
            .contentRetriever(contentRetriever)
            .build();
    }
    
    @Bean
    public ReportGeneratorAgent reportGeneratorAgent(
            ChatLanguageModel chatModel) {
        
        return AiServices.builder(ReportGeneratorAgent.class)
            .chatLanguageModel(chatModel)
            .build();
    }
}
```

---

## 三、方案二：纯 LangChain4j 实现（不依赖 agentic 模块）

如果 `langchain4j-agentic` 不可用，可以手动实现 multi-agent 编排：

```java
@Service
public class MultiAgentOrchestrator {
    
    private final GitDiffAnalyzerAgent gitAgent;
    private final JiraAnalyzerAgent jiraAgent;
    private final RAGRetrieverAgent ragAgent;
    private final ReportGeneratorAgent reportAgent;
    
    public String generateReport(String commitHash, String jiraKey) {
        // 1. 并行执行 sub-agents (使用虚拟线程)
        CompletableFuture<String> gitAnalysisFuture = 
            CompletableFuture.supplyAsync(() -> analyzeGit(commitHash));
        
        CompletableFuture<String> jiraAnalysisFuture = 
            CompletableFuture.supplyAsync(() -> analyzeJira(jiraKey));
        
        CompletableFuture<String> ragResultFuture = 
            CompletableFuture.supplyAsync(() -> retrieveContext(commitHash, jiraKey));
        
        // 2. 等待所有分析完成
        CompletableFuture.allOf(
            gitAnalysisFuture, 
            jiraAnalysisFuture, 
            ragResultFuture
        ).join();
        
        // 3. 整合结果生成最终日报
        String gitAnalysis = gitAnalysisFuture.join();
        String jiraAnalysis = jiraAnalysisFuture.join();
        String historicalContext = ragResultFuture.join();
        
        return reportAgent.generate(gitAnalysis, jiraAnalysis, historicalContext);
    }
    
    private String analyzeGit(String commitHash) {
        // GitDiffAnalyzerAgent 会调用 GitTools
        String diff = gitTools.getCommitDiff(commitHash);
        return gitAgent.analyze(diff);
    }
    
    private String analyzeJira(String jiraKey) {
        // JiraAnalyzerAgent 会调用 JiraTools
        String description = jiraTools.getIssueDescription(jiraKey);
        return jiraAgent.analyze(description);
    }
    
    private String retrieveContext(String commitHash, String jiraKey) {
        String query = String.format("commit: %s, jira: %s", commitHash, jiraKey);
        return ragAgent.retrieve(query);
    }
}
```

---

## 四、项目结构调整

```
com/topsion/easy_daily_report/
├── EasyDailyReportApplication.java
├── shell/
│   └── DailyReportCommands.java           // 调用 MultiAgentOrchestrator
├── agent/
│   ├── coordinator/
│   │   └── CoordinatorAgent.java          // Master Agent (可选)
│   ├── subagents/
│   │   ├── GitDiffAnalyzerAgent.java      // Git 分析 Sub-Agent
│   │   ├── JiraAnalyzerAgent.java         // Jira 分析 Sub-Agent
│   │   ├── RAGRetrieverAgent.java         // RAG 检索 Sub-Agent
│   │   └── ReportGeneratorAgent.java      // 日报生成 Sub-Agent
│   └── orchestrator/
│       └── MultiAgentOrchestrator.java    // 手动编排器（方案二）
├── tools/
│   ├── GitTools.java                      // @Tool 方法
│   └── JiraTools.java                     // @Tool 方法
├── rag/
│   ├── IngestionService.java
│   └── ReportRetriever.java
├── service/
│   ├── GitService.java
│   └── JiraService.java
└── config/
    ├── LangChain4jConfig.java
    ├── MultiAgentConfig.java              // 🆕 Multi-Agent 配置
    └── PgVectorConfig.java
```

---

## 五、优势分析

### Multiple Agent 架构优势

1. **关注点分离**
    - 每个 agent 专注单一职责
    - 更清晰的代码组织
    - 易于测试和维护

2. **并行处理**
    - Git 和 Jira 分析可并行执行
    - 提升整体性能

3. **可扩展性**
    - 轻松添加新的 sub-agent (如代码质量检查、安全扫描等)
    - 不影响现有 agents

4. **错误隔离**
    - 单个 agent 失败不影响其他
    - 更好的容错性

5. **Prompt 优化**
    - 每个 agent 有专门的 system message
    - 更精确的输出控制

### 与单 Agent 对比

| 维度 | 单 Agent (ReAct) | Multiple Agents |
|------|------------------|-----------------|
| 复杂度 | 低 | 中 |
| 可维护性 | 中 | 高 |
| 并行能力 | 无 | 有 |
| Prompt 管理 | 复杂（单个大 prompt） | 清晰（多个小 prompt） |
| Token 消耗 | 高（冗长 context） | 低（分段处理） |
| 扩展性 | 低 | 高 |

---

## 六、实施建议

### 阶段一：最小可行方案（MVP）
1. 先实现**方案二**（纯 LangChain4j 手动编排）
2. 定义 3 个核心 sub-agents：
    - GitDiffAnalyzerAgent
    - JiraAnalyzerAgent
    - ReportGeneratorAgent
3. 使用 `CompletableFuture` 并行执行

### 阶段二：引入 langchain4j-agentic
1. 检查 LangChain4j 版本更新
2. 如果 `langchain4j-agentic` 可用，迁移到**方案一**
3. 添加 CoordinatorAgent 实现智能委托

### 阶段三：优化和扩展
1. 添加 RAGRetrieverAgent
2. 实现 Agent 间通信和状态共享
3. 添加缓存和错误重试机制

---

## 七、代码示例：Spring Shell 命令

```java
@ShellComponent
public class DailyReportCommands {
    
    private final MultiAgentOrchestrator orchestrator;
    
    @ShellMethod(key = "generate", value = "Generate daily report")
    public String generateReport(
            @ShellOption String commit,
            @ShellOption String jira) {
        
        try {
            String report = orchestrator.generateReport(commit, jira);
            return TerminalUI.success("Report generated successfully:\n\n" + report);
        } catch (Exception e) {
            return TerminalUI.error("Failed to generate report: " + e.getMessage());
        }
    }
}
```

---

## 八、配置示例 (application.yml)

```yaml
langchain4j:
  open-ai:
    chat-model:
      model-name: gpt-4-turbo-preview
      temperature: 0.3
      max-tokens: 2000
  
  multi-agent:
    enabled: true
    parallel-execution: true
    timeout-seconds: 60
    
    agents:
      git-analyzer:
        model: gpt-4-turbo-preview
        temperature: 0.1  # 低温度，追求准确性
      
      jira-analyzer:
        model: gpt-4-turbo-preview
        temperature: 0.2
      
      report-generator:
        model: gpt-4-turbo-preview
        temperature: 0.5  # 稍高温度，增加创造性
```

---

## 九、测试策略

```java
@SpringBootTest
class MultiAgentOrchestratorTests {
    
    @Autowired
    private MultiAgentOrchestrator orchestrator;
    
    @Test
    void testParallelExecution() {
        long start = System.currentTimeMillis();
        
        String report = orchestrator.generateReport(
            "abc123", 
            "PROJ-456"
        );
        
        long duration = System.currentTimeMillis() - start;
        
        assertNotNull(report);
        assertTrue(duration < 30000); // 应在 30 秒内完成
        assertTrue(report.contains("代码变更要点"));
        assertTrue(report.contains("业务价值"));
    }
    
    @Test
    void testAgentIsolation() {
        // 验证单个 agent 失败不影响其他
    }
}
```

---

## 总结

**推荐实施路径：**
1. 短期：使用**方案二**（纯 LangChain4j 手动编排）快速实现
2. 长期：关注 `langchain4j-agentic` 模块发展，适时迁移

**核心收益：**
- ✅ 更清晰的代码组织
- ✅ 更好的性能（并行执行）
- ✅ 更强的扩展性
- ✅ 更精确的 AI 输出控制