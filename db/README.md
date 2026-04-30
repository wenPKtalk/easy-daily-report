# 数据库初始化

## 概述

Easy Daily Report 使用 **PostgreSQL + pgvector 扩展** 作为向量数据库，存储日报的向量化表示以支持 RAG（检索增强生成）。

## 快速启动（推荐）

使用 Docker Compose 一键启动：

```bash
docker compose up -d pgvector
```

这将自动：
1. 创建 PostgreSQL 实例
2. 启用 pgvector 扩展
3. 创建 `daily_report` 数据库

## 手动初始化

如需手动初始化数据库（例如在现有 PostgreSQL 实例上）：

### 1. 确保 pgvector 扩展可用

```sql
-- 连接到目标数据库后执行
CREATE EXTENSION IF NOT EXISTS vector;
```

### 2. 执行初始化脚本

```bash
# 方法 1: 使用迁移脚本
./db/migrate.sh

# 方法 2: 手动执行 SQL 文件
psql -h localhost -U postgres -d daily_report -f db/init/01-init-database.sql
psql -h localhost -U postgres -d daily_report -f db/init/02-create-tables.sql
```

### 3. 验证安装

```sql
-- 检查 pgvector 扩展
SELECT * FROM pg_extension WHERE extname = 'vector';

-- 检查表是否创建（如果使用 LangChain4j 自动创建，首次运行后会自动生成）
SELECT table_name FROM information_schema.tables 
WHERE table_schema = 'public';
```

## 表结构说明

### LangChain4j 自动管理

| 表名 | 说明 | 管理方式 |
|------|------|---------|
| `report_embeddings` | 日报向量存储 | LangChain4j PgVectorEmbeddingStore 自动创建 |

该表存储：
- `content`: 日报 Markdown 内容
- `embedding`: 384 维向量（All-MiniLM-L6-v2）
- `metadata`: JSON 元数据（日期、类型等）

### 可选业务表

| 表名 | 说明 | 用途 |
|------|------|------|
| `daily_reports` | 日报元数据 | 补充业务字段，非向量查询 |
| `code_changes` | 代码变更缓存 | 可选，缓存 Git 数据减少 API 调用 |

## 配置

数据库连接配置通过环境变量或 `application.yaml`：

```yaml
pgvector:
  host: localhost
  port: 5432
  database: daily_report
  user: postgres
  password: postgres
  table: report_embeddings
  dimension: 384
```

或环境变量：

```bash
export PGVECTOR_HOST=localhost
export PGVECTOR_PORT=5432
export PGVECTOR_DATABASE=daily_report
export PGVECTOR_USER=postgres
export PGVECTOR_PASSWORD=postgres
```

## 故障排查

### pgvector 扩展未找到

```bash
# 在 PostgreSQL 容器中安装扩展
docker exec -it daily-report-pgvector psql -U postgres -c "CREATE EXTENSION vector;"
```

### 连接失败

检查：
1. Docker 容器是否运行：`docker ps | grep pgvector`
2. 端口是否映射：`docker port daily-report-pgvector`
3. 防火墙设置

### 向量维度不匹配

确保 `pgvector.dimension` 配置与 Embedding 模型一致：
- `All-MiniLM-L6-v2`: 384 维（默认）
- 其他模型需相应调整
