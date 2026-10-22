# PushBox WSL Docker 快速部署指南

## 前置检查

1. **确认 WSL 和 Docker 已安装**
   ```powershell
   wsl --version
   wsl docker --version
   ```

2. **检查 Docker 是否运行**
   ```powershell
   wsl docker info
   ```

## 部署步骤

### 方法一：使用 PowerShell 脚本（推荐）

```powershell
cd D:\Projects\Android\PushBox\deploy
.\deploy-wsl.ps1 build    # 构建镜像
.\deploy-wsl.ps1 up       # 启动服务
```

### 方法二：手动在 WSL 中执行

```bash
# 进入项目目录
cd /mnt/d/Projects/Android/PushBox/deploy

# 如果 Docker 有代理问题，先清除代理
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY

# 构建镜像
docker compose -f docker-compose-wsl.yml --env-file wsl-test.env build pushbox-server

# 创建数据目录
mkdir -p data/mysql data/rustfs data/media

# 启动服务
docker compose -f docker-compose-wsl.yml --env-file wsl-test.env up -d

# 等待服务就绪（约 30-60 秒）
sleep 60

# 检查服务状态
docker compose -f docker-compose-wsl.yml --env-file wsl-test.env ps
```

## 验证部署

### ✅ 实际部署结果（2026-10-19 测试通过）

```bash
# 服务状态
NAME                            STATUS
pushbox-test-mysql-1            Up (healthy)       ✓
pushbox-test-pushbox-server-1   Up (healthy)       ✓
pushbox-test-rustfs-1           Up                 ✓
pushbox-test-caddy-1            Restarting         ⚠️ (配置待优化)
```

### 1. 健康检查

```bash
curl http://localhost:7404/healthz
```

**实际响应**：
```json
{"ok":true,"db":"up","storage":"up","uptime_ms":27023}
```

✅ **数据库连接正常**  
✅ **存储后端正常**

### 2. 访问管理台

**重要**：管理台 API 使用 **POST** 方法，不是 GET。

#### 登录获取 Token

```bash
curl -X POST http://localhost:7404/api/v1/admin/login \
  -H "Content-Type: application/json" \
  -d '{"secret":"admin_secret_test_12345"}'
```

预期响应：
```json
{
  "ok": true,
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expires_in": 86400
}
```

#### 使用 Token 访问管理台接口

```bash
# 保存 token
TOKEN="your_token_here"

# 查看概览
curl http://localhost:7404/api/v1/admin/overview \
  -H "Authorization: Bearer $TOKEN"

# 列出频道
curl http://localhost:7404/api/v1/admin/channels \
  -H "Authorization: Bearer $TOKEN"

# 创建新频道（返回明文 secret）
# groupId 必填，而且必须是 /admin/devices 里真出现过的 group_id（groups 表里有这一行）；
# 省略或写不存在的 id 一律 400 invalid_param。2026-10-21 之前这里不校验，省略就落到字面量
# "admin" 这个 Space，任何真机都看不到它——先查一个真实 group_id 再建
curl -X POST http://localhost:7404/api/v1/admin/channels \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"groupId":"把 /admin/devices 里某个 group_id 抄过来","name":"测试频道","maxLevel":"warning","maxImages":4}'

# 重置频道密钥
curl -X POST http://localhost:7404/api/v1/admin/channels/12345678/rotate-secret \
  -H "Authorization: Bearer $TOKEN"

# 搜索消息（路径就是 /messages，没有 /search 那一段；q 必填）
curl "http://localhost:7404/api/v1/admin/messages?q=test&limit=50" \
  -H "Authorization: Bearer $TOKEN"
```

**实际测试结果**（2026-10-19）：
```bash
$ curl -X POST http://localhost:7404/api/v1/admin/login \
  -H "Content-Type: application/json" \
  -d '{"secret":"admin_secret_test_12345"}'

{"ok":true,"token":"eyJhbG...","expires_in":86400}

$ curl http://localhost:7404/api/v1/admin/overview \
  -H "Authorization: Bearer $TOKEN"

{"ok":true,"stats":{"total_channels":0,"active_channels":0,...},"trend":[]}
```

✅ **管理台 API 工作正常**

### 3. 查看日志

```bash
# 在 WSL 中
docker compose -f docker-compose-wsl.yml --env-file wsl-test.env logs -f pushbox-server

# 或在 Windows PowerShell 中
wsl docker compose -f /mnt/d/Projects/Android/PushBox/deploy/docker-compose-wsl.yml --env-file /mnt/d/Projects/Android/PushBox/deploy/wsl-test.env logs -f pushbox-server
```

## 常见问题

### Q1: Docker 代理连接失败

