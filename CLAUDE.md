# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Start PGVector (required before running the app)
docker compose up -d

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

Copy `.env.example` to `.env` — the app auto-loads it via `spring-dotenv`, no manual `export` needed. Required key: `OPENAI_API_KEY`. PGVector must be running before startup.

## Architecture

DDD hexagonal architecture with five layers:

```
shell/          → Spring Shell CLI: report generate / report generate-today
application/    → Strategy pattern: GenerateAgent interface + 2 implementations + AgentRouter
agent/subagents → LangChain4j AiService interfaces for Multi-Agent sub-agents
domain/         → Models (record types) + port interfaces (zero external deps)
infrastructure/ → Adapters: JGit, Jira REST, LangChain4j AI, PGVector + config beans
```

### Strategy Pattern: GenerateAgent

`GenerateAgent` is the central strategy interface with two implementations:

1. **`GenerateReportUseCase`** — single-agent ReAct loop via `DailyReportAgent` (LangChain4j tool-calling with `GitTool` + `JiraTool`, includes RAG)
2. **`MultiAgentOrchestrator`** — parallel multi-agent: `GitDiffAnalyzerAgent` and `JiraAnalyzerAgent` run concurrently via `CompletableFuture`, then `ReportGeneratorAgent` synthesizes the report

`AgentRouter` holds the strategy selection logic (in-progress; shell currently injects `GenerateReportUseCase` directly). `AgentLevel` enum: `SINGLE` / `SAMPLE_MULTIPLE` / `COORDINATOR_AGENT`.

### Key Integration Points

- **LangChain4j `AiServices`**: All agents (single `DailyReportAgent` + three sub-agents) are Java interfaces assembled in `LangChain4jConfig` / `MultiAgentConfig` via `AiServices.builder()`
- **RAG**: `PgVectorReportStore` stores report embeddings using `All-MiniLM-L6-v2` (384-dim) in `report_embeddings`; used by the single-agent path's `ContentRetriever`
- **LLM config**: `ChatModelConfig` reads `LlmProperties` — supports `openai-compatible` (ZhipuAI/OpenAI) and `ollama` providers via `LLM_PROVIDER` env var

### Domain Models (Records)

- `ReportRequest` — input: commitHash, commitRange, jiraIssueKey, repoPath
- `DailyReport` — output: markdown content + metadata; factory `DailyReport.fromMarkdown(String)`
- `CodeChange`, `JiraIssueInfo` — intermediate data

### Port → Adapter Mapping

- `GitPort` → `JGitAdapter`
- `JiraPort` → `JiraRestAdapter`
- `ReportGenerator` → `AgentReportGenerator`
- `ReportStore` → `PgVectorReportStore`

## Tech Stack

Java 21, Spring Boot 4.0.6, Spring Shell 4.0.1, LangChain4j 1.13.1, JGit 7.2.0, PGVector (pg17), Lombok, Gradle
