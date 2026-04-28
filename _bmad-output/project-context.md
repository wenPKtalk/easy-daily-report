---
project_name: 'easy-daily-report'
user_name: 'Pengkunwen'
date: '2026-04-27'
sections_completed: ['technology_stack', 'architecture', 'implementation_rules']
existing_patterns_found: 6
version: 1.0.0
---

# Project Context: easy-daily-report

**Purpose:** 本文档包含 AI Agent 在为 easy-daily-report 项目编写代码时必须遵循的关键规则、模式和约定。

**Last Updated:** 2026-04-27

---

## 1. 项目概述

**目标：** 开发一个轻量级命令行（CLI）智能 Agent，结合 **Git Commit Code Diff** 和 **Jira Issue 描述**，通过 **ReAct 模式** + **RAG**（基于 PGVector）自动生成专业工作日报。

**核心输入：**
- Git Commit Hash（或范围）
- Jira Issue Key

**核心输出：** 结构化 Markdown/JSON 格式的工作日报，包含日期与任务概述、代码变更要点、业务价值、潜在风险/优化建议、明日计划。

---

## 2. 技术栈

### 核心框架
- **Language:** Java 21（虚拟线程、Records 等现代特性）
- **Framework:** Spring Boot 4.0.6（**无 Web**，不包含 spring-boot-starter-web）
- **CLI:** Spring Shell 4.0.1（spring-shell-starter）
- **AI Framework:** LangChain4j（1.x 系列）
- **Build Tool:** Gradle

### AI / LLM
- **LLM:** 支持 OpenAI、Anthropic Claude、Groq、Ollama（本地 Llama3.1/DeepSeek）
- **Embedding 模型:** All-MiniLM-L6-v2（本地轻量）或 OpenAI text-embedding-3-small
- **向量数据库:** PostgreSQL + pgvector 扩展
- **Agent 模式:** ReAct（Thought → Action → Observation 循环）

### 外部集成
- **Git 操作:** JGit（纯 Java，无需系统 Git）
- **Jira 集成:** Atlassian Jira REST Java Client（或 Spring WebClient）

### 测试
- **Framework:** JUnit 5 (Jupiter)
- **Integration:** Spring Boot Test

### 其他
- Lombok（可选）、SLF4J + Logback

---

## 3. 项目结构

```
com/topsion/easy_daily_report/
├── EasyDailyReportApplication.java        // 主启动类
├── shell/                                  // Spring Shell 命令
│   └── DailyReportCommands.java
├── agent/                                  // Agent 定义
│   └── DailyReportAgent.java              // AiServices 接口
├── tools/                                  // LangChain4j Tools
│   ├── GitTools.java
│   └── JiraTools.java
├── rag/                                    // RAG 相关
│   ├── IngestionService.java
│   └── ReportRetriever.java
├── service/                                // 业务服务
│   ├── GitService.java
│   └── JiraService.java
└── config/                                 // 配置类
    ├── LangChain4jConfig.java
    └── PgVectorConfig.java
```

**资源文件：**
```
src/main/resources/
├── application.yml                         // LLM、PGVector、Jira 等配置
└── prompts/                                // Prompt 模板（可选）
```

**根目录：**
```
├── build.gradle
├── settings.gradle
└── docker-compose.yml                      // PGVector + Ollama（本地开发）
```

---

## 4. 系统架构

### 数据流
1. 用户命令 → Spring Shell Command 调用 Agent
2. Agent 使用 ReAct 自主决定调用 Tool（可能多次循环）
3. 需要历史上下文时 → 通过 Embedding + PGVector 检索相似日报
4. 综合 Git Diff + Jira 描述 + RAG 结果 → 生成结构化日报

### 核心组件
- **DailyReportAgent** — AiServices 接口，注入 Tools + ContentRetriever + ChatMemory
- **GitTools** — `@Tool` 方法获取 diff、列出最近 commits
- **JiraTools** — `@Tool` 方法获取 issue summary + description
- **RAG Retriever Tool** — 从 PGVector 查询历史日报/项目知识
- **ChatMemory** — ConversationBuffer 或 TokenWindow
- **System Prompt** — ReAct 风格 + 角色设定

---

## 5. 关键实现规则

### Java 约定
- **Package:** `com.topsion.easy_daily_report`
- 使用 Java 21 特性：Records（DTO）、虚拟线程
- 使用 Spring Boot 4.x 自动配置模式

### LangChain4j 规则
- 使用 `AiServices.builder()` 构建 Agent，注入 Tools + ContentRetriever + ChatMemory
- Tools 使用 `@Tool` 注解定义
- System Prompt 必须明确要求 ReAct 格式思考
- 结构化输出使用 JSON Schema 或 Structured Output

### RAG 规则
- 使用 `PgVectorEmbeddingStore` + `EmbeddingStoreContentRetriever`
- 支持 metadata 过滤（项目名、日期范围）
- Ingestion Pipeline：加载历史日报 → Splitter → Embedding → 存储

### Spring Shell 规则
- 命令类使用 `@ShellMethod` 注解
- 命令调用 Agent 方法，输出 Markdown（支持颜色高亮）
- 命令组织为 Spring 组件

### 配置管理（application.yml）
- LLM 配置：model、temperature、api-key
- PGVector 配置：host、port、database、维度、表名、索引
- Jira 配置：base-url、username、token
- Git 默认仓库路径

### 测试规则
- 使用 JUnit 5 (Jupiter) — `org.junit.jupiter.api.Test`
- 使用 `@SpringBootTest` 进行集成测试
- 测试类命名：`{ClassName}Tests`

---

## 6. 开发工作流

### 构建命令
```bash
./gradlew build       # 构建项目
./gradlew test        # 运行测试
./gradlew bootRun     # 运行应用
```

### 本地开发环境
- 使用 `docker-compose.yml` 启动 PGVector + Ollama
- API Key 通过环境变量或 application.yml 配置，**禁止硬编码**

---

## 7. 关键技术实践点

AI Agent 开发过程中需要完整掌握的流程：
- **Tools** 定义与调用（@Tool）
- **ReAct** 模式（Thought → Action → Observation 循环）
- **RAG** 全流程（Ingestion + Retrieval，使用 PGVector）
- **Memory** 管理
- **Structured Output**
- LangChain4j + Spring Boot 集成（自动配置）
- 轻量 CLI（Spring Shell）
