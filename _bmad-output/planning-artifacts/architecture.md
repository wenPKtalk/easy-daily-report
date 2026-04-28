---
stepsCompleted: [1]
inputDocuments:
  - /Users/pengkunwen/tech/easy-daily-report/_bmad-output/project-context.md
workflowType: 'architecture'
project_name: 'easy-daily-report'
user_name: 'Pengkunwen'
date: '2026-04-27'
---

# 项目描述
**项目名称**：easy-daily-report 
**版本**：1.0 (MVP) 
**技术栈**：JDK 21 + Spring Boot (无 Web) + Spring Shell + LangChain4j + PGVector + Gradle 
**目标**：开发一个轻量级命令行（CLI）智能 Agent，能够结合 **Git Commit Code Diff** 和 **Jira Issue 描述**，通过 **ReAct 模式** + **RAG**（基于 PGVector）自动生成专业的工作日报。

# Architecture Decision Document: easy-daily-report

_This document builds collaboratively through step-by-step discovery. Sections are appended as we work through each architectural decision together._

本 Agent 帮助开发者/团队自动生成高质量工作日报。核心输入： 
- Git Commit Hash（或范围） 
- Jira Issue Key 
核心输出：结构化 Markdown/JSON 格式的工作日报，包含： 
- 日期与任务概述 
- 代码变更要点（技术细节提炼） 
- 业务价值（与 Jira 描述关联） 
- 潜在风险/优化建议 
- 明日计划 
**关键技术实践点**（你想完整掌握的流程）： 
- **Tools** 定义与调用（@Tool） 
- **ReAct** 模式（Thought → Action → Observation 循环） 
- **RAG** 全流程（Ingestion + Retrieval，使用 PGVector） 
- **Memory** 管理 
- **Structured Output** 
- LangChain4j + Spring Boot 集成（自动配置） 
- 轻量 CLI（Spring Shell） 

### 2. 系统架构图（概念描述）
用户 (CLI Shell)
   ↓ (输入命令: report generate --commit xxx --jira PROJ-123)
Spring Shell Commands
   ↓
DailyReportAgent (AiServices / Agentic)
   ├── System Prompt (ReAct 风格 + 角色设定)
   ├── ChatMemory (ConversationBuffer 或 TokenWindow)
   ├── Tools:
   │   ├── GitTool (JGit) → 获取 Code Diff
   │   ├── JiraTool (Jira REST Client) → 获取 Issue 详情
   │   └── RAG Retriever Tool → 从 PGVector 查询历史日报/项目知识
   └── LLM (OpenAI / Claude / Groq / Ollama)
         ↓ (ReAct 循环)
   生成结构化日报 → 输出到控制台 (Markdown)

**数据流**

1. 用户命令 → Shell Command 调用 Agent 
2. Agent 使用 ReAct 自主决定调用 Tool（可能多次循环） 
3. 需要历史上下文时 → 通过 Embedding + PGVector 检索相似日报 
4. 综合 Git Diff + Jira 描述 + RAG 结果 → 生成日报

### 3. 技术栈与版本推荐（2026 年最新实践)

- **JDK**：21（推荐，虚拟线程、Records 等现代特性） 
- **构建工具**：Gradle (Kotlin DSL 推荐) 
- **框架**： 
- Spring Boot 3.x（不包含 spring-boot-starter-web） 
- Spring Shell（spring-shell-starter）—— 提供交互式 CLI 
- LangChain4j（最新稳定版，推荐 1.x 系列） 
- **向量数据库**：PostgreSQL + pgvector 扩展 
- **Embedding 模型**：All-MiniLM-L6-v2（本地，轻量）或 OpenAI text-embedding-3-small 
- **LLM**：支持 OpenAI、Anthropic Claude、Groq、Ollama（本地 Llama3.1/DeepSeek 等用于快速迭代） 
- **Git 操作**：JGit（纯 Java，无需系统 Git） 
- **Jira 集成**：Atlassian Jira REST Java Client（或 Spring WebClient 手动调用） 
- **其他**：Lombok（可选）、SLF4J + Logback 

