# PushBox WSL Docker 部署测试指南

本文档说明如何在 Windows Subsystem for Linux (WSL) 的 Docker 环境中部署和测试 PushBox。

这套栈是**拓扑 B**（全都自己起）：MySQL + RustFS + 服务端 + Caddy 一次拉起，用来在发布前跑真镜像。
它顺带也是**安装页的端到端验证**——`deploy-wsl.sh setup` 走的是和真人在浏览器里点「保存并启动」
完全相同的 `POST /api/v1/setup`。

> 服务端**不需要环境变量**（`PB_*` 仍然认，优先级还高于配置文件，理由见 `部署快速参考.md` 开头那段）。
> 库地址、口令、AK/SK、管理台口令全都在安装页里填，落进容器的
> `/app/data/config/pushbox.json`。`infra/wsl-test.env` 只是 `deploy-wsl.sh` 与 compose 的输入：
> 前者用它拼那一次 POST 的 JSON，后者用它喂 mysql / rustfs / grafana 那几个第三方镜像。

## 前置条件

1. **WSL2 已安装**
   ```powershell
   wsl --install
   ```

2. **Docker Desktop 或 Docker Engine 已安装并运行在 WSL 中**
   - Docker Desktop: 启用 "Use the WSL 2 based engine"
   - 或在 WSL 内直接安装 Docker Engine

3. **测试环境文件**（不入库，`.gitignore` 已排掉）
   ```bash
   cp infra/wsl-test.env.example infra/wsl-test.env
   ```
   里面的值都是测试值，但**别把它提交上去**：进了公开仓库就等于对全世界公开"这套部署的口令长什么样"，
   而 copy-paste 是双向的——有人会把它当模板用在真机上。

## 快速开始

首跑四条命令，顺序不能换（桶不会自动出现，`setup` 之前它必须已经在了）：

```bash
cd infra
./deploy-wsl.sh build && ./deploy-wsl.sh up && ./deploy-wsl.sh bucket && ./deploy-wsl.sh setup
```

| 子命令 | 做什么 |
|--------|--------|
| `build` | 用项目根目录的 `infra/Dockerfile` 构建服务端镜像 |
| `up` | 起 MySQL 8.4 + RustFS + PushBox Server + Caddy。服务端会**停在安装页上**，这是正常的 |
| `bucket` | 在 RustFS 上建桶 `pushbox`（借 rustfs 自己的网络命名空间跑 `amazon/aws-cli`，因为 compose 没给它发布端口，而 `minio/mc` 已从 Docker Hub 下架） |
| `setup` | 把 `wsl-test.env` 里的值拼成 JSON 提交给安装页，然后等 `/healthz` 通 |
| `down` | 停栈，**卷留着**（配置与图片都还在） |
| `logs [service]` | 跟日志，默认 `pushbox-server` |
| `restart` | 重启全部服务 |
| `clean` | 连卷一起删（`down -v`），下次 `up` 之后要重新 `setup`。会先问一次确认，答 `yes` 才动手；没有可交互终端时按「取消」处理，不会闷头删 |

手工来也一样：`up` 之后浏览器打开 `http://localhost:7404/` 自己填表，不需要 `setup`。

**改了 `wsl-test.env` 想重灌一次配置**：安装页只在配置文件不存在时启动，所以要先在容器里把它删掉再重启，
`setup` 才有的可提交（本轮验 SSRF 放行主机就是这么重灌的）：

```bash
docker exec pushbox-test-pushbox-server-1 rm -f /app/data/config/pushbox.json
docker restart pushbox-test-pushbox-server-1
./deploy-wsl.sh setup
```

只想改一项就别跑 `setup`（它整份重灌）：删掉配置重启之后，安装页上先点「从库里找回上一次的配置」
把上次那份捞回来，改那一项再保存。

## 验证部署

### 健康检查

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

