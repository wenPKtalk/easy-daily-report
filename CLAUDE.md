# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# No database server needed — storage is fully embedded (DuckDB vectors + H2 chat, both single-file in ./data/).

# Build and run (recommended)
./run.sh

# Force rebuild then run
./run.sh -b

# Gradle commands
./gradlew build          # compile + test
./gradlew test           # run tests only
./gradlew bootRun        # start application
./gradlew clean build    # clean rebuild
./gradlew compileJava    # verify compilation only
```

## Running a Single Test

```bash
./gradlew test --tests "com.topsion.easy_daily_report.SomeTest"
./gradlew test --tests "com.topsion.easy_daily_report.*"
```

## Environment Setup

Copy `.env.example` to `.env` — the app auto-loads it via `spring-dotenv`, no manual `export` needed. Required key: `OPENAI_API_KEY`. **No database server is required** — vectors persist in embedded DuckDB and chat/session state in embedded H2, both single-file under `./data/`.

## Architecture

DDD hexagonal architecture with five layers:

```
shell/                  → Spring Shell CLI: report generate / generate-today (--level switches strategy)
application/            → Strategy pattern: GenerateAgent interface + 3 implementations + AgentRouter
agent/subagents         → LangChain4j AiService interfaces for parallel Multi-Agent sub-agents
agent/coordinator       → LangChain4j AiService interface for the COORDINATOR_AGENT Master Agent
domain/                 → Models (record types) + port interfaces (zero external deps)
infrastructure/         → Adapters: JGit, Jira REST, LangChain4j AI, DuckDB embedding store, H2 chat store + config beans
```

### Strategy Pattern: GenerateAgent

`GenerateAgent` is the central strategy interface with three implementations:

1. **`GenerateReportUseCase`** (`SINGLE`) — single-agent ReAct loop via `DailyReportAgent` (LangChain4j tool-calling with `GitTool` + `JiraTool`, includes RAG)
2. **`MultiAgentOrchestrator`** (`SAMPLE_MULTIPLE`) — parallel multi-agent with static Java orchestration: `GitDiffAnalyzerAgent` and `JiraAnalyzerAgent` run concurrently via `CompletableFuture`, then `ReportGeneratorAgent` synthesizes the report
3. **`CoordinatorOrchestrator`** (`COORDINATOR_AGENT`) — Master/Sub multi-agent with LLM-driven dynamic orchestration: `CoordinatorAgent` (AiService with ReAct) decides at runtime which of the 4 `@Tool` methods on `SubAgentDelegationTool` to call (`analyzeGitChanges` / `analyzeJiraIssue` / `retrieveSimilarReports` / `composeFinalReport`), and in what order

`AgentRouter` selects the implementation by `AgentLevel`. The shell layer injects `AgentRouter` and exposes the choice via `--level`. `AgentLevel` enum: `SINGLE` / `SAMPLE_MULTIPLE` / `COORDINATOR_AGENT`.

### Key Integration Points

- **LangChain4j `AiServices`**: All agents (single `DailyReportAgent`, three sub-agents, `CoordinatorAgent`) are Java interfaces assembled in `LangChain4jConfig` / `MultiAgentConfig` / `CoordinatorConfig` via `AiServices.builder()`
- **RAG**: `EmbeddingStoreReportStore` (store-agnostic adapter) stores report embeddings using `All-MiniLM-L6-v2` (384-dim) in `report_embeddings`, backed by embedded **DuckDB** (`DuckDBConfig`, single-file, no server). SINGLE path consumes it via `ContentRetriever`; COORDINATOR_AGENT path consumes it via the `retrieveSimilarReports` `@Tool` (calls `ReportStore.searchSimilar()` directly)
- **Chat persistence**: `ChatSessionRepository` (JdbcTemplate) persists sessions/turns to embedded **H2** (single-file `./data/chat`); schema in `db/init-chat-tables.sql` run at startup via `spring.sql.init`
- **LLM config**: `ChatModelConfig` reads `LlmProperties` — supports `openai-compatible` (ZhipuAI/OpenAI) and `ollama` providers via `LLM_PROVIDER` env var

### Domain Models (Records)

- `ReportRequest` — input: commitHash, commitRange, jiraIssueKey, repoPath
- `DailyReport` — output: markdown content + metadata; factory `DailyReport.fromMarkdown(String)`
- `CodeChange`, `JiraIssueInfo` — intermediate data

### Port → Adapter Mapping

- `GitPort` → `JGitAdapter`
- `JiraPort` → `JiraRestAdapter`
- `ReportGenerator` → `AgentReportGenerator`
- `ReportStore` → `EmbeddingStoreReportStore` (backed by embedded DuckDB `EmbeddingStore`)

## Tech Stack

Java 21, Spring Boot 4.0.6, Spring Shell 4.0.1, LangChain4j 1.13.1 (+ `langchain4j-community-duckdb` 1.0.0-beta5), JGit 7.2.0, embedded storage: DuckDB (vectors) + H2 (chat) — no DB server, Lombok, Gradle
