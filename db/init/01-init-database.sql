-- =============================================================================
-- Easy Daily Report - 数据库初始化脚本
-- 适用于 PostgreSQL 17 + pgvector 扩展
-- =============================================================================

-- 启用 pgvector 扩展（必需）
CREATE EXTENSION IF NOT EXISTS vector;

-- 验证扩展是否安装成功
SELECT * FROM pg_extension WHERE extname = 'vector';
