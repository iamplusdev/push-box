#!/bin/bash
# PushBox WSL Docker 部署测试脚本
# 用法: ./deploy-wsl.sh [up|down|logs|restart|build]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose-wsl.yml"
ENV_FILE="$SCRIPT_DIR/wsl-test.env"

# 转换为 WSL 路径（处理 Windows 路径）
if [[ "$SCRIPT_DIR" == /mnt/* ]]; then
    # 已经在 WSL 中，使用当前路径
    :
else
    # Windows 路径，需要转换
    SCRIPT_DIR=$(wslpath -u "$SCRIPT_DIR" 2>/dev/null || echo "$SCRIPT_DIR")
    PROJECT_ROOT=$(wslpath -u "$PROJECT_ROOT" 2>/dev/null || echo "$PROJECT_ROOT")
    COMPOSE_FILE="$SCRIPT_DIR/docker-compose-wsl.yml"
    ENV_FILE="$SCRIPT_DIR/wsl-test.env"
fi

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查 Docker 是否可用
check_docker() {
    if ! wsl docker info >/dev/null 2>&1; then
        log_error "Docker 在 WSL 中不可用，请先启动 Docker Desktop 或 Docker Engine"
        exit 1
    fi
    log_info "Docker 环境正常"
}

# 构建镜像
build() {
    log_info "开始构建 PushBox Server 镜像..."
    wsl docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" build pushbox-server
    log_info "镜像构建完成"
}

# 启动服务
up() {
    log_info "启动 PushBox 服务栈（包含 MySQL + RustFS）..."

    # 创建数据目录并设置权限
    mkdir -p "$SCRIPT_DIR/data/mysql"
    mkdir -p "$SCRIPT_DIR/data/rustfs"
    mkdir -p "$SCRIPT_DIR/data/media"

    # RustFS 需要特定用户权限
    if [ -d "$SCRIPT_DIR/data/rustfs" ]; then
        wsl sudo chown -R 10001:10001 "$SCRIPT_DIR/data/rustfs" 2>/dev/null || true
    fi

    # 启动服务（拓扑 B：包含 mysql 和 rustfs）
    wsl docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d

    log_info "等待服务启动..."
    sleep 5

    # 检查服务状态
    wsl docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" ps

    log_info "部署完成！访问地址："
    log_info "  - 健康检查: http://localhost:7404/healthz"
    log_info "  - Caddy 反代: http://localhost/"
    log_info "  - Prometheus: http://localhost:9090 (如启用 monitoring profile)"
    log_info "  - Grafana: http://localhost:3000 (如启用 monitoring profile)"
}

# 停止服务
down() {
    log_info "停止 PushBox 服务栈..."
    wsl docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" down
    log_info "服务已停止"
}

# 查看日志
logs() {
    local service="${1:-pushbox-server}"
    log_info "查看 $service 日志..."
    wsl docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" logs -f "$service"
}

# 重启服务
restart() {
    log_info "重启 PushBox 服务..."
    wsl docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" restart
    log_info "服务已重启"
}

# 清理数据
clean() {
    log_warn "警告：这将删除所有数据目录（MySQL、RustFS、Media）！"
    read -p "确认删除？(yes/no): " confirm
    if [ "$confirm" = "yes" ]; then
        log_info "清理数据..."
        rm -rf "$SCRIPT_DIR/data/mysql"
        rm -rf "$SCRIPT_DIR/data/rustfs"
        rm -rf "$SCRIPT_DIR/data/media"
        rm -rf "$SCRIPT_DIR/data/prometheus"
        rm -rf "$SCRIPT_DIR/data/grafana"
        wsl docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" down -v
        log_info "数据已清理"
    else
        log_info "取消清理"
    fi
}

# 显示帮助
show_help() {
    echo "PushBox WSL Docker 部署测试工具"
    echo ""
    echo "用法: $0 [command]"
    echo ""
    echo "命令:"
    echo "  build     构建 PushBox Server 镜像"
    echo "  up        启动所有服务"
    echo "  down      停止所有服务"
    echo "  logs      查看日志（默认 pushbox-server）"
    echo "  restart   重启所有服务"
    echo "  clean     清理所有数据和容器"
    echo "  help      显示此帮助信息"
    echo ""
    echo "示例:"
    echo "  $0 build    # 先构建镜像"
    echo "  $0 up       # 启动服务"
    echo "  $0 logs     # 查看服务端日志"
    echo "  $0 down     # 停止服务"
}

# 主逻辑
case "${1:-help}" in
    build)
        check_docker
        build
        ;;
    up)
        check_docker
        up
        ;;
    down)
        check_docker
        down
        ;;
    logs)
        check_docker
        logs "$2"
        ;;
    restart)
        check_docker
        restart
        ;;
    clean)
        check_docker
        clean
        ;;
    help|--help|-h)
        show_help
        ;;
    *)
        log_error "未知命令: $1"
        show_help
        exit 1
        ;;
esac