`/healthz` 只放行环回加安装页「运维网段」里填的 CIDR，不在白名单里直接回 **404**（刻意不用 403，
免得扫描器确认这里有个运维端点）。`wsl-test.env.example` 里已经放行了 `172.16.0.0/12,127.0.0.0/8`，
所以宿主和模拟器都够得着——**别把这个值抄进生产**，生产那份的默认口径是留空、只放行环回。

也可以进容器里取，那条路永远是环回：

```bash
wsl docker exec pushbox-pushbox-server-1 curl -s http://127.0.0.1:7404/healthz
```

### 管理台登录

浏览器打开 `http://localhost/`（经 Caddy）或 `http://localhost:7404/`（直连），口令是
`wsl-test.env` 里的 `ADMIN_PASSWORD`。管理台在**根路径**，不是 `/admin`。

> 经 Caddy 访问时 Host 头必须匹配 `PB_CADDY_HOST`（`wsl-test.env` 里是 `localhost`）：
> 用 `http://127.0.0.1/` 会拿到一个空 200，看着像"通了"其实根本没进那个站点块。

### 查看日志

```bash
./deploy-wsl.sh logs pushbox-server
wsl docker compose -f docker-compose-wsl.yml --env-file wsl-test.env ps
```

## 配置说明

`infra/wsl-test.env`（模板是 `infra/wsl-test.env.example`）里的键分三类：

| 键 | 给谁用 | 说明 |
|----|--------|------|
| `DB_PASSWORD` | mysql 镜像 + 安装页 | compose 拿它设 `MYSQL_PASSWORD`，`setup` 拿它填安装页的库口令，两边必须同值 |
| `MYSQL_ROOT_PASSWORD` | mysql 镜像 | 只给第三方镜像，服务端不用 |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` / `S3_BUCKET` | rustfs 镜像 + 安装页 | 同上，两边同值 |
| `STORAGE_PASSPHRASE` | 安装页 | 留空 = 这套测试栈走明文（安装页里不勾「加密存储」）；填了就至少 12 个字符。**KEK 用过就不可轮换**，换了口令之前写进桶里的图片再也解不开 |
| `ADMIN_PASSWORD` | 安装页 | 管理台登录口令，至少 8 个字符；留空 = 管理台整个不开放 |
| `INTERNAL_CIDRS` | 安装页 | 逗号分隔的 CIDR，喂「运维网段」；空值就是只放行容器环回 |
| `MEDIA_ALLOW_HOSTS` | 安装页 | 逗号分隔的裸 `host` 或 `host:port`，喂「图片抓取放行主机」；空值就是私网一律不抓。这套测试栈要验图片链路就得给它一个值——容器没有公网出口，图只能从 compose 网络里的临时 HTTP 服务取，而那种地址正是抓取器默认拒绝的私网段（不给的症状是每条图片都 `media_objects.error=ssrf_blocked`） |
| `PB_SERVER_PORT` / `PB_HTTP_PORT` | compose | **宿主侧**的端口映射，不进容器；容器里永远是 7404 |
| `PB_CADDY_HOST` | Caddy 容器 | 站点地址，改一处即可 |
| `GRAFANA_ADMIN_PASSWORD` | grafana 镜像 | 只有 `--profile monitoring` 才用 |

`admin.jwtKey` 不在里面：它由服务端首次启动时自动生成并回写配置文件，不用你填。

## 监控栈（可选）

```bash
wsl docker compose -f docker-compose-wsl.yml --env-file wsl-test.env --profile monitoring up -d
```

访问地址（两个都只绑在 `127.0.0.1` 上）：
- **Prometheus**: http://localhost:9090
- **Grafana**: http://localhost:3000（用户名 `admin`，密码是 `GRAFANA_ADMIN_PASSWORD`）

Prometheus 直连 `pushbox-server:7404/actuator/prometheus`，不经 Caddy——Caddyfile 里那条
`@metrics → 403` 会把经反代的抓取全挡掉。所以 `INTERNAL_CIDRS` 要包含这条网络实际分到的子网，
否则 targets 全 403。

## 故障排查

### 1. 容器启动失败

```bash
./deploy-wsl.sh logs pushbox-server
```

entrypoint 现在只剩一件事：`/app/data` 的写入探针（容器跑 uid 10001）。写不进就当场退出并给出修法。
其余的"起不来"都发生在安装页那一次提交里——它提交前会真连一次库与桶，连不上当场说清，不会落盘一份
起不来的配置。

### 2. 端口冲突

如果 7404 或 80 被占用，改**宿主侧**的映射，不要改容器内的端口：

```bash
# wsl-test.env
PB_SERVER_PORT=8080
PB_HTTP_PORT=8080
```
```bash
./deploy-wsl.sh restart
```

容器内改端口要同步改三处（Caddyfile 的上游、compose 的 `ports`、compose 的 `healthcheck`），
少改一处就是"容器起了但反代 502"。

### 3. 数据库连接失败

```bash
wsl docker exec -it pushbox-mysql-1 mysql -u pushbox -p"$DB_PASSWORD" pushbox -e "SHOW TABLES;"
```

库名与库用户名都固定是 `pushbox`，账号是 `'pushbox'@'%'`（写成 `localhost` 时，服务端容器连过来的
来源就不是 `localhost`，口令全对也照样 1045）。MySQL 首轮初始化在慢盘上要 1～2 分钟，compose 的
healthcheck 会等。

### 4. `setup` 报"安装页拒绝了这份配置"

回话里那句 `error` 就是原因，照着改 `wsl-test.env` 再来一次。最常见的是桶没建（漏了 `bucket`）
或者 `DB_PASSWORD` 与 mysql 那次 `up` 用的值不一致（改了 env 但没 `clean`，卷里的旧口令还在）。

### 5. 查看容器资源使用

```bash
wsl docker stats pushbox-pushbox-server-1
```

## 网络拓扑

```
[客户端] → [Caddy:80] → [PushBox Server:7404] → [MySQL:3306]
                                              → [RustFS:9000]
