# PushBox WSL Docker 部署测试脚本 (PowerShell)
# 用法: .\deploy-wsl.ps1 [build|up|down|logs|restart|clean]

param(
    [Parameter(Position=0)]
    [ValidateSet('build','up','down','logs','restart','clean','help')]
    [string]$Command = 'help',

    [Parameter(Position=1)]
    [string]$Service = 'pushbox-server'
)

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ComposeFile = Join-Path $ScriptDir "docker-compose-wsl.yml"
$EnvFile = Join-Path $ScriptDir "wsl-test.env"

function Write-Info { param($msg) Write-Host "[INFO] $msg" -ForegroundColor Green }
function Write-Warn { param($msg) Write-Host "[WARN] $msg" -ForegroundColor Yellow }
function Write-Error { param($msg) Write-Host "[ERROR] $msg" -ForegroundColor Red; exit 1 }

function Check-Docker {
    try {
        wsl docker info | Out-Null
        Write-Info "Docker 环境正常"
    } catch {
        Write-Error "Docker 在 WSL 中不可用，请先启动 Docker Desktop 或 Docker Engine"
    }
}

function Build-Image {
    Write-Info "开始构建 PushBox Server 镜像..."
    wsl docker compose -f "$ComposeFile" --env-file "$EnvFile" build pushbox-server
    Write-Info "镜像构建完成"
}

function Start-Services {
    Write-Info "启动 PushBox 服务栈（包含 MySQL + RustFS）..."

    # 创建数据目录
    New-Item -ItemType Directory -Force -Path (Join-Path $ScriptDir "data/mysql") | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $ScriptDir "data/rustfs") | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $ScriptDir "data/media") | Out-Null

    # 启动服务
    wsl docker compose -f "$ComposeFile" --env-file "$EnvFile" up -d

    Write-Info "等待服务启动..."
    Start-Sleep -Seconds 5

    # 检查服务状态
    wsl docker compose -f "$ComposeFile" --env-file "$EnvFile" ps

    Write-Info ""
    Write-Info "部署完成！访问地址："
    Write-Info "  - 健康检查: http://localhost:7404/healthz"
    Write-Info "  - Caddy 反代: http://localhost/"
    Write-Info "  - Prometheus: http://localhost:9090 (如启用 monitoring profile)"
    Write-Info "  - Grafana: http://localhost:3000 (如启用 monitoring profile)"
}

function Stop-Services {
    Write-Info "停止 PushBox 服务栈..."
    wsl docker compose -f "$ComposeFile" --env-file "$EnvFile" down
    Write-Info "服务已停止"
}

function Show-Logs {
    Write-Info "查看 $Service 日志..."
    wsl docker compose -f "$ComposeFile" --env-file "$EnvFile" logs -f $Service
}

function Restart-Services {
    Write-Info "重启 PushBox 服务..."
    wsl docker compose -f "$ComposeFile" --env-file "$EnvFile" restart
    Write-Info "服务已重启"
}

function Clean-Data {
    Write-Warn "警告：这将删除所有数据目录（MySQL、RustFS、Media）！"
    $confirm = Read-Host "确认删除？(yes/no)"
    if ($confirm -eq "yes") {
        Write-Info "清理数据..."
        Remove-Item -Recurse -Force (Join-Path $ScriptDir "data/mysql") -ErrorAction SilentlyContinue
        Remove-Item -Recurse -Force (Join-Path $ScriptDir "data/rustfs") -ErrorAction SilentlyContinue
        Remove-Item -Recurse -Force (Join-Path $ScriptDir "data/media") -ErrorAction SilentlyContinue
        Remove-Item -Recurse -Force (Join-Path $ScriptDir "data/prometheus") -ErrorAction SilentlyContinue
        Remove-Item -Recurse -Force (Join-Path $ScriptDir "data/grafana") -ErrorAction SilentlyContinue
        wsl docker compose -f "$ComposeFile" --env-file "$EnvFile" down -v
        Write-Info "数据已清理"
    } else {
        Write-Info "取消清理"
    }
}

function Show-Help {
    Write-Host "PushBox WSL Docker 部署测试工具"
    Write-Host ""
    Write-Host "用法: .\deploy-wsl.ps1 [command]"
    Write-Host ""
    Write-Host "命令:"
    Write-Host "  build     构建 PushBox Server 镜像"
    Write-Host "  up        启动所有服务"
    Write-Host "  down      停止所有服务"
    Write-Host "  logs      查看日志（默认 pushbox-server）"
    Write-Host "  restart   重启所有服务"
    Write-Host "  clean     清理所有数据和容器"
    Write-Host "  help      显示此帮助信息"
    Write-Host ""
    Write-Host "示例:"
    Write-Host "  .\deploy-wsl.ps1 build    # 先构建镜像"
    Write-Host "  .\deploy-wsl.ps1 up       # 启动服务"
    Write-Host "  .\deploy-wsl.ps1 logs     # 查看服务端日志"
    Write-Host "  .\deploy-wsl.ps1 down     # 停止服务"
}

# 主逻辑
Check-Docker

switch ($Command) {
    'build' { Build-Image }
    'up' { Start-Services }
    'down' { Stop-Services }
    'logs' { Show-Logs }
    'restart' { Restart-Services }
    'clean' { Clean-Data }
    'help' { Show-Help }
}
