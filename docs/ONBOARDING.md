# Onboarding Guide — easy-daily-report

> Generated from `.understand-anything/knowledge-graph.json` (commit `fc7a29c`, analyzed 2026-05-25).
> This guide is auto-derived from a 192-node / 352-edge semantic graph of the codebase. Re-run `/understand-anything:understand-onboard` after major refactors to refresh it.

## Project Overview

**easy-daily-report** is an AI-driven daily report generator that fuses Git commits and Jira issues into structured Markdown reports using LangChain4j. It is built as a DDD hexagonal architecture with **two pluggable report-generation strategies behind a single `GenerateAgent` interface**:

- **Single-agent ReAct loop** — one LangChain4j `AiService` that plans, calls tools (`GitTool`, `JiraTool`), and synthesizes a report.
- **Parallel multi-agent orchestrator** — fans out `GitDiffAnalyzerAgent` + `JiraAnalyzerAgent` concurrently via `CompletableFuture`, then a `ReportGeneratorAgent` synthesizes the final report (~38% faster per the README).

A **chat mode** sits on top, using a `SupervisorAgent` to classify user intent (generate / follow-up / clarify / mode-switch) and route to the right strategy.

| | |
|---|---|
| **Languages** | Java, SQL, YAML, Markdown, Gradle, Properties, Shell, Batch |
| **Frameworks** | Spring Boot 4.0.6, Spring Shell 4.0.1, LangChain4j 1.13.1, JGit 7.2.0, PGVector (pg17), Lombok, JUnit, Docker Compose |
| **JDK** | Java 21 |
| **Persistence** | PostgreSQL + `pgvector` for RAG over historical reports; JSONB for chat session state |

## Architecture at a Glance

The codebase is organized as ten semantic layers. The inner four (Domain Core → Application Strategies → Agent Layer → Infrastructure Adapters) form the classic hexagon; the rest support and surround it.

```
                                  ┌────────────────────────┐
                                  │       CLI Shell        │  DailyReportCommands, ChatCommands
                                  └───────────┬────────────┘
                                              │
                                  ┌───────────▼────────────┐
                                  │ Application Strategies │  GenerateAgent, AgentRouter,
                                  │                        │  GenerateReportUseCase,
                                  │                        │  MultiAgentOrchestrator,
                                  │                        │  ChatOrchestrator, ChatSession
                                  └─────┬────────────┬─────┘
                                        │            │
                       ┌────────────────▼─┐     ┌────▼─────────────────┐
                       │   Agent Layer    │     │     Domain Core      │
                       │  (AiServices &   │     │  records + ports     │
                       │   supervisor)    │     │  (zero ext. deps)    │
                       └────────┬─────────┘     └────────────▲─────────┘
                                │                            │
                                │             ┌──────────────┴───────────┐
                                └────────────►│ Infrastructure Adapters  │ JGit, Jira REST,
                                              │                          │ pgvector, LC4j tools,
                                              │                          │ chat session repo
                                              └────────────┬─────────────┘
                                                           │
                                              ┌────────────▼─────────────┐
                                              │  Spring Configuration    │ wires every bean
                                              └────────────┬─────────────┘
                                                           │
   ┌─────────────────┐   ┌─────────────────────┐   ┌───────▼──────────┐   ┌──────────────────┐
   │  Database Schema│   │ Build/Infrastructure│   │  Documentation   │   │   Test Suite     │
   └─────────────────┘   └─────────────────────┘   └──────────────────┘   └──────────────────┘
```

### Architecture Layers

