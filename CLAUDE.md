# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Vector store defaults to DuckDB (embedded, in-process, single file at ./data/) — no server needed.
# Only start PGVector if you set REPORT_STORE_TYPE=pgvector:
#   docker compose up -d

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

Copy `.env.example` to `.env` — the app auto-loads it via `spring-dotenv`, no manual `export` needed. Required key: `OPENAI_API_KEY`. The vector store defaults to embedded **DuckDB** (no server). Set `REPORT_STORE_TYPE=pgvector` only if you want the Postgres/pgvector backend, in which case PGVector must be running before startup.

## Architecture

DDD hexagonal architecture with five layers:

```
shell/                  → Spring Shell CLI: report generate / generate-today (--level switches strategy)
application/            → Strategy pattern: GenerateAgent interface + 3 implementations + AgentRouter
agent/subagents         → LangChain4j AiService interfaces for parallel Multi-Agent sub-agents
agent/coordinator       → LangChain4j AiService interface for the COORDINATOR_AGENT Master Agent
domain/                 → Models (record types) + port interfaces (zero external deps)
infrastructure/         → Adapters: JGit, Jira REST, LangChain4j AI, DuckDB/PGVector embedding store + config beans
```

### Strategy Pattern: GenerateAgent

`GenerateAgent` is the central strategy interface with three implementations:

1. **`GenerateReportUseCase`** (`SINGLE`) — single-agent ReAct loop via `DailyReportAgent` (LangChain4j tool-calling with `GitTool` + `JiraTool`, includes RAG)
2. **`MultiAgentOrchestrator`** (`SAMPLE_MULTIPLE`) — parallel multi-agent with static Java orchestration: `GitDiffAnalyzerAgent` and `JiraAnalyzerAgent` run concurrently via `CompletableFuture`, then `ReportGeneratorAgent` synthesizes the report
3. **`CoordinatorOrchestrator`** (`COORDINATOR_AGENT`) — Master/Sub multi-agent with LLM-driven dynamic orchestration: `CoordinatorAgent` (AiService with ReAct) decides at runtime which of the 4 `@Tool` methods on `SubAgentDelegationTool` to call (`analyzeGitChanges` / `analyzeJiraIssue` / `retrieveSimilarReports` / `composeFinalReport`), and in what order

`AgentRouter` selects the implementation by `AgentLevel`. The shell layer injects `AgentRouter` and exposes the choice via `--level`. `AgentLevel` enum: `SINGLE` / `SAMPLE_MULTIPLE` / `COORDINATOR_AGENT`.

### Key Integration Points

- **LangChain4j `AiServices`**: All agents (single `DailyReportAgent`, three sub-agents, `CoordinatorAgent`) are Java interfaces assembled in `LangChain4jConfig` / `MultiAgentConfig` / `CoordinatorConfig` via `AiServices.builder()`
- **RAG**: `EmbeddingStoreReportStore` (store-agnostic adapter) stores report embeddings using `All-MiniLM-L6-v2` (384-dim) in `report_embeddings`. The backing `EmbeddingStore` bean is selected by `report.store.type`: **`duckdb`** (default, `DuckDBConfig`, embedded single-file) or `pgvector` (`PgVectorConfig`). SINGLE path consumes it via `ContentRetriever`; COORDINATOR_AGENT path consumes it via the `retrieveSimilarReports` `@Tool` (calls `ReportStore.searchSimilar()` directly)
- **LLM config**: `ChatModelConfig` reads `LlmProperties` — supports `openai-compatible` (ZhipuAI/OpenAI) and `ollama` providers via `LLM_PROVIDER` env var

### Domain Models (Records)

- `ReportRequest` — input: commitHash, commitRange, jiraIssueKey, repoPath
- `DailyReport` — output: markdown content + metadata; factory `DailyReport.fromMarkdown(String)`
- `CodeChange`, `JiraIssueInfo` — intermediate data

### Port → Adapter Mapping

- `GitPort` → `JGitAdapter`
- `JiraPort` → `JiraRestAdapter`
- `ReportGenerator` → `AgentReportGenerator`
- `ReportStore` → `EmbeddingStoreReportStore` (backed by DuckDB or PGVector `EmbeddingStore`, per `report.store.type`)

## Tech Stack

Java 21, Spring Boot 4.0.6, Spring Shell 4.0.1, LangChain4j 1.13.1 (+ `langchain4j-community-duckdb` 1.0.0-beta5), JGit 7.2.0, DuckDB (embedded, default) / PGVector (pg17, optional), Lombok, Gradle
