#!/bin/bash

# Easy Daily Report - 快速运行脚本
# 自动加载 .env 文件并启动应用

set -e

# 颜色定义
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_NAME="Easy Daily Report"

# 打印带颜色的信息
info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 打印标题
show_banner() {
    echo ""
    echo -e "${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║              Easy Daily Report - 智能日报生成器            ║${NC}"
    echo -e "${BLUE}╚════════════════════════════════════════════════════════════╝${NC}"
    echo ""
}

# 检查 Java 版本
check_java() {
    if ! command -v java &> /dev/null; then
        error "Java 未安装，请先安装 JDK 21+"
        exit 1
    fi

    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2)
    info "检测到 Java 版本: $JAVA_VERSION"
}

# 加载 .env 文件
load_env() {
    local env_file="$SCRIPT_DIR/.env"

    if [ -f "$env_file" ]; then
        info "加载环境变量: .env"
        # 导出 .env 中的变量（跳过注释和空行）
        export $(grep -v '^#' "$env_file" | grep -v '^$' | xargs)
        success "环境变量加载完成"
    else
        warn ".env 文件不存在，使用默认配置"
        warn "提示: 复制 .env.example 为 .env 并配置你的 API Key"
    fi
}

# 检查关键配置
check_config() {
    if [ -z "$OPENAI_API_KEY" ] || [ "$OPENAI_API_KEY" = "demo" ] || [ "$OPENAI_API_KEY" = "your-api-key-here" ]; then
        warn "OPENAI_API_KEY 未配置或使用的是默认值"
        warn "请在 .env 文件中设置有效的 API Key"
        echo ""
        echo -e "${YELLOW}配置示例:${NC}"
        echo "  LLM_PROVIDER=openai-compatible"
        echo "  LLM_BASE_URL=https://open.bigmodel.cn/api/paas/v4/"
        echo "  OPENAI_API_KEY=your-actual-api-key"
        echo "  LLM_MODEL=glm-4-flash"
        echo ""

        # 询问是否继续
        read -p "是否继续启动? (y/N): " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            info "已取消启动"
            exit 0
        fi
    fi
}

# 检查 PGVector 可选
check_pgvector() {
    local pg_host="${PGVECTOR_HOST:-localhost}"
    local pg_port="${PGVECTOR_PORT:-5432}"

    info "检查 PGVector 连接 ($pg_host:$pg_port)..."

    if nc -z "$pg_host" "$pg_port" 2>/dev/null; then
        success "PGVector 已就绪"
    else
        warn "PGVector 未运行 ($pg_host:$pg_port)"
        warn "RAG 历史检索功能将不可用"
        warn "启动 PGVector: docker compose up -d"
    fi
}

# 构建项目（如果需要）
build_if_needed() {
    local jar_file="$SCRIPT_DIR/build/libs/easy-daily-report-0.0.1-SNAPSHOT.jar"
    if [ ! -f "$jar_file" ]; then
        info "首次运行，需要构建项目..."
        ./gradlew bootJar -x test --quiet
        success "构建完成: $jar_file"
    fi
}

# 运行应用
run_app() {
    echo ""
    info "启动 $PROJECT_NAME..."
    info "按 Ctrl+C 停止应用"
    echo ""

    # 直接运行 JAR（保持前台交互，Spring Shell 才能正常工作）
    java -jar "$SCRIPT_DIR/build/libs/easy-daily-report-0.0.1-SNAPSHOT.jar"
}

# 显示帮助
show_help() {
    show_banner
    echo "用法: ./run.sh [选项]"
    echo ""
    echo "选项:"
    echo "  -h, --help      显示帮助信息"
    echo "  -b, --build     强制重新构建项目"
    echo "  --skip-checks   跳过配置检查直接启动"
    echo ""
    echo "示例:"
    echo "  ./run.sh                    # 正常启动"
    echo "  ./run.sh -b                 # 重新构建后启动"
    echo "  ./run.sh --skip-checks      # 跳过检查快速启动"
    echo ""
}

# 主函数
main() {
    # 显示标题
    show_banner

    # 解析参数
    local skip_checks=false
    local force_build=false

    while [[ $# -gt 0 ]]; do
        case $1 in
            -h|--help)
                show_help
                exit 0
                ;;
            -b|--build)
                force_build=true
                shift
                ;;
            --skip-checks)
                skip_checks=true
                shift
                ;;
            *)
                shift
                ;;
        esac
    done

    # 切换到脚本所在目录
    cd "$SCRIPT_DIR"

    # 检查 Java
    check_java

    # 加载 .env
    load_env

    # 检查配置（除非跳过）
    if [ "$skip_checks" = false ]; then
        check_config
        check_pgvector
    fi

    # 构建项目
    if [ "$force_build" = true ]; then
        info "强制重新构建..."
        ./gradlew clean bootJar -x test --quiet
        success "构建完成"
    else
        build_if_needed
    fi

    # 运行应用
    run_app
}

# 执行主函数
main "$@"