1. **CLI Shell** (3 nodes) — Spring Boot entry point and Spring Shell command handlers that expose the daily-report generator as an interactive CLI.
2. **Application Strategies** (8 nodes) — `GenerateAgent` strategy interface, `GenerateReportUseCase` (single-agent ReAct), `MultiAgentOrchestrator` (parallel sub-agents), `AgentRouter`, and chat orchestration (`ChatOrchestrator`, `ChatSession`).
3. **Agent Layer** (6 nodes) — LangChain4j `AiService` interfaces and supervisor types: `GitDiffAnalyzerAgent`, `JiraAnalyzerAgent`, `ReportGeneratorAgent`, plus `SupervisorAgent`, `Intent`, `SupervisorDecision`.
4. **Domain Core** (8 nodes) — Pure domain records (`DailyReport`, `ReportRequest`, `CodeChange`, `JiraIssueInfo`) and port interfaces (`GitPort`, `JiraPort`, `ReportGenerator`, `ReportStore`) with zero external dependencies.
5. **Infrastructure Adapters** (9 nodes) — Concrete adapters: `JGitAdapter`, `JiraRestAdapter`, `PgVectorReportStore`, `ChatSessionRepository`, `AgentReportGenerator`, `DailyReportAgent`, and the LangChain4j tool wrappers (`GitTool`, `JiraTool`, `SessionContextTool`).
6. **Spring Configuration** (7 nodes) — `@Configuration` beans that wire the runtime: `LangChain4jConfig`, `MultiAgentConfig`, `SupervisorConfig`, `ChatModelConfig`, `PgVectorConfig`, `ShellConfig`, and `LlmProperties`.
7. **Database Schema** (13 nodes) — pgvector init scripts and table definitions: `db/init/*.sql` plus an in-resources chat-tables migration, defining `report_embeddings`, `daily_reports`, `code_changes`, `chat_sessions`, `conversation_turns`.
8. **Test Suite** (8 nodes) — JUnit unit and contract tests mirroring the main package layout.
9. **Build & Infrastructure** (13 nodes) — Gradle files, wrapper scripts (`gradlew`, `run.sh`, `run.bat`, `db/migrate.sh`), Docker Compose for pgvector, environment templates, and Spring `application.yaml`.
10. **Documentation** (9 nodes) — `README.md`, `CLAUDE.md`, `HELP.md`, the `docs/` guides, and `db/README.md`.

## Key Concepts

### 1. Strategy Pattern — `GenerateAgent`
A single-method interface (`generate(ReportRequest) → DailyReport`) is the central seam of the codebase. Two implementations live behind it:

- `GenerateReportUseCase` — thin application service that calls the `ReportGenerator` port; the heavy lifting is the LangChain4j `DailyReportAgent` AiService underneath.
- `MultiAgentOrchestrator` — runs Git/Jira analyzers in parallel, then synthesizes.

`AgentLevel` enum (`SINGLE`, `SAMPLE_MULTIPLE`, `COORDINATOR_AGENT`) is the most depended-upon enum in the project — 11 files key off it. `AgentRouter` holds the selection logic.

### 2. Hexagonal Ports & Adapters
The domain layer declares **what it needs** (`GitPort`, `JiraPort`, `ReportGenerator`, `ReportStore`); infrastructure declares **how it talks to the world** (`JGitAdapter`, `JiraRestAdapter`, `AgentReportGenerator`, `PgVectorReportStore`). JGit / `HttpClient` / pgvector types never appear above the infrastructure boundary.

### 3. LangChain4j `AiServices` as Java Interfaces
Every agent — `DailyReportAgent`, the three sub-agents, and `SupervisorAgent` — is a plain Java interface with a `@SystemMessage` annotation. LangChain4j dynamically proxies them in `LangChain4jConfig` / `MultiAgentConfig` / `SupervisorConfig` via `AiServices.builder()`, attaching the chat model, tools (`@Tool`-annotated Spring components), and a `MessageWindowChatMemory`.

### 4. RAG with pgvector
`PgVectorReportStore` embeds each generated report with `AllMiniLm-L6-v2` (384-dim) and stores it in `report_embeddings`. The single-agent path uses `EmbeddingStoreContentRetriever` so every new report run can semantically pull in similar past reports — each report becomes context for the next.

### 5. Chat Mode with Supervisor Routing
`ChatOrchestrator` asks `SupervisorAgent` to classify a message into a `SupervisorDecision` (intent + optional extracted params or clarification). `ChatSession` is an **immutable aggregate root** (copy-on-write mutators `withMode` / `appendTurn` / `updateContext`), persisted by `ChatSessionRepository` using upserts with an explicit `?::jsonb` cast for the JSONB context column. A `MODE_SWITCH` intent can be promoted to a manual override flag on the session.

### 6. Provider-Agnostic LLM Config
`ChatModelConfig` reads `LlmProperties` (a `@ConfigurationProperties` record) and dispatches to either an OpenAI-compatible provider (ZhipuAI, OpenAI) or Ollama based on the `LLM_PROVIDER` env var.

### 7. `.env` Auto-Load via `spring-dotenv`
No manual `export` needed — `run.sh` (and `EasyDailyReportApplication` startup) loads `.env` for you. Required keys: `OPENAI_API_KEY` plus PGVector/Jira/Git settings (see `.env.example`).

