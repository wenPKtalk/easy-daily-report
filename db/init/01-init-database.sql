-- =============================================================================
-- Easy Daily Report - 一级数据库初始化脚本
-- 适用于 PostgreSQL 17 + pgvector 扩展
--
-- 本脚本由 docker-entrypoint-initdb.d 在容器首次创建时自动执行，
-- 也可通过 db/migrate.sh 手动执行。仅负责扩展和会话级配置，
-- 表结构由后续脚本（02-, 03-）创建。
-- =============================================================================

-- 启用 pgvector 扩展：用于日报向量检索（RAG）
CREATE EXTENSION IF NOT EXISTS vector;

-- 启用 pgcrypto：daily_reports / code_changes 表使用 gen_random_uuid() 作为主键默认值
CREATE EXTENSION IF NOT EXISTS pgcrypto;
