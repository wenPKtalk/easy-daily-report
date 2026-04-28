# Easy Daily Report

> 🚀 智能工作日报生成器 — 让 AI 帮你写日报

基于 **Git Commit** + **Jira Issue**，通过 **ReAct AI Agent** 自动生成结构化工作日报。

---

## ✨ 核心特性

| 特性 | 说明 |
|------|------|
| 🤖 **AI 驱动** | ReAct 模式 Agent，自主分析代码和业务上下文 |
| 🔗 **Git 集成** | 自动提取 Commit Diff，分析代码变更 |
| 📋 **Jira 关联** | 获取 Issue 描述，理解业务背景 |
| 🧠 **RAG 增强** | 基于 PGVector 检索历史日报，保持风格一致 |
| 📝 **结构化输出** | 标准化 Markdown 日报格式 |
| ⚡ **轻量 CLI** | Spring Shell 交互，零配置上手 |

---

## 🏗️ 架构概览

```
┌─────────────┐     ┌─────────────┐     ┌─────────────────────┐
│   Git Repo  │     │  Jira API   │     │      LLM (AI)       │
└──────┬──────┘     └──────┬──────┘     └──────────┬──────────┘
       │                   │                       │
       ▼                   ▼                       ▼
┌─────────────────────────────────────────────────────────────┐
│                  Infrastructure Layer                        │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │ JGitAdapter │  │JiraRestAdapter│  │ AgentReportGenerator│ │
│  └─────────────┘  └─────────────┘  └──────────┬──────────┘ │
│                                               │ DailyReportAgent
│                              ┌────────────────┼────────────┐
│                              │   Tools         │  RAG        │
│                              │ ┌────────┐   │  ┌────────┐  │
│                              │ │GitTool │   │  │PGVector│  │
│                              │ │JiraTool│   │  │ Store  │  │
│                              │ └────────┘   │  └────────┘  │
└──────────────────────────────┼──────────────┴──────────────┘
                               │
┌──────────────────────────────┼──────────────────────────────┐
│          Application Layer    │                              │
│              GenerateReportUseCase                          │
└──────────────────────────────┼──────────────────────────────┘
                               │
┌──────────────────────────────┼──────────────────────────────┐
│            Shell Layer        │                              │
│      DailyReportCommands      │  ← 你的 CLI 入口            │
└──────────────────────────────┴──────────────────────────────┘
```

---

## 📋 前置要求

- **JDK 21+**
- **Docker** (用于 PGVector)
- **OpenAI API Key** (或兼容 API)

---

## 🚀 快速开始

### 1. 克隆项目

```bash
git clone <your-repo-url>
cd easy-daily-report
```

### 2. 启动 PGVector

```bash
docker compose up -d
```

> 这将启动 PostgreSQL + pgvector 扩展，用于存储日报向量。

### 3. 配置环境变量

```bash
export OPENAI_API_KEY=sk-your-api-key-here
```

或使用 `.env` 文件（开发时）：

```bash
# 复制模板（可选）
cp .env.example .env
# 编辑 .env 填入你的配置
```

### 4. 构建 & 运行

```bash
# 构建
./gradlew build

# 运行
./gradlew bootRun
```

---

## 💻 使用方法

启动后进入 Spring Shell 交互界面：

```
shell:>
```

### 生成日报

```bash
# 基于单个 commit + Jira Issue
shell:> report generate -c abc1234 -j PROJ-123

# 基于 commit 范围
shell:> report generate -r abc1234..def5678 -j PROJ-456

# 指定仓库路径
shell:> report generate -c abc1234 -j PROJ-123 -p /path/to/repo
```

### 参数说明

| 参数 | 短标志 | 长标志 | 说明 |
|------|--------|--------|------|
| Commit Hash | `-c` | `--commit` | Git commit hash |
| Commit Range | `-r` | `--range` | Commit 范围 (from..to) |
| Jira Issue | `-j` | `--jira` | Jira Issue Key |
| Repo Path | `-p` | `--repo` | Git 仓库路径 |

### 查看帮助

```bash
shell:> report help
```

---

## 📄 输出示例

生成的日报格式：

```markdown
## 📅 工作日报 - 2026-04-27

### 📋 任务概述
完成了用户认证模块的 token 刷新机制重构...

### 💻 代码变更要点
- 将 JWT token 刷新逻辑从拦截器移至独立服务
- 引入 Redis 缓存，减少数据库查询 60%
- 新增单元测试覆盖边界场景

### 💼 业务价值
修复了 PROJ-123 中报告的"token 过期后仍可使用"的安全漏洞，
提升了系统安全性和用户体验。

### ⚠️ 潜在风险与优化建议
- 建议灰度发布，监控 Redis 连接池使用率
- 考虑添加刷新失败时的降级策略

### 📌 明日计划
- 完成单元测试补充
- 准备代码评审材料
```

---

## ⚙️ 配置详解

### 环境变量

| 变量 | 必需 | 默认值 | 说明 |
|------|------|--------|------|
| `OPENAI_API_KEY` | ✅ | — | LLM API 密钥 |
| `LLM_MODEL` | ❌ | `gpt-4o` | 模型名称 |
| `JIRA_BASE_URL` | ❌ | — | Jira 地址 |
| `JIRA_USERNAME` | ❌ | — | Jira 用户名 |
| `JIRA_API_TOKEN` | ❌ | — | Jira API Token |
| `GIT_REPO_PATH` | ❌ | `./` | 默认 Git 仓库路径 |
| `PGVECTOR_*` | ❌ | 见配置 | 数据库连接配置 |

### application.yml

完整配置见 `src/main/resources/application.yaml`：

```yaml
langchain4j:
  open-ai:
    chat-model:
      api-key: ${OPENAI_API_KEY}
      model-name: ${LLM_MODEL:gpt-4o}
      temperature: 0.3
```

---

## 🏛️ 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 21 |
| 框架 | Spring Boot | 4.0.6 |
| CLI | Spring Shell | 4.0.1 |
| AI | LangChain4j | 1.0.0-beta4 |
| 向量库 | PGVector | pg17 |
| Embedding | All-MiniLM-L6-v2 | — |
| Git | JGit | 7.2.0 |
| 构建 | Gradle | — |

---

## 🧪 开发指南

```bash
# 构建项目
./gradlew build

# 运行测试
./gradlew test

# 清理并重建
./gradlew clean build

# 开发模式运行
./gradlew bootRun --args='--spring.shell.interactive.enabled=true'
```

---

## 📚 文档

- [技术文档](docs/technical-documentation.md) — 架构设计、DDD 分层、设计模式、API 参考
- [架构文档](_bmad-output/planning-artifacts/architecture.md) — 原始架构设计

---

## 🗺️ 路线图

- [x] MVP: Git + Jira + OpenAI + PGVector + Spring Shell
- [ ] 支持 Ollama 本地模型
- [ ] 多语言日报输出
- [ ] 周报/月报聚合
- [ ] Web 界面（可选）

---

## 🤝 贡献

欢迎 Issue 和 PR！请确保：

1. 代码遵循 DDD 分层架构
2. 遵守 SOLID 原则
3. 新增功能附带测试

---

## 📄 许可

MIT License © 2026 Pengkunwen