## Guided Tour (10 Steps)

Follow these in order to build a working mental model of the codebase.

### Step 1 — Project Overview & Mental Model
Read the three top-level guides to frame the rest of the tour: `README.md` previews the two strategies, `CLAUDE.md` locks in the build commands and hexagonal layering, and `docs/how-it-works.md` ties architecture to runtime behaviour (including the RAG path closing Step 10).

### Step 2 — Bootstrap: From `run.sh` to Spring Context
`run.sh` is the practical entry point — it loads `.env` (via `spring-dotenv`) and invokes `./gradlew bootRun`. Control reaches `EasyDailyReportApplication`, a one-line `@SpringBootApplication` whose 15 inbound dependencies make it the structural hub. `.env.example` and `application.yaml` document everything the context needs at startup.

### Step 3 — The CLI Surface: Spring Shell Commands
Two entry points sit on the context:
- `DailyReportCommands` — `report generate` and `report generate-today`, builds a `ReportRequest` and hands it to the injected `GenerateAgent` strategy.
- `ChatCommands` — interactive JLine read-loop for the `chat` command; dispatches slash builtins (`/mode`, `/clear`, `/history`, etc.) and persists the session after every turn.

### Step 4 — The Strategy Pattern: `GenerateAgent` + `AgentRouter`
The architectural pivot of the codebase. `GenerateAgent` is the strategy contract. `AgentLevel` enumerates the three strategies. `AgentRouter.route(AgentLevel)` returns the right concrete implementation.

### Step 5 — Strategy A: Single-Agent ReAct Loop
- `GenerateReportUseCase` — thin application service.
- `DailyReportAgent` — LangChain4j AiService whose `@SystemMessage` describes a ReAct loop.
- `AgentReportGenerator` — port adapter owning the AiService instance.
- `GitTool` / `JiraTool` — expose `GitPort` / `JiraPort` as `@Tool` methods so the ReAct loop can self-invoke I/O.
- `LangChain4jConfig` — assembles the AiService with chat model, memory, RAG retriever, and tools.

### Step 6 — Strategy B: Multi-Agent Parallel Orchestrator
- `MultiAgentOrchestrator` — fans out Git and Jira analyzers via `CompletableFuture.supplyAsync`, awaits both JSON outputs, then calls the synthesis agent.
- Three sub-agents: `GitDiffAnalyzerAgent`, `JiraAnalyzerAgent`, `ReportGeneratorAgent`.
- `MultiAgentConfig` builds all three AiService beans.
- `docs/single_vs_sample_multiple_agent.md` is the side-by-side comparison and migration guide.

### Step 7 — Domain Core: Records and Hexagonal Ports
The inner ring: immutable records (`DailyReport`, `ReportRequest`, `CodeChange`, `JiraIssueInfo`) and three port interfaces (`GitPort`, `JiraPort`, `ReportStore`) with **no external dependencies**.

### Step 8 — Infrastructure Adapters: Talking to the World
- `JGitAdapter` implements `GitPort` — opens the repo with JGit, walks `RevCommit`s, formats unified diffs into `CodeChange` records.
- `JiraRestAdapter` implements `JiraPort` — uses Java's built-in `HttpClient` with Basic-Auth against Jira REST v2.

The LLM tools (`GitTool`, `JiraTool`) wrap these adapters indirectly through the ports — agents never touch JGit or `HttpClient` directly.

### Step 9 — Chat Mode: Supervisor Routing & Session State
- `ChatOrchestrator` — asks `SupervisorAgent` for an `Intent`, mutates session context via `SessionContextTool`, dispatches to a direct/clarification/routed response.
- `ChatSession` — immutable aggregate root holding `AgentLevel` + override flag, context map, and conversation history.
- `ChatSessionRepository` — JDBC upsert on `chat_sessions` with `?::jsonb` cast for the JSONB column; appends the latest turn to `conversation_turns`.
- `SupervisorConfig` — wires `SupervisorAgent` with `SessionContextTool`, `GitTool`, and a 20-message `MessageWindowChatMemory`.

