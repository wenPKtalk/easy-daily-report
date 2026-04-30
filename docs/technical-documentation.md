# Easy Daily Report — 技术文档

> **版本:** 1.0 (MVP)  
> **日期:** 2026-04-27  
> **作者:** Pengkunwen  

---

## 目录

1. [项目概述](#1-项目概述)
2. [系统架构](#2-系统架构)
3. [技术栈](#3-技术栈)
4. [DDD 分层架构](#4-ddd-分层架构)
5. [领域模型](#5-领域模型)
6. [端口与适配器](#6-端口与适配器)
7. [AI Agent 设计](#7-ai-agent-设计)
8. [RAG 子系统](#8-rag-子系统)
9. [Spring Shell 命令层](#9-spring-shell-命令层)
10. [配置管理](#10-配置管理)
11. [设计模式总览](#11-设计模式总览)
12. [SOLID 原则实践](#12-solid-原则实践)
13. [本地开发指南](#13-本地开发指南)
14. [API 参考](#14-api-参考)

---

## 1. 项目概述

### 1.1 项目目标

Easy Daily Report 是一个轻量级命令行（CLI）智能 Agent 应用，能够结合 **Git Commit Code Diff** 和 **Jira Issue 描述**，通过 **ReAct 模式** + **RAG**（基于 PGVector）自动生成专业的工作日报。

### 1.2 核心能力

| 能力 | 说明 |
|------|------|
| Git Diff 分析 | 基于 JGit 读取 commit 信息和代码差异 |
| Jira 集成 | 通过 REST API 获取 Issue 上下文 |
| ReAct Agent | LLM 自主决策调用工具，多轮推理 |
| RAG 检索 | 通过 PGVector 检索历史日报作为参考 |
| 结构化输出 | 生成 Markdown 格式的标准化日报 |

### 1.3 核心输入/输出

```
输入:
  ├── Git Commit Hash（或范围）
  └── Jira Issue Key

输出（Markdown 日报）:
  ├── 📅 日期与任务概述
  ├── 💻 代码变更要点
  ├── 💼 业务价值
  ├── ⚠️ 潜在风险/优化建议
  └── 📌 明日计划
```

---

## 2. 系统架构

### 2.1 架构总览图

```
┌──────────────────────────────────────────────────────────────┐
│                    Interface Layer (Shell)                    │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │  DailyReportCommands (Spring Shell @Command)            │ │
│  │  report generate -c <hash> -j <jira-key>                │ │
│  └──────────────────────┬──────────────────────────────────┘ │
├─────────────────────────┼────────────────────────────────────┤
│                Application Layer                              │
│  ┌──────────────────────▼──────────────────────────────────┐ │
│  │  GenerateReportUseCase                                  │ │
│  │  编排: ReportGenerator.generate() → ReportStore.save()  │ │
│  └──────────┬──────────────────────────────┬───────────────┘ │
├─────────────┼──────────────────────────────┼─────────────────┤
│             │     Domain Layer             │                  │
│  ┌──────────▼───────────┐   ┌──────────────▼──────────────┐ │
│  │  Port: ReportGenerator│   │  Port: ReportStore          │ │
│  │  Port: GitPort        │   │  Model: DailyReport         │ │
│  │  Port: JiraPort       │   │  Model: CodeChange          │ │
│  │                       │   │  Model: JiraIssueInfo        │ │
│  │                       │   │  Model: ReportRequest        │ │
│  └──────────┬───────────┘   └──────────────┬──────────────┘ │
├─────────────┼──────────────────────────────┼─────────────────┤
│             │   Infrastructure Layer       │                  │
│  ┌──────────▼───────────┐   ┌──────────────▼──────────────┐ │
│  │ AgentReportGenerator  │   │ PgVectorReportStore         │ │
│  │   ├── DailyReportAgent│   │   ├── EmbeddingStore        │ │
│  │   ├── GitTool (@Tool) │   │   └── EmbeddingModel        │ │
│  │   ├── JiraTool (@Tool)│   │                              │ │
│  │   └── ChatMemory      │   │                              │ │
│  ├───────────────────────┤   ├──────────────────────────────┤ │
│  │ JGitAdapter           │   │ PgVectorConfig               │ │
│  │ JiraRestAdapter       │   │ LangChain4jConfig            │ │
│  └───────────────────────┘   └──────────────────────────────┘ │
├──────────────────────────────────────────────────────────────┤
│                    External Systems                           │
│  ┌──────────┐  ┌──────────┐  ┌───────────┐  ┌────────────┐ │
│  │ Git Repo │  │ Jira API │  │ PGVector  │  │ LLM (API)  │ │
│  └──────────┘  └──────────┘  └───────────┘  └────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 数据流（ReAct 循环）

```
用户输入 CLI 命令
       │
       ▼
┌─────────────────┐
│ Shell Command   │──── 解析参数，构造 ReportRequest
└────────┬────────┘
         ▼
┌─────────────────┐
│ UseCase 编排    │──── 调用 ReportGenerator + ReportStore
└────────┬────────┘
         ▼
┌─────────────────┐
│ ReAct Agent     │◄─── System Prompt (角色 + 输出格式)
│ (LangChain4j)   │
│                 │──► Thought: "需要获取 commit 详情"
│                 │──► Action:  调用 GitTool.getCommitDiff()
│                 │◄── Observation: diff 内容
│                 │
│                 │──► Thought: "需要 Jira 上下文"
│                 │──► Action:  调用 JiraTool.getJiraIssue()
│                 │◄── Observation: issue 详情
│                 │
│                 │──► Thought: "检索历史日报参考"
│                 │──► (RAG ContentRetriever 自动介入)
│                 │◄── Observation: 相似日报片段
│                 │
│                 │──► Thought: "信息充足，生成日报"
│                 │──► 输出: 结构化 Markdown 日报
└────────┬────────┘
         ▼
┌─────────────────┐
│ ReportStore     │──── 向量化保存到 PGVector
└────────┬────────┘
         ▼
    输出到控制台
```

---

## 3. 技术栈

### 3.1 核心依赖

| 类别 | 技术 | 版本 | 用途 |
|------|------|------|------|
| 语言 | Java | 21 | Records, Virtual Threads, Text Blocks |
| 框架 | Spring Boot | 4.0.6 | 应用框架（无 Web） |
| CLI | Spring Shell | 4.0.1 | 交互式命令行 |
| 终端 | JLine | 3.26 | 智能提示、历史记录 |
| 颜色 | JANSI | 2.4.1 | 跨平台 ANSI 输出 |
| 配置 | spring-dotenv | 5.1.0 | .env 文件自动加载 |
| AI | LangChain4j | 1.13.1 | Agent + Tools + RAG |
| LLM | OpenAI (可替换) | — | GPT-4o / Claude / Ollama |
| 向量库 | PGVector | pg17 | 向量存储与检索 |
| Embedding | All-MiniLM-L6-v2 | — | 本地轻量嵌入模型 |
| Git | JGit | 7.2.0 | 纯 Java Git 操作 |
| 构建 | Gradle | — | Groovy DSL |
| 测试 | JUnit 5 | — | 单元/集成测试 |

### 3.2 依赖关系图

```
spring-boot-starter
       │
       ├── spring-shell-starter
       │      ├── spring-shell-jline      (终端增强)
       │      └── spring-shell-starter-jansi (颜色输出)
       │
       ├── spring-dotenv                  (.env 自动加载)
       │
       ├── langchain4j (core)             (手动配置，非 starter)
       │      ├── langchain4j-open-ai      (OpenAI/ZhipuAI 客户端)
       │      ├── langchain4j-pgvector     (向量存储)
       │      └── langchain4j-embeddings-all-minilm-l6-v2
       │
       ├── org.eclipse.jgit
       │
       └── postgresql (runtime)
```

---

## 4. DDD 分层架构

### 4.1 层级职责

```
┌─────────────────────────────────────────────┐
│          Shell Layer (Interface)             │  CLI 入口，参数解析
├─────────────────────────────────────────────┤
│          Application Layer                   │  用例编排，流程控制
├─────────────────────────────────────────────┤
│          Domain Layer (Core)                 │  业务模型，端口定义
├─────────────────────────────────────────────┤
│          Infrastructure Layer                │  技术实现，外部集成
└─────────────────────────────────────────────┘
```

### 4.2 依赖规则

```
Shell ──────► Application ──────► Domain ◄────── Infrastructure
  │                │                 ▲                  │
  │                │                 │                  │
  └────────────────┴─────── 依赖方向 ┘──────────────────┘
```

**核心规则：** Domain 层不依赖任何外部框架。所有外部依赖通过 Port 接口反转，由 Infrastructure 实现。

### 4.3 包结构

```
com.topsion.easy_daily_report/
│
├── EasyDailyReportApplication.java          # Spring Boot 启动入口
│
├── domain/                                   # 领域层 ★ 零外部依赖
│   ├── model/                                # 领域模型
│   │   ├── DailyReport.java                  #   Aggregate Root
│   │   ├── CodeChange.java                   #   Value Object
│   │   ├── JiraIssueInfo.java                #   Value Object
│   │   └── ReportRequest.java                #   Command Object
│   └── port/                                 # 端口（接口）
│       ├── GitPort.java                      #   Git 操作契约
│       ├── JiraPort.java                     #   Jira 操作契约
│       ├── ReportGenerator.java              #   日报生成策略
│       └── ReportStore.java                  #   日报存储/检索
│
├── application/                              # 应用层
│   └── usecase/
│       └── GenerateReportUseCase.java        #   日报生成用例
│
├── infrastructure/                           # 基础设施层
│   ├── ai/                                   # AI Agent
│   │   ├── DailyReportAgent.java             #   AiServices 接口
│   │   ├── AgentReportGenerator.java         #   ReportGenerator 实现
│   │   └── tools/                            #   LangChain4j Tools
│   │       ├── GitTool.java                  #     @Tool: Git 操作
│   │       └── JiraTool.java                 #     @Tool: Jira 操作
│   ├── git/
│   │   └── JGitAdapter.java                  #   GitPort 实现
│   ├── jira/
│   │   └── JiraRestAdapter.java              #   JiraPort 实现
│   ├── rag/
│   │   └── PgVectorReportStore.java          #   ReportStore 实现
│   └── config/
│       ├── LangChain4jConfig.java            #   Agent 组装配置
│       └── PgVectorConfig.java               #   向量存储配置
│
└── shell/                                    # 接口层
    └── DailyReportCommands.java              #   CLI 命令定义
```

---

## 5. 领域模型

### 5.1 DailyReport（Aggregate Root）

```java
public record DailyReport(
    LocalDate date,           // 日报日期
    String taskOverview,      // 任务概述
    List<String> codeHighlights,   // 代码变更要点
    String businessValue,     // 业务价值
    List<String> risksAndSuggestions, // 风险与建议
    String tomorrowPlan,      // 明日计划
    String rawMarkdown        // 原始 Markdown 输出
)
```

**职责：** 日报的聚合根，封装一份完整日报的所有信息。提供工厂方法 `fromMarkdown()` 从 Agent 原始输出构建实例。

### 5.2 CodeChange（Value Object）

```java
public record CodeChange(
    String commitId,          // 完整 commit hash
    String author,            // 提交者
    String message,           // 提交信息
    String diff,              // 代码差异
    LocalDateTime commitTime  // 提交时间
)
```

**职责：** 表示一次 Git Commit 的核心信息。提供 `shortId()` 方法返回 7 位短哈希。

### 5.3 JiraIssueInfo（Value Object）

```java
public record JiraIssueInfo(
    String issueKey,          // PROJ-123
    String summary,           // 标题
    String description,       // 描述
    String status,            // 状态
    String assignee,          // 负责人
    String priority           // 优先级
)
```

**职责：** 封装 Jira Issue 的业务上下文。提供 `businessContext()` 方法生成格式化的业务摘要。

### 5.4 ReportRequest（Command Object）

```java
public record ReportRequest(
    String commitHash,        // 单个 commit
    String commitRange,       // commit 范围 (from..to)
    String jiraIssueKey,      // Jira Issue Key
    String repositoryPath     // Git 仓库路径
)
```

**职责：** 封装用户输入参数。提供 `hasCommitRange()` 和 `hasJiraIssue()` 便捷判断方法。

### 5.5 领域模型关系

```
          ┌──────────────┐
          │ ReportRequest│ ◄──── 用户输入
          └──────┬───────┘
                 │ 触发生成
                 ▼
          ┌──────────────┐
          │  DailyReport │ ◄──── 输出产物（Aggregate Root）
          └──────────────┘
                 ▲ 组合
        ┌────────┴────────┐
        ▼                 ▼
┌──────────────┐  ┌───────────────┐
│  CodeChange  │  │ JiraIssueInfo │
│ (Value Obj)  │  │ (Value Obj)   │
└──────────────┘  └───────────────┘
```

---

## 6. 端口与适配器

### 6.1 端口定义（Domain 层接口）

#### GitPort

```java
public interface GitPort {
    CodeChange getCommitDetail(String repositoryPath, String commitHash);
    List<CodeChange> getCommitRange(String repositoryPath, String from, String to);
    String getDiff(String repositoryPath, String commitHash);
    List<CodeChange> getRecentCommits(String repositoryPath, int count);
}
```

**实现:** `JGitAdapter` — 使用 Eclipse JGit 库，纯 Java 实现，无需系统 Git。

#### JiraPort

```java
public interface JiraPort {
    JiraIssueInfo getIssue(String issueKey);
}
```

**实现:** `JiraRestAdapter` — 使用 Java HttpClient 调用 Jira REST API v2，Basic Auth 认证。

#### ReportGenerator

```java
public interface ReportGenerator {
    DailyReport generate(ReportRequest request);
}
```

**实现:** `AgentReportGenerator` — 委托给 LangChain4j `DailyReportAgent`，支持替换不同生成策略。

#### ReportStore

```java
public interface ReportStore {
    void save(DailyReport report);
    List<String> searchSimilar(String query, int maxResults);
}
```

**实现:** `PgVectorReportStore` — 使用 PGVector 进行向量化存储和相似度检索。

### 6.2 适配器映射关系

```
Domain Port              Infrastructure Adapter        External System
─────────────           ──────────────────────        ────────────────
GitPort           ◄──── JGitAdapter               ──► Git Repository
JiraPort          ◄──── JiraRestAdapter            ──► Jira REST API
ReportGenerator   ◄──── AgentReportGenerator       ──► LLM API
ReportStore       ◄──── PgVectorReportStore        ──► PostgreSQL + pgvector
```

---

## 7. AI Agent 设计

### 7.1 DailyReportAgent 接口

```java
public interface DailyReportAgent {
    @SystemMessage("...")   // ReAct 风格 System Prompt
    String generateReport(@UserMessage String userRequest);
}
```

**工作原理：** LangChain4j 的 `AiServices` 在运行时为此接口生成动态代理，自动处理：
- Tool 调用路由
- ChatMemory 管理
- RAG ContentRetriever 注入
- ReAct 推理循环

### 7.2 Agent 组装（LangChain4jConfig）

```java
AiServices.builder(DailyReportAgent.class)
    .chatLanguageModel(chatLanguageModel)     // LLM 模型
    .chatMemory(chatMemory)                   // 对话记忆
    .contentRetriever(contentRetriever)        // RAG 检索器
    .tools(gitTool, jiraTool)                 // 可调用工具
    .build();
```

### 7.3 Tools 设计

#### GitTool

| 方法 | 描述 | Agent 调用时机 |
|------|------|---------------|
| `getCommitDiff(commitHash)` | 获取 commit 详情 + diff | 用户提供 commit hash 时 |
| `getRecentCommits(count)` | 列出最近 N 条 commit | 需要概览最近活动时 |

#### JiraTool

| 方法 | 描述 | Agent 调用时机 |
|------|------|---------------|
| `getJiraIssue(issueKey)` | 获取 Issue 完整信息 | 用户提供 Jira Key 时 |

### 7.4 System Prompt 设计

```
角色设定:
  专业的工作日报生成助手

推理模式:
  ReAct (Thought → Action → Observation 循环)

可用工具:
  1. getCommitDiff — Git commit 代码变更
  2. getRecentCommits — 最近 commit 列表
  3. getJiraIssue — Jira Issue 详情

输出格式:
  ## 📅 工作日报 - {日期}
  ### 📋 任务概述
  ### 💻 代码变更要点
  ### 💼 业务价值
  ### ⚠️ 潜在风险与优化建议
  ### 📌 明日计划
```

### 7.5 ChatMemory 配置

- **类型:** `MessageWindowChatMemory`
- **窗口大小:** 20 条消息（可配置）
- **作用:** 保持对话上下文，支持追问和迭代优化

---

## 8. RAG 子系统

### 8.1 架构

```
┌───────────────────────────────────────────────┐
│                RAG Pipeline                    │
│                                               │
│  ┌─────────────┐    ┌─────────────────────┐  │
│  │ Ingestion   │    │ Retrieval           │  │
│  │             │    │                     │  │
│  │ DailyReport │    │ Query (来自 Agent)  │  │
│  │     │       │    │     │               │  │
│  │     ▼       │    │     ▼               │  │
│  │ TextSegment │    │ Embedding           │  │
│  │     │       │    │     │               │  │
│  │     ▼       │    │     ▼               │  │
│  │ Embedding   │    │ PGVector 近似搜索    │  │
│  │     │       │    │     │               │  │
│  │     ▼       │    │     ▼               │  │
│  │ PGVector    │    │ 相似日报片段         │  │
│  │   Store     │    │                     │  │
│  └─────────────┘    └─────────────────────┘  │
└───────────────────────────────────────────────┘
```

### 8.2 关键配置

| 参数 | 值 | 说明 |
|------|-----|------|
| Embedding Model | All-MiniLM-L6-v2 | 本地运行，384 维 |
| 向量维度 | 384 | 匹配 Embedding 模型 |
| 存储表 | `report_embeddings` | PGVector 表名 |
| 检索数量 | 3 | 每次返回最相似的 3 条 |

### 8.3 Metadata 策略

每份日报存储时附带元数据：
- `date` — 日报日期（用于时间范围过滤）
- `type` — 固定值 `daily-report`（区分不同类型文档）

---

## 9. Spring Shell 命令层

### 9.1 命令定义

```
report generate [OPTIONS]    生成工作日报
report help                  显示帮助信息
```

### 9.2 命令参数

| 参数 | 短标志 | 长标志 | 说明 | 示例 |
|------|--------|--------|------|------|
| Commit Hash | `-c` | `--commit` | Git Commit Hash | `abc1234` |
| Commit Range | `-r` | `--range` | Commit 范围 | `abc..def` |
| Jira Issue | `-j` | `--jira` | Jira Issue Key | `PROJ-123` |
| Repo Path | `-p` | `--repo` | Git 仓库路径 | `/path/to/repo` |

### 9.3 使用示例

```bash
# 基于单个 commit + Jira Issue 生成日报
shell:> report generate -c abc1234 -j PROJ-123

# 基于 commit 范围生成日报
shell:> report generate -r abc1234..def5678

# 指定仓库路径
shell:> report generate -c abc1234 -j PROJ-123 -p /path/to/repo
```

---

## 10. 配置管理

### 10.1 配置文件

**位置:** `src/main/resources/application.yaml`

### 10.2 配置项一览

| 分类 | 配置键 | 环境变量 | 默认值 | 说明 |
|------|--------|----------|--------|------|
| LLM | `langchain4j.open-ai.chat-model.base-url` | `LLM_BASE_URL` | `https://open.bigmodel.cn/api/paas/v4/` | API 基础地址 |
| LLM | `langchain4j.open-ai.chat-model.api-key` | `OPENAI_API_KEY` | `demo` | API 密钥 |
| LLM | `langchain4j.open-ai.chat-model.model-name` | `LLM_MODEL` | `glm-4-flash` | 模型名称 |
| LLM | `langchain4j.open-ai.chat-model.temperature` | — | `0.3` | 生成温度 |
| Memory | `langchain4j.chat-memory.max-messages` | — | `20` | 记忆窗口 |
| RAG | `langchain4j.rag.max-results` | — | `3` | 检索条数 |
| PGVector | `pgvector.host` | `PGVECTOR_HOST` | `localhost` | 数据库主机 |
| PGVector | `pgvector.port` | `PGVECTOR_PORT` | `5432` | 数据库端口 |
| PGVector | `pgvector.database` | `PGVECTOR_DATABASE` | `daily_report` | 数据库名 |
| PGVector | `pgvector.user` | `PGVECTOR_USER` | `postgres` | 用户名 |
| PGVector | `pgvector.password` | `PGVECTOR_PASSWORD` | `postgres` | 密码 |
| PGVector | `pgvector.table` | — | `report_embeddings` | 向量表名 |
| PGVector | `pgvector.dimension` | — | `384` | 向量维度 |
| Jira | `jira.base-url` | `JIRA_BASE_URL` | — | Jira 地址 |
| Jira | `jira.username` | `JIRA_USERNAME` | — | Jira 用户名 |
| Jira | `jira.api-token` | `JIRA_API_TOKEN` | — | Jira API Token |
| Git | `git.default-repo-path` | `GIT_REPO_PATH` | `./` | 默认仓库路径 |

### 10.3 安全要求

- **API Key 禁止硬编码** — 必须通过环境变量注入
- Jira API Token 同样通过环境变量管理
- PGVector 密码在生产环境必须使用安全的密钥管理方案

---

## 11. 设计模式总览

### 11.1 模式应用一览

| 设计模式 | 应用位置 | 说明 |
|---------|---------|------|
| **Adapter** | `JGitAdapter`, `JiraRestAdapter`, `AgentReportGenerator` | 将外部系统 API 适配为 Domain 端口接口 |
| **Strategy** | `ReportGenerator` 接口 | 允许替换不同的日报生成策略（AI/模板/混合） |
| **Factory Method** | `LangChain4jConfig`, `PgVectorConfig` 中的 `@Bean` | 封装复杂对象的创建逻辑 |
| **Builder** | `AiServices.builder()`, `PgVectorEmbeddingStore.builder()` | 分步构建复杂对象 |
| **Facade** | `DailyReportCommands`, `GitTool`, `JiraTool` | 简化复杂子系统的入口 |
| **Proxy** | `DailyReportAgent` (AiServices 动态代理) | LangChain4j 运行时代理生成 |
| **Repository** | `ReportStore` / `PgVectorReportStore` | 封装持久化细节 |
| **Composite** | `LangChain4jConfig` 组合 Tools+RAG+Memory | 组合多个组件形成完整 Agent |
| **Command** | `ReportRequest` | 封装请求参数为不可变对象 |

### 11.2 模式关系图

```
                    ┌─────────────────┐
         Facade ────│ DailyReportCmds │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │ GenerateReport  │
                    │   UseCase       │
                    └───┬─────────┬───┘
           Strategy     │         │    Repository
                ┌───────▼──┐  ┌──▼──────────┐
                │ Report   │  │ ReportStore  │
                │ Generator│  │ (PGVector)   │
                └───┬──────┘  └──────────────┘
          Adapter   │
            ┌───────▼──────────┐
            │ AgentReport      │
            │  Generator       │
            │  ├── Proxy ──► DailyReportAgent
            │  │              (AiServices)
            │  ├── Facade ─► GitTool
            │  │              ├── Adapter ──► JGitAdapter
            │  └── Facade ─► JiraTool
            │                  └── Adapter ──► JiraRestAdapter
            └──────────────────┘
```

---

## 12. SOLID 原则实践

### S — 单一职责原则

| 类 | 单一职责 |
|-----|---------|
| `GenerateReportUseCase` | 只编排生成流程 |
| `JGitAdapter` | 只封装 JGit 操作 |
| `JiraRestAdapter` | 只封装 Jira API |
| `AgentReportGenerator` | 只委托 Agent 生成 |
| `PgVectorReportStore` | 只管理向量存储 |
| `DailyReportCommands` | 只处理 CLI 交互 |

### O — 开闭原则

- 新增 LLM 提供者 → 新建 `XxxReportGenerator` 实现 `ReportGenerator` 接口
- 新增存储方式 → 新建 `XxxReportStore` 实现 `ReportStore` 接口
- 新增工具 → 在 `tools/` 下新建 `@Tool` 类并注入 Agent

### L — 里氏替换原则

- 所有 Domain Model 使用 Java 21 Record（不可变、无继承层级）
- 端口接口契约清晰，任何实现均可无缝替换

### I — 接口隔离原则

- `GitPort` — 只定义 Git 相关操作
- `JiraPort` — 只定义 Jira 相关操作
- `ReportStore` — 只定义存储/检索
- `ReportGenerator` — 只定义生成

### D — 依赖倒置原则

- Domain 层定义接口（Port）
- Infrastructure 层实现接口（Adapter）
- Application 层通过构造器注入依赖端口接口
- Spring IoC 容器在运行时完成装配

---

## 13. 本地开发指南

### 13.1 环境要求

- **JDK:** 21+
- **Docker:** 用于运行 PGVector
- **API Key:** OpenAI 或兼容 API

### 13.2 快速启动

```bash
# 1. 启动 PGVector
docker compose up -d

# 2. 配置环境变量（使用 .env 文件，自动加载）
cp .env.example .env
# 编辑 .env 填入 API Key 等配置

# 3. 构建项目
./gradlew build

# 4. 运行应用（自动加载 .env）
./gradlew bootRun

# 5. 在 Shell 中使用
shell:> report generate -c abc1234 -j PROJ-123
```

> **注意：** 项目使用 `spring-dotenv` 自动加载 `.env` 文件，无需手动 `export` 环境变量。

### 13.3 Docker Compose 服务

| 服务 | 镜像 | 端口 | 用途 |
|------|------|------|------|
| pgvector | `pgvector/pgvector:pg17` | 5432 | 向量数据库 |

### 13.4 常用命令

```bash
./gradlew build          # 编译 + 测试
./gradlew test           # 仅运行测试
./gradlew bootRun        # 启动应用
./gradlew clean build    # 清理重建
```

---

## 14. API 参考

### 14.1 Domain Model API

#### DailyReport

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `fromMarkdown(String)` | `DailyReport` | 静态工厂方法，从 Markdown 构建 |
| `date()` | `LocalDate` | 日报日期 |
| `rawMarkdown()` | `String` | 原始 Markdown 内容 |

#### CodeChange

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `shortId()` | `String` | 返回 7 位短 commit hash |
| `commitId()` | `String` | 完整 hash |
| `diff()` | `String` | 代码差异 |

#### JiraIssueInfo

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `businessContext()` | `String` | 格式化业务摘要 `[KEY] summary — description` |

#### ReportRequest

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `hasCommitRange()` | `boolean` | 是否有 commit 范围 |
| `hasJiraIssue()` | `boolean` | 是否有 Jira Issue |

### 14.2 Port 接口 API

#### GitPort

| 方法 | 参数 | 返回值 |
|------|------|--------|
| `getCommitDetail` | `(repoPath, commitHash)` | `CodeChange` |
| `getCommitRange` | `(repoPath, from, to)` | `List<CodeChange>` |
| `getDiff` | `(repoPath, commitHash)` | `String` |
| `getRecentCommits` | `(repoPath, count)` | `List<CodeChange>` |

#### JiraPort

| 方法 | 参数 | 返回值 |
|------|------|--------|
| `getIssue` | `(issueKey)` | `JiraIssueInfo` |

#### ReportGenerator

| 方法 | 参数 | 返回值 |
|------|------|--------|
| `generate` | `(ReportRequest)` | `DailyReport` |

#### ReportStore

| 方法 | 参数 | 返回值 |
|------|------|--------|
| `save` | `(DailyReport)` | `void` |
| `searchSimilar` | `(query, maxResults)` | `List<String>` |

---

*本文档随项目迭代持续更新。如有疑问请联系项目维护者。*