```

所有服务都在 `pushbox-net` 这张 bridge 网络里，通过服务名互相访问。安装页里「数据库地址」填 `mysql`，
对象存储地址填 `http://rustfs:9000`（`setup` 就是这么填的）。

## 数据持久化

全是 named volume，**不是** `infra/data/` 下的目录：

| 卷 | 装什么 |
|----|--------|
| `pushbox-test_server_data` | 服务端的 `config/pushbox.json`（明文凭据）+ `media/` + `logs/` |
| `pushbox-test_mysql_data` | 数据库 |
| `pushbox-test_rustfs_data` | 桶里的对象 |
| `pushbox-test_caddy_data` / `_caddy_config` | Caddy 自己的状态 |

只有 Prometheus 与 Grafana 用 `infra/data/` 下的绑定挂载，`up` 会自动 `mkdir`。

刻意不把服务端那份挂成宿主目录：`deploy-wsl.sh` 多半跑在 `/mnt/d` 上，drvfs 不支持 `chown`，
宿主目录属主进不了 10001，写入探针会直接拒绝启动。清数据用 `./deploy-wsl.sh clean`，不是 `rm` 目录。

⚠️ `clean` 会连 `server_data` 一起删掉——里面既有图片，也有那份 `pushbox.json`。想留着配置就只 `down`。

## 生产环境注意事项

⚠️ **此配置仅用于测试**，生产部署请：

1. 用 `openssl rand -base64 24` 一类的工具生成口令，别用这里的测试值
2. 启用 TLS（Caddy 只做 HTTP 反代，证书由你在前面那层自己配）
3. `INTERNAL_CIDRS` 留空，只放行环回
4. 设置定期备份，并且**把 `STORAGE_PASSPHRASE` 一起备份**——丢了它等于旧图片全部解不开
5. 启用对象加密（安装页里勾「加密存储」+ 填口令）
6. 调整资源限制

参考 `infra/docker-compose.yml` 与 `README.md` 的「Docker 部署」章节。