### Step 10 — Persistence & RAG: Closing the Loop
- `PgVectorReportStore` embeds each new `DailyReport` (384-dim All-MiniLM-L6-v2) and writes it to `report_embeddings` (auto-created by LangChain4j).
- `daily_reports` stores the raw markdown plus extracted fields and provenance.
- `chat_sessions` / `conversation_turns` persist chat state.
- `docker-compose.yml` stands up `pgvector/pgvector:pg17` with the init scripts mounted.
- Every report becomes RAG context for the next run via `ContentRetriever`.

## File Map (by Layer)

### CLI Shell
| File | Complexity | What it does |
|---|---|---|
| `src/main/java/.../EasyDailyReportApplication.java` | simple | `@SpringBootApplication` bootstrap entry point (15 inbound deps — the structural hub). |
| `src/main/java/.../shell/DailyReportCommands.java` | moderate | Spring Shell commands `report generate` / `report generate-today`; builds `ReportRequest` and invokes the injected `GenerateAgent`. |
| `src/main/java/.../shell/ChatCommands.java` | **complex** | `chat`/`@chat` commands; JLine read-loop, slash builtins (`/mode`, `/clear`, `/new`, `/history`, `/context`, `/help`, `/exit`), per-turn persistence. |

### Application Strategies
| File | Complexity | What it does |
|---|---|---|
| `application/usecase/GenerateAgent.java` | simple | Central strategy interface for daily-report generation. |
| `application/usecase/AgentLevel.java` | simple | Enum: `SINGLE`, `SAMPLE_MULTIPLE`, `COORDINATOR_AGENT`. |
| `application/usecase/AgentRouter.java` | simple | Strategy selector that returns the right `GenerateAgent` implementation. |
| `application/usecase/GenerateReportUseCase.java` | simple | Single-agent application service — calls `ReportGenerator` port, persists via `ReportStore`. |
| `application/usecase/MultiAgentOrchestrator.java` | **complex** | Parallel multi-agent strategy — `CompletableFuture.supplyAsync` for Git and Jira analyzers, then synthesis. |
| `application/chat/ChatOrchestrator.java` | moderate | Asks `SupervisorAgent` for intent decision, mutates session, routes to response/clarify/execute. |
| `application/chat/ChatSession.java` | moderate | Immutable aggregate root with copy-on-write mutators (`withMode`, `appendTurn`, `updateContext`). |
| `application/chat/ConversationTurn.java` | simple | Record: role + content + timestamp. |

### Agent Layer
| File | Complexity | What it does |
|---|---|---|
| `agent/subagents/GitDiffAnalyzerAgent.java` | simple | AiService for Git-diff analysis — outputs structured JSON. |
| `agent/subagents/JiraAnalyzerAgent.java` | simple | AiService for Jira business analysis — outputs structured JSON. |
| `agent/subagents/ReportGeneratorAgent.java` | simple | AiService that merges Git+Jira JSON + date into the final Markdown report. |
| `agent/supervisor/SupervisorAgent.java` | simple | AiService classifying user messages into a `SupervisorDecision`. |
| `agent/supervisor/Intent.java` | simple | Enum: `GENERATE_REPORT`, `FOLLOW_UP`, `CLARIFY`, `MODE_SWITCH`. |
| `agent/supervisor/SupervisorDecision.java` | simple | Record: intent, target agent level, extracted commit/Jira ids, direct/clarification response. |

### Domain Core
| File | Complexity | What it does |
|---|---|---|
| `domain/model/DailyReport.java` | simple | Output record with `fromMarkdown` factory (fan-in: 11). |
| `domain/model/ReportRequest.java` | simple | Input record: commit hash/range, optional Jira key, repo path (fan-in: 9). |
| `domain/model/CodeChange.java` | simple | Git commit value object with abbreviated-id helper. |
| `domain/model/JiraIssueInfo.java` | simple | Jira issue value object with `businessContext()` prompt helper. |
| `domain/port/GitPort.java` | simple | Port: commit detail, range, diff, recent and today commits. |
| `domain/port/JiraPort.java` | simple | Port: `getIssue(issueKey)`. |
| `domain/port/ReportGenerator.java` | simple | Port: `generate(ReportRequest)` — decouples application layer from LangChain4j. |
| `domain/port/ReportStore.java` | simple | Port: persist and similarity-search reports — decouples from vector-store tech. |