**现象**：`proxyconnect tcp: dial tcp 127.0.0.1:7890: connect: connection refused`

**解决**：
```bash
# 临时清除代理
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY

# 或永久修改 Docker 配置
# 编辑 ~/.docker/config.json，移除 proxies 配置
```

### Q1.5: MySQL 启动失败 - 权限问题

**现象**：`Cannot change permissions of the file 'private_key.pem.temp' (OS errno 1)`

**原因**：Windows NTFS 文件系统不支持 MySQL 所需的 POSIX 权限操作

**解决**：已修复！MySQL 和 RustFS 现在使用 Docker volume 而非 bind mount。

### Q1.6: CIDR 配置错误

**现象**：`内网白名单 "127.0.0.1/8" 的主机位不为 0，应写成网段地址 127.0.0.0/8`

**解决**：已修复！配置文件中的 CIDR 已更正为 `127.0.0.0/8`。

### Q1.7: 构建卡在下载依赖（容器里 IPv6 不通）

**现象**：`mvn package` 停在某个 `.pom` 上不动，或报 `curl: (35) Recv failure: Connection reset by peer`；同一个 URL 在 WSL 宿主上 `curl` 却秒回。

**原因**：容器解析 `repo.maven.apache.org` 拿到 AAAA 记录（Cloudflare `2606:…`），而 Docker bridge 网络里没有可用的 IPv6 出口，连接被重置或一直挂着。宿主机走的是 IPv4，所以看起来"网络没问题"。

**解决**：让构建阶段直接用宿主机网络栈：

```bash
docker buildx build --network=host -f deploy/Dockerfile -t pushbox/server:1.1.3 --load .
```

`--network=host` 只影响构建，不进镜像、不影响运行时网络；compose 默认的 bridge 启动方式不变。

### Q1.8: 走 80 端口（Caddy）返回 200 但响应体是空的

**现象**：`curl http://127.0.0.1/api/v1/admin/login` 返回 `HTTP 200`、`Content-Length: 0`，直连 7404 却正常。

**原因**：`Caddyfile` 的站点地址是 `{$PB_CADDY_HOST}:80`，Caddy 按 Host 头匹配站点。
`wsl-test.env` 里是 `PB_CADDY_HOST=localhost`，所以 Host 为 `127.0.0.1` 的请求匹配不到任何站点，
Caddy 直接回一个空的 200 —— 请求根本没进 `reverse_proxy`。

**解决**：用配置里的主机名访问（或把 `PB_CADDY_HOST` 改成 `_` 接受任意 Host）：

```bash
curl http://localhost/api/v1/admin/overview -H "Authorization: Bearer $TOKEN"
```

### Q2: 端口被占用

**现象**：端口 7404 或 80 已被其他程序使用

**解决**：
编辑 `wsl-test.env`，修改端口：
```env
PB_SERVER_PORT=8080
PB_HTTP_PORT=8080
```

然后重启：
```bash
docker compose -f docker-compose-wsl.yml --env-file wsl-test.env down
docker compose -f docker-compose-wsl.yml --env-file wsl-test.env up -d
```

### Q3: MySQL 启动缓慢

**现象**：服务端报错数据库连接失败

**解决**：
MySQL 首次启动需要初始化，等待 30-60 秒后重试。可以查看 MySQL 日志：
```bash
docker compose -f docker-compose-wsl.yml --env-file wsl-test.env logs mysql
```

### Q4: RustFS 权限问题

**现象**：RustFS 容器无法写入数据

**解决**：
```bash
# 在 WSL 中修复权限
sudo chown -R 10001:10001 /mnt/d/Projects/Android/PushBox/deploy/data/rustfs
```

## 停止和清理

### 停止服务（保留数据）
```bash
docker compose -f docker-compose-wsl.yml --env-file wsl-test.env down
```

### 完全清理（删除所有数据）
```bash
docker compose -f docker-compose-wsl.yml --env-file wsl-test.env down -v
rm -rf /mnt/d/Projects/Android/PushBox/deploy/data/*
```

## 监控（可选）

启用 Prometheus + Grafana：
```bash
docker compose -f docker-compose-wsl.yml --env-file wsl-test.env --profile monitoring up -d
```

访问：
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (admin / grafana_test_123)

## 下一步

部署成功后，你可以：

1. **注册设备**：使用 Android 或 Windows 客户端连接 `http://localhost:7404`
2. **创建频道**：通过管理台 API（见上方示例）或 Android 端"新建频道"
3. **发送消息**：参考 README 中的 API 示例
4. **查看指标**：访问 Prometheus 或 Grafana 监控面板

详细使用说明请参考主 README.md。
