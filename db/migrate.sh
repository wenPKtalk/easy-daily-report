#!/bin/bash
# =============================================================================
# Easy Daily Report - 数据库迁移脚本
# 用于手动执行 SQL 初始化
# =============================================================================

if [ -f ".env" ]; then
    set -o allexport
    source .env
    set +o allexport
    echo ".env 文件加载成功"
else
    echo "当前目录下未找到 .env 文件"
fi

set -e

# 默认配置
DB_HOST="${PGVECTOR_HOST:-localhost}"
DB_PORT="${PGVECTOR_PORT:-5432}"
DB_NAME="${PGVECTOR_DATABASE:-daily_report}"
DB_USER="${PGVECTOR_USER:-postgres}"
DB_PASS="${PGVECTOR_PASSWORD:-postgres}"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "=========================================="
echo "Easy Daily Report - 数据库初始化 - ${DB_HOST}:${DB_PORT}/${DB_NAME} for ${DB_USER} with password ${DB_PASS}"
echo "=========================================="
echo ""

# 检查 psql 是否安装
if ! command -v psql &> /dev/null; then
    echo -e "${RED}错误: 未找到 psql 命令，请先安装 PostgreSQL 客户端${NC}"
    exit 1
fi

# 构建连接字符串
export PGPASSWORD="$DB_PASS"
CONN="-h $DB_HOST -p $DB_PORT -U $DB_USER"

echo "连接信息:"
echo "  主机: $DB_HOST"
echo "  端口: $DB_PORT"
echo "  数据库: $DB_NAME"
echo "  用户: $DB_USER"
echo ""

# 检查数据库是否存在，不存在则创建
echo -e "${YELLOW}检查数据库是否存在...${NC}"
if ! psql $CONN -d postgres -tc "SELECT 1 FROM pg_database WHERE datname='$DB_NAME'" | grep -q 1; then
    echo "数据库 $DB_NAME 不存在，正在创建..."
    psql $CONN -d postgres -c "CREATE DATABASE $DB_NAME;"
    echo -e "${GREEN}数据库创建成功${NC}"
else
    echo -e "${GREEN}数据库 $DB_NAME 已存在${NC}"
fi

echo ""

# 执行初始化脚本
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INIT_DIR="$SCRIPT_DIR/init"

echo -e "${YELLOW}执行初始化脚本...${NC}"
for file in "$INIT_DIR"/*.sql; do
    if [ -f "$file" ]; then
        echo "  → 执行: $(basename "$file")"
        psql $CONN -d "$DB_NAME" -f "$file" > /dev/null 2>&1
        if [ $? -eq 0 ]; then
            echo -e "    ${GREEN}✓ 成功${NC}"
        else
            echo -e "    ${RED}✗ 失败${NC}"
            exit 1
        fi
    fi
done

echo ""
echo -e "${GREEN}==========================================${NC}"
echo -e "${GREEN}数据库初始化完成！${NC}"
echo -e "${GREEN}==========================================${NC}"