### Infrastructure Adapters
| File | Complexity | What it does |
|---|---|---|
| `infrastructure/ai/AgentReportGenerator.java` | simple | `ReportGenerator` adapter delegating to `DailyReportAgent`. |
| `infrastructure/ai/DailyReportAgent.java` | simple | LangChain4j AiService for single-agent ReAct loop. |
| `infrastructure/ai/tools/GitTool.java` | simple | `@Tool` methods wrapping `GitPort` for ReAct tool-calling. |
| `infrastructure/ai/tools/JiraTool.java` | simple | `@Tool` `getJiraIssue(issueKey)` wrapping `JiraPort`. |
| `infrastructure/ai/tools/SessionContextTool.java` | simple | `@Tool` exposing `ChatSession` to the supervisor via a ThreadLocal-style holder. |
| `infrastructure/chat/ChatSessionRepository.java` | moderate | JDBC repo — upsert on `chat_sessions` with `?::jsonb` cast; appends turn rows. |
| `infrastructure/git/JGitAdapter.java` | moderate | `GitPort` impl via JGit. |
| `infrastructure/jira/JiraRestAdapter.java` | moderate | `JiraPort` impl via Java `HttpClient` against Jira REST v2. |
| `infrastructure/rag/PgVectorReportStore.java` | moderate | `ReportStore` impl using LangChain4j `EmbeddingStore<TextSegment>` on pgvector. |

### Spring Configuration
| File | Complexity | What it does |
|---|---|---|
| `infrastructure/config/ChatModelConfig.java` | moderate | Wires the `ChatModel` bean — OpenAI-compatible vs. Ollama based on `LlmProperties`. |
| `infrastructure/config/LangChain4jConfig.java` | moderate | Assembles the single-agent: `ChatModel`, memory, `EmbeddingStoreContentRetriever` (RAG), `GitTool`, `JiraTool` → `DailyReportAgent`. |
| `infrastructure/config/MultiAgentConfig.java` | moderate | Builds the three multi-agent sub-agents via `AiServices.builder()`. |
| `infrastructure/config/PgVectorConfig.java` | moderate | `AllMiniLm-L6-v2` `EmbeddingModel` + pgvector `EmbeddingStore<TextSegment>`. |
| `infrastructure/config/SupervisorConfig.java` | simple | Builds `SupervisorAgent` bean with `SessionContextTool`, `GitTool`, and a 20-message `MessageWindowChatMemory`. |
| `infrastructure/config/ShellConfig.java` | simple | Custom Spring Shell prompt + shared Jackson `ObjectMapper`. |
| `infrastructure/config/properties/LlmProperties.java` | moderate | `@ConfigurationProperties` record binding `llm.*`; `Provider` enum: `OPENAI_COMPATIBLE`, `OLLAMA`. |

### Database Schema
| File | Complexity | What it does |
|---|---|---|
| `db/init/01-init-database.sql` | simple | Enables `pgvector` and `pgcrypto` extensions; runs via `docker-entrypoint-initdb.d`. |
| `db/init/02-create-tables.sql` | moderate | `daily_reports`, `code_changes`, an `updated_at` trigger; documents (commented) `report_embeddings`. |
| `db/init/03-create-chat-tables.sql` | simple | `chat_sessions` (with JSONB `context_json`) and `conversation_turns`. |
| `src/main/resources/db/init-chat-tables.sql` | simple | Jar-bundled mirror of the chat tables schema. |

### Build & Infrastructure
| File | Complexity | What it does |
|---|---|---|
| `run.sh` | **complex** | Unix launcher — banner, Java check, `.env` load, config validation, pgvector readiness probe, optional build, `bootJar` launch. |
| `run.bat` | moderate | Windows equivalent of `run.sh`. |
| `build.gradle` | moderate | Java 21 toolchain + all framework dependencies. |
| `docker-compose.yml` | simple | `pgvector/pgvector:pg17` container; mounts `db/init`. |
| `db/migrate.sh` | moderate | Manual schema bootstrap script. |
| `.env.example` | moderate | LLM provider/key, PGVector, Jira, Git env template. |
| `src/main/resources/application.yaml` | moderate | Main Spring config — datasource, llm, langchain4j, pgvector, jira, git, logging. |
| `src/test/resources/application.yaml` | simple | Test profile — H2 in-memory + stubbed externals. |