### 4. 项目结构（推荐）

daily-report-agent/
├── build.gradle.kts
├── settings.gradle.kts
├── src/main/java/
│   ├── com/example/dailyreport/
│   │   ├── DailyReportAgentApplication.java          // 主启动类
│   │   ├── shell/                                    // Spring Shell 命令
│   │   │   └── DailyReportCommands.java
│   │   ├── agent/                                    // Agent 定义
│   │   │   └── DailyReportAgent.java                 // AiServices 接口
│   │   ├── tools/                                    // Tools
│   │   │   ├── GitTools.java
│   │   │   └── JiraTools.java
│   │   ├── rag/                                      // RAG 相关
│   │   │   ├── IngestionService.java
│   │   │   └── ReportRetriever.java
│   │   ├── service/
│   │   │   ├── GitService.java
│   │   │   └── JiraService.java
│   │   └── config/                                   // 配置类
│   │       ├── LangChain4jConfig.java
│   │       └── PgVectorConfig.java
├── src/main/resources/
│   ├── application.yml                               // 配置（LLM、PGVector、Jira 等）
│   └── prompts/                                      // Prompt 模板（可选）
└── docker-compose.yml                                // PGVector + Ollama（本地开发）

### 5. 关键依赖配置（build.gradle.kts 示例）
kotlin
plugins {
    java
    id("org.springframework.boot") version "3.4.0"  // 或最新
    id("io.spring.dependency-management") version "1.1.6"
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.shell:spring-shell-starter")

    // LangChain4j
    implementation("dev.langchain4j:langchain4j-spring-boot-starter")
    implementation("dev.langchain4j:langchain4j-pgvector")
    implementation("dev.langchain4j:langchain4j-embeddings-all-minilm")

    // LLM（按需选择）
    implementation("dev.langchain4j:langchain4j-open-ai")
    // implementation("dev.langchain4j:langchain4j-anthropic") 等

    // Git & Jira
    implementation("org.eclipse.jgit:org.eclipse.jgit")
    implementation("com.atlassian.jira:jira-rest-java-client-core") // 注意版本兼容

    runtimeOnly("org.postgresql:postgresql")
    // testImplementation...
}

### 6. 核心组件设计 
**6.1 配置（application.yml）** 
- LLM 配置（model、temperature、api-key） 
- PGVector 配置（host、port、database、维度、表名、索引） 
- Jira 配置（base-url、username、token） 
- Git 默认仓库路径 

**6.2 PGVector RAG 配置** 
- 使用 PgVectorEmbeddingStore + EmbeddingStoreContentRetriever。 
- 支持 metadata 过滤（项目名、日期范围）。 
- Ingestion Pipeline：加载历史日报 → Splitter → Embedding → 存储。 

**6.3 Tools** 
- GitTools：@Tool 方法获取 diff、列出最近 commits。 
- JiraTools：@Tool 方法获取 issue summary + description。 
- 可选 RAG Tool，让 Agent 自主决定是否检索历史。 

**6.4 ReAct Agent** 
- 使用 AiServices.builder() 注入 Tools + ContentRetriever + ChatMemory。 
- 系统 Prompt 明确要求使用 **ReAct** 格式思考，并生成结构化输出（推荐使用 JSON Schema 或 Structured Output）。 

**6.5 Spring Shell 命令层** 
- 简单 Command 类，调用 Agent 方法，输出 Markdown（支持颜色高亮）。 

---

## Document Setup

**Created:** 2026-04-27
**Project:** easy-daily-report
**Type:** Spring Shell CLI Application

**Input Documents:**
- Project Context (technology stack and coding rules)

---

*Ready for architectural decision making. Awaiting user to continue to context analysis.*
