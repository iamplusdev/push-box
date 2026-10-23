#!/bin/bash
# PushBox WSL Docker 部署测试脚本
# 用法: ./deploy-wsl.sh [build|up|setup|bucket|down|logs|restart|clean|help]
#
# 服务端不读环境变量：配置从安装页来。所以这套测试栈的"填配置"这一步也由脚本代劳——
# `up` 起栈（容器会停在安装页上），`setup` 把 wsl-test.env 里的测试值 POST 给 /api/v1/setup，
# 走的是和真人在浏览器里点「保存并启动」完全相同的一条路。

set -e

# Git Bash 会在把参数交给 wsl.exe 之前，把 /mnt/d/... 这类"看着像 POSIX 路径"的值改写成
# D:/Apps/DE/Git/mnt/d/...，于是 compose 只报一句 "couldn't find env file"。关掉这个转换。
# 在 WSL 里直接跑这个脚本时它只是个没人读的环境变量。
export MSYS_NO_PATHCONV=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 同一条路径要两种写法：`wsl docker` 只认 /mnt/d/...，而脚本自己在 Windows 侧跑的
# sed / mkdir 只认 Git Bash 的 /d/...。所以两套都留着，别把它们混成一个变量。
# 转换要先 cygpath -m 拿到 D:/... 再交给 wslpath：上面关掉了 MSYS 的参数改写，
# 直接把 /d/... 递过去会被 wslpath 当成 WSL 自己的路径，出来 /mnt/d/d/... 这种鬼东西。
if [[ "$SCRIPT_DIR" == /mnt/* ]]; then
    WSL_SCRIPT_DIR="$SCRIPT_DIR"
else
    WSL_SCRIPT_DIR=$(wsl wslpath -u "$(cygpath -m "$SCRIPT_DIR")") || {
        echo "[ERROR] 无法把 $SCRIPT_DIR 转成 WSL 路径（wsl wslpath 调用失败）" >&2
        exit 1
    }
fi

ENV_FILE="$SCRIPT_DIR/wsl-test.env"
WSL_COMPOSE_FILE="$WSL_SCRIPT_DIR/docker-compose-wsl.yml"
WSL_ENV_FILE="$WSL_SCRIPT_DIR/wsl-test.env"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

dc() { wsl docker compose -f "$WSL_COMPOSE_FILE" --env-file "$WSL_ENV_FILE" "$@"; }

check_docker() {
    if ! wsl docker info >/dev/null 2>&1; then
        log_error "Docker 在 WSL 中不可用，请先启动 Docker Desktop 或 Docker Engine"
        exit 1
    fi
    if [ ! -f "$ENV_FILE" ]; then
        log_error "缺 $ENV_FILE"
        log_error "  cp deploy/wsl-test.env.example deploy/wsl-test.env  然后按本机情况填值"
        exit 1
    fi
}

# 读 wsl-test.env 里的一个键（compose 的 --env-file 只给 compose 用，脚本自己要另读一遍）
envval() {
    sed -n "s/^$1=//p" "$ENV_FILE" | tail -n 1
}

build() {
    log_info "开始构建 PushBox Server 镜像..."
    dc build pushbox-server
    log_info "镜像构建完成"
}

up() {
    log_info "启动 PushBox 服务栈（MySQL + RustFS + Server + Caddy）..."
    mkdir -p "$SCRIPT_DIR/data/prometheus" "$SCRIPT_DIR/data/grafana"
    dc up -d
    log_info "等待服务启动..."
    sleep 5
    dc ps
    echo
    local port; port="$(envval PB_SERVER_PORT)"; port="${port:-7404}"
    log_warn "服务端现在停在安装页上（这是正常的：还没有配置文件）"
    log_info "  浏览器打开  http://localhost:$port/  自己填，"
    log_info "  或者跑      $0 setup                 用 wsl-test.env 里的值一次填完"
}

# 代替真人点「保存并启动」：把 wsl-test.env 拼成安装页那一次 POST 的 JSON。
# 转义只做反斜杠与双引号两件事——这是够的：值来自 env 文件的一行，换行进不来；
# 带制表符的测试口令不值得为它拉一个 python3 依赖（这台机器上 python3 并不在 PATH 里）。
json_escape() {
    local s=$1
    s=${s//\\/\\\\}
    s=${s//\"/\\\"}
    printf '%s' "$s"
}

# 逗号分隔的一串 → JSON 数组（去空格、丢空项）。internalCidrs 与 media.allowHosts 共用。
# IFS 用 local 关在函数里，别漏出去影响调用方的分词。
json_array() {
    local IFS=','
    local out="" c
    for c in $1; do
        c="${c// /}"
        [ -n "$c" ] && out="$out\"$c\","
    done
    printf '[%s]' "${out%,}"
}

setup() {
    local port; port="$(envval PB_SERVER_PORT)"; port="${port:-7404}"
    local db_pass s3_ak s3_sk s3_bucket passphrase admin_pass cidrs
    db_pass="$(envval DB_PASSWORD)"
    s3_ak="$(envval S3_ACCESS_KEY)"
    s3_sk="$(envval S3_SECRET_KEY)"
    s3_bucket="$(envval S3_BUCKET)"; s3_bucket="${s3_bucket:-pushbox}"
    passphrase="$(envval STORAGE_PASSPHRASE)"
    admin_pass="$(envval ADMIN_PASSWORD)"
    # 逗号分隔 → JSON 数组。空值就是空数组（= 只放行容器环回，最严的口径）。
    cidrs=$(json_array "$(envval INTERNAL_CIDRS)")
    # 图片抓取的 SSRF 白名单，对应安装页上「图片抓取放行主机」那一栏。这台机器上的容器
    # 没有公网出口，图床只能指向宿主机（host.docker.internal）——那是私网段，
    # 不点名的话每条图片都是 ssrf_blocked，而服务端日志之外没有任何提示。
    allow_hosts=$(json_array "$(envval MEDIA_ALLOW_HOSTS)")

    local encrypted=false
    [ -n "$passphrase" ] && encrypted=true

    local payload
    payload=$(printf '{"port":7404,"database":{"host":"mysql","port":3306,"name":"pushbox","username":"pushbox","password":"%s"},"storage":{"s3":true,"localPath":"data/media","endpoint":"http://rustfs:9000","region":"us-east-1","bucket":"%s","accessKey":"%s","secretKey":"%s","pathStyle":true,"encrypted":%s,"passphrase":"%s","masterKey":""},"admin":{"password":"%s","jwtKey":""},"security":{"internalCidrs":%s},"media":{"allowHosts":%s}}' \
        "$(json_escape "$db_pass")" "$(json_escape "$s3_bucket")" \
        "$(json_escape "$s3_ak")" "$(json_escape "$s3_sk")" \
        "$encrypted" "$(json_escape "$passphrase")" \
        "$(json_escape "$admin_pass")" "$cidrs" "$allow_hosts")

    log_info "把配置提交给安装页 http://localhost:$port/api/v1/setup ..."
    local resp
    resp=$(wsl curl -s -X POST "http://localhost:$port/api/v1/setup" \
             -H 'Content-Type: application/json' --data "$payload") || {
        log_error "连不上安装页。先确认 $0 up 已经跑过、容器还在安装页上（$0 logs）"; exit 1; }
    # 回话里带口令吗？不带——install() 只回 ok/message/error，message 是配置文件的绝对路径。
    echo "$resp"
    case "$resp" in
        *'"ok":true'*) : ;;
        *) log_error "安装页拒绝了这份配置，按上面那句改 wsl-test.env 再来一次"; exit 1 ;;
    esac

    log_info "配置已落盘，等 Spring 起来（首次要跑 Flyway 建表，慢个几十秒是正常的）..."
    for i in $(seq 1 60); do
        if wsl curl -sf "http://localhost:$port/healthz" >/dev/null 2>&1; then
            log_info "✅ 服务端就绪：http://localhost:$port/healthz"
            return 0
        fi
        sleep 3
    done
    log_warn "180 秒内 /healthz 还没通。/healthz 只放行运维网段，从宿主打过去本来就可能 404；"
    log_warn "看日志确认：$0 logs   （出现 Started PushboxServerApplication 即就绪）"
}

# 桶不会自动出现：rustfs 镜像里没有 S3 客户端，minio/mc 已从 Docker Hub 下架，
# 所以借 amazon/aws-cli + rustfs 自己的网络命名空间建（compose 没给 rustfs 发布端口）。
bucket() {
    local ak sk; ak="$(envval S3_ACCESS_KEY)"; sk="$(envval S3_SECRET_KEY)"
    local b; b="$(envval S3_BUCKET)"; b="${b:-pushbox}"
    local net; net="container:$(dc ps -q rustfs)"
    [ -n "$net" ] || { log_error "rustfs 容器没在跑，先 $0 up"; exit 1; }
    log_info "在 rustfs 上建桶 $b ..."
    wsl docker run --rm --network "$net" \
        -e AWS_ACCESS_KEY_ID="$ak" -e AWS_SECRET_ACCESS_KEY="$sk" \
        -e AWS_DEFAULT_REGION=us-east-1 -e AWS_EC2_METADATA_DISABLED=true \
        amazon/aws-cli --endpoint-url http://127.0.0.1:9000 s3api create-bucket --bucket "$b" \
        || log_warn "create-bucket 非零退出：桶可能已经在了（这条命令不幂等），下面 head-bucket 说了算"
    wsl docker run --rm --network "$net" \
        -e AWS_ACCESS_KEY_ID="$ak" -e AWS_SECRET_ACCESS_KEY="$sk" \
        -e AWS_DEFAULT_REGION=us-east-1 -e AWS_EC2_METADATA_DISABLED=true \
        amazon/aws-cli --endpoint-url http://127.0.0.1:9000 s3api head-bucket --bucket "$b"
    log_info "✅ 桶 $b 已就绪"
}

down() {
    log_info "停止 PushBox 服务栈..."
    dc down
    log_info "服务已停止（卷留着；要连数据一起清就用 clean）"
}

logs() {
    local service="${1:-pushbox-server}"
    log_info "查看 $service 日志..."
    dc logs -f "$service"
}

restart() {
    log_info "重启 PushBox 服务..."
    dc restart
    log_info "服务已重启"
}

clean() {
    log_warn "警告：这会删掉全部卷（MySQL、RustFS、PushBox 的配置与图片）！"
    # 从 /dev/tty 读，不从 stdin：脚本被管道或 CI 调用时 stdin 不是终端，read 撞上 EOF
    # 返回非 0，在 set -e 下整个脚本当场退出——用户只看到那句警告，删没删都无从得知。
    local confirm=""
    if { exec 3</dev/tty; } 2>/dev/null; then
        read -r -p "确认删除？(yes/no): " confirm <&3 || confirm=""
        exec 3<&-
    else
        log_warn "没有可交互的终端，按「取消」处理"
    fi
    if [ "$confirm" = "yes" ]; then
        log_info "清理数据..."
        dc down -v
        rm -rf "$SCRIPT_DIR/data/prometheus" "$SCRIPT_DIR/data/grafana"
        log_info "数据已清理（下次 up 之后要重新跑一遍 setup）"
    else
        log_info "取消清理"
    fi
}

show_help() {
    cat <<EOF
PushBox WSL Docker 部署测试工具

用法: $0 [command]

命令:
  build     构建 PushBox Server 镜像
  up        启动所有服务（服务端会停在安装页上）
  setup     把 wsl-test.env 里的值提交给安装页，代替真人点「保存并启动」
  bucket    在 rustfs 上建桶（首次部署补一次；桶不会自动出现）
  down      停止所有服务（卷留着）
  logs      查看日志（默认 pushbox-server）
  restart   重启所有服务
  clean     连卷一起清掉（下次要重新 setup）
  help      显示此帮助信息

一次完整的首跑:
  $0 build && $0 up && $0 bucket && $0 setup
EOF
}

case "${1:-help}" in
    build)   check_docker; build ;;
    up)      check_docker; up ;;
    setup)   check_docker; setup ;;
    bucket)  check_docker; bucket ;;
    down)    check_docker; down ;;
    logs)    check_docker; logs "$2" ;;
    restart) check_docker; restart ;;
    clean)   check_docker; clean ;;
    help|--help|-h) show_help ;;
    *) log_error "未知命令: $1"; show_help; exit 1 ;;
esac