### Test Suite
| File | Complexity | What it covers |
|---|---|---|
| `EasyDailyReportApplicationTests.java` | simple | `@SpringBootTest` smoke: context loads with `EmbeddingStore` and `ChatModel` wired. |
| `application/usecase/AgentRouterTest.java` | simple | `SINGLE` and `SAMPLE_MULTIPLE` → correct strategy; `COORDINATOR_AGENT` throws. |
| `application/chat/ChatOrchestratorTest.java` | moderate | Follow-up, clarify, generate, mode-override intent paths. |
| `application/chat/ChatSessionTest.java` | moderate | Immutable mutator semantics: `withMode`, `appendTurn`, `updateContext`, `contextAsString`. |
| `agent/supervisor/SupervisorAgentContractTest.java` | simple | Guards `SupervisorDecision` shape and `Intent` enum membership. |
| `infrastructure/ai/tools/SessionContextToolTest.java` | moderate | `getContext`, `updateContext`, `getUpdatedSession` with/without bound session. |
| `infrastructure/chat/ChatSessionRepositoryTest.java` | moderate | Upsert SQL shape, `?::jsonb` cast verification, `Optional.empty` on no active session. |
| `shell/ChatCommandsTest.java` | moderate | `/mode single|multi|auto`, `/clear`, `isBuiltinCommand`. |

### Documentation
| File | Complexity | Purpose |
|---|---|---|
| `README.md` | **complex** | Primary docs — features, strategy comparison, quickstart, CLI usage, configuration, roadmap. |
| `CLAUDE.md` | moderate | Agent guide — build commands, env setup, DDD layering, strategy pattern. |
| `docs/how-it-works.md` | **complex** | Deep dive on the report pipeline, ReAct loop, RAG flow, end-to-end timing. |
| `docs/sample_multiple_sub_agent.md` | **complex** | Multi-agent design proposal — `langchain4j-agentic` vs. pure AiServices. |
| `docs/single_vs_sample_multiple_agent.md` | moderate | Side-by-side comparison and migration guide. |
| `docs/technical-documentation.md` | **complex** | Primary technical reference — DDD layering, domain model details, RAG. |
| `docs/troubleshooting.md` | moderate | Runbook for three recurring startup/test failures. |
| `db/README.md` | moderate | pgvector setup, manual init steps, schema descriptions, troubleshooting. |

## Complexity Hotspots — Approach with Care

These files concentrate the most logic. Skim the high-level docs above first, then come back to these once you have the mental model.

| File | Why it's complex |
|---|---|
| `shell/ChatCommands.java` | JLine read-loop, eight slash builtins, per-turn persistence, mode-override flow. Touch with tests. |
| `application/usecase/MultiAgentOrchestrator.java` | Concurrency via `CompletableFuture.supplyAsync`, error propagation across futures, JSON contract between three agents. |
| `run.sh` | Several preflight checks (Java version, env, pgvector readiness) before `bootJar` — easy to break startup. |
| `README.md` / `docs/how-it-works.md` / `docs/sample_multiple_sub_agent.md` / `docs/technical-documentation.md` | Long-form architecture docs — change with code, keep diagrams aligned. |

## Quick-Start Checklist (For Your First Day)

1. **Read** `README.md` → `CLAUDE.md` → `docs/how-it-works.md` (≈ 30 min).
2. **Copy** `.env.example` → `.env` and fill in `OPENAI_API_KEY` (plus Jira / Git settings if you'll exercise those).
3. **Start pgvector**: `docker compose up -d`.
4. **Run**: `./run.sh` (use `-b` to force a Gradle rebuild).
5. **Try the CLI**: `report generate-today` for the single-agent path; `chat` to explore the supervisor-routed mode.
6. **Read the guided tour** above with your IDE open — open each file as you go.
7. **Run the tests**: `./gradlew test` — start with `AgentRouterTest`, `ChatSessionTest`, `SupervisorAgentContractTest` for fast feedback on the strategy + chat surface.
8. **Pick a small change** to wire muscle memory — e.g. add a new `/mode` alias in `ChatCommands`, or a new `Intent` value end-to-end.

## Where to Go Next

- **For deeper architecture**: `docs/technical-documentation.md`, `docs/how-it-works.md`
- **For strategy design rationale**: `docs/single_vs_sample_multiple_agent.md`, `docs/sample_multiple_sub_agent.md`
- **For ops issues**: `docs/troubleshooting.md`, `db/README.md`
- **For an interactive view**: run `/understand-anything:understand-dashboard` to explore the 192-node graph visually.
- **For deep dives on a specific file or symbol**: `/understand-anything:understand-explain <path-or-symbol>`.
