-- =============================================================================
-- Easy Daily Report - 表结构初始化
-- 注意：LangChain4j PgVectorEmbeddingStore 默认会自动创建表
-- 此脚本仅在需要手动控制表结构时使用
-- =============================================================================

-- 日报向量存储表（由 LangChain4j 管理，通常无需手动创建）
-- 如需自定义，可取消注释以下语句：

/*
CREATE TABLE IF NOT EXISTS report_embeddings (
    embedding_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content TEXT NOT NULL,
    embedding VECTOR(384),  -- 匹配 All-MiniLM-L6-v2 维度
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 创建向量索引（IVFFlat 适合中等规模数据，约 10万条以下）
CREATE INDEX ON report_embeddings
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

-- 为 metadata 查询创建 GIN 索引
CREATE INDEX idx_report_embeddings_metadata ON report_embeddings USING GIN (metadata);

-- 为时间范围查询创建索引
CREATE INDEX idx_report_embeddings_created_at ON report_embeddings (created_at);
*/

-- =============================================================================
-- 可选：业务数据表（如需扩展非向量业务功能）
-- =============================================================================

-- 日报元数据表（可选，用于补充业务字段）
CREATE TABLE IF NOT EXISTS daily_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_date DATE NOT NULL,
    task_overview TEXT,
    business_value TEXT,
    tomorrow_plan TEXT,
    raw_markdown TEXT NOT NULL,
    commit_hash VARCHAR(40),
    jira_issue_key VARCHAR(20),
    author VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 索引
CREATE INDEX idx_daily_reports_date ON daily_reports (report_date);
CREATE INDEX idx_daily_reports_author ON daily_reports (author);
CREATE INDEX idx_daily_reports_jira ON daily_reports (jira_issue_key);
CREATE INDEX idx_daily_reports_commit ON daily_reports (commit_hash);

-- 触发器：自动更新 updated_at
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_daily_reports_updated_at
    BEFORE UPDATE ON daily_reports
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- 代码变更记录表（可选，用于缓存 Git 数据）
CREATE TABLE IF NOT EXISTS code_changes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    commit_id VARCHAR(40) UNIQUE NOT NULL,
    author VARCHAR(100) NOT NULL,
    message TEXT NOT NULL,
    diff TEXT,
    commit_time TIMESTAMP WITH TIME ZONE NOT NULL,
    repository_path TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_code_changes_commit_id ON code_changes (commit_id);
CREATE INDEX idx_code_changes_author ON code_changes (author);
CREATE INDEX idx_code_changes_time ON code_changes (commit_time);
