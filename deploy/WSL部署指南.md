# PushBox WSL Docker 部署测试指南

本文档说明如何在 Windows Subsystem for Linux (WSL) 的 Docker 环境中部署和测试 PushBox。

## 前置条件

1. **WSL2 已安装**
   ```powershell
   wsl --install
   ```

2. **Docker Desktop 或 Docker Engine 已安装并运行在 WSL 中**
   - Docker Desktop: 启用 "Use the WSL 2 based engine"
   - 或在 WSL 内直接安装 Docker Engine

3. **Git 已安装**（用于克隆代码）

## 快速开始

### 1. 构建镜像

```bash
cd deploy
./deploy-wsl.sh build
```

这将使用项目根目录的 `deploy/Dockerfile` 构建 PushBox Server 镜像。

### 2. 启动服务

```bash
./deploy-wsl.sh up
```

这会启动以下服务：
- **MySQL 8.4**: 数据库
- **RustFS**: 对象存储（S3 兼容）
- **PushBox Server**: 后端服务
- **Caddy**: HTTP 反代

### 3. 验证部署

#### 健康检查
```bash
curl http://localhost:7404/healthz
```

预期响应：
```json
{
  "ok": true,
  "db": "up",
  "storage": "up",
  "uptime_ms": 12345
}
```

#### 管理台登录
访问 `http://localhost/api/v1/admin/login`，使用以下凭据：
- 口令: `admin_secret_test_12345`

#### 查看日志
```bash
# 查看服务端日志
./deploy-wsl.sh logs pushbox-server

# 查看所有服务状态
wsl docker compose -f docker-compose-wsl.yml --env-file wsl-test.env ps
```

### 4. 停止服务

```bash
./deploy-wsl.sh down
```

### 5. 清理所有数据

```bash
./deploy-wsl.sh clean
```

⚠️ **警告**: 这将删除所有数据库、对象存储和媒体文件！

## 配置说明

配置文件位于 `deploy/wsl-test.env`，主要配置项：

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `PB_DB_PASSWORD` | 数据库密码 | `test_password_123` |
| `PB_ADMIN_SECRET` | 管理台登录口令 | `admin_secret_test_12345` |
| `PB_ADMIN_JWT_SECRET` | JWT 签名密钥 | `jwt_secret_must_be_different_xyz789abc` |
| `PB_SERVER_PORT` | 服务端端口 | `7404` |
| `PB_HTTP_PORT` | Caddy HTTP 端口 | `80` |

## 监控栈（可选）

如需启用 Prometheus + Grafana 监控：

```bash
wsl docker compose -f docker-compose-wsl.yml --env-file wsl-test.env --profile monitoring up -d
```

访问地址：
- **Prometheus**: http://localhost:9090
- **Grafana**: http://localhost:3000
  - 用户名: `admin`
  - 密码: `grafana_test_123`

## 故障排查

### 1. 容器启动失败

```bash
# 查看特定服务的日志
wsl docker compose -f docker-compose-wsl.yml --env-file wsl-test.env logs <service-name>

# 常见原因：
# - MySQL 未就绪：等待 healthcheck 通过
# - RustFS 权限问题：确保 data/rustfs 目录可写
```

### 2. 端口冲突

如果 7404 或 80 端口被占用：

```bash
# 修改 wsl-test.env 中的端口
PB_SERVER_PORT=8080
PB_HTTP_PORT=8080

# 重启服务
./deploy-wsl.sh restart
```

### 3. 数据库连接失败

```bash
# 检查 MySQL 是否正常运行
wsl docker exec -it pushbox-mysql-1 mysql -u pushbox -p test_password_123 pushbox -e "SHOW TABLES;"
```

### 4. 查看容器资源使用

```bash
wsl docker stats pushbox-pushbox-server-1
```

## 网络拓扑

```
[客户端] → [Caddy:80] → [PushBox Server:7404] → [MySQL:3306]
                                               → [RustFS:7101]
```

所有服务都在 `pushbox-net` Docker 网络中，通过服务名互相访问。

## 数据持久化

数据存储在 `deploy/data/` 目录下：
- `data/mysql/`: MySQL 数据
- `data/rustfs/`: 对象存储数据
- `data/media/`: 本地媒体文件（如使用 local 存储模式）
- `data/prometheus/`: Prometheus 指标
- `data/grafana/`: Grafana 配置和数据

## 生产环境注意事项

⚠️ **此配置仅用于测试**，生产部署请：

1. 使用强随机密码生成工具
2. 启用 TLS（Caddy 自动证书）
3. 配置防火墙规则
4. 设置定期备份
5. 启用对象加密（SSE-C）
6. 调整资源限制

参考 `deploy/docker-compose.yml` 的生产配置模板。
