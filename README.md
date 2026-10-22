# PushBox

自托管的消息推送系统：任何程序（CI、告警、脚本、内网服务）用一条 `POST /message` 把消息投进来，PushBox 负责**存下来、抓图转存、加密下发**到你自己的 Android 手机与 Windows PC，通知在两端几乎同时出现（实测同机链路 49ms）。

不依赖厂商推送、不依赖第三方转发服务，服务端、数据、图片全在你自己的机器上。

## 快速开始

### 用户

- 📖 [用户使用手册](docs/用户使用手册.md) - 安装、配置、使用流程、常见问题
- 🐳 [Docker 部署](#-docker-部署) - 一个容器起步只要库地址和库口令；[deploy/部署快速参考.md](deploy/部署快速参考.md) 是同一套口径的速查
- 🗄️ [NAS 部署指南](deploy/NAS部署指南.md) - 飞牛 / 群晖 / 1Panel 的图形界面里逐条填变量那种装法
- 🌐 [官网](https://iamplusdev.github.io/push-box/) - 项目介绍与下载链接（站点源码在仓库的 `site/`）

### 开发者

- 🏗️ [软件设计方案](docs/软件设计方案.md) - 架构、协议、加密、数据模型（权威技术参考）
- 💻 [开发指南](docs/开发指南.md) - 环境搭建、构建流程、调试技巧
- 🧪 [测试指南](docs/测试指南.md) - 单测、集成测试、端到端测试、真机走查清单
- 🔧 [Windows 开发环境安装教程](docs/Windows开发环境安装教程.md) - .NET SDK / Rider / Inno Setup 安装
- 📋 [更新日志](CHANGELOG.md) - 每个版本的改动记录

---

## 🐳 Docker 部署

### 形态一：单容器起步（推荐，两个变量）

镜像自己把拓扑默认值烘焙好了（[deploy/Dockerfile](deploy/Dockerfile) 的 `ENV` 段），所以起步只需要
**数据库地址**和**库口令**这两样只有你自己知道的东西——不需要克隆仓库，也不需要 `.env`：

```bash
docker run -d --name pushbox \
  -p 7404:7404 \
  -e PB_DB_HOST=192.0.2.20 -e PB_DB_PASSWORD='<强口令>' \
  -v pushbox-media:/app/data/media -v pushbox-logs:/app/logs \
  docker.io/iamplusdev/push-box:latest
```

`192.0.2.20` 是 RFC 5737 的文档保留地址，**照抄一定连不上**——换成你那台库的局域网 IP 或服务名。

**镜像之外只有一件事要做**：那个库和那个用户得先存在，服务端不会替你建库（Flyway 只建表）。
在库所在那台机器上跑一次就够了——NAS 上装的 MySQL 就填它的局域网 IP，别填 `127.0.0.1`（容器里的环回是它自己）：

```sql
CREATE DATABASE IF NOT EXISTS pushbox
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'pushbox'@'%' IDENTIFIED BY '<强口令>';   -- 必须是 '%'：另一个容器连过来的来源不是 localhost
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES
  ON pushbox.* TO 'pushbox'@'%';
FLUSH PRIVILEGES;

SELECT @@ngram_token_size;   -- 期望 2；不是 2 就搜不出中文长句
```

另外两条边界，别在起容器之后才发现：**库只能是 MySQL 8.0/8.4**（`V1__init.sql` 用了
`utf8mb4_0900_ai_ci` 与 `FULLTEXT … WITH PARSER ngram`，MariaDB 与 MySQL 5.7 过不去），
而 **Hub 上那份镜像只有 linux/amd64**（发布工作流没传 `platforms`），ARM 机型要自己 `docker build`。

其余一个都不给时的样子：端口 7404、本地文件存储（图片落 `pushbox-media` 卷里，明文 + 每文件 600 权限，
entrypoint 出声提醒一次）、管理台**关闭**、库名与库用户名都是 `pushbox`、Flyway 首次启动自动建表。
想换形态就在那条命令后面追加同名变量，别去改镜像：

| 想要的形态 | 追加 |
|------|------|
| 开管理台 | `-e PB_ADMIN_SECRET='<openssl rand -base64 32>' -e PB_ADMIN_JWT_SECRET='<另一个不同的强随机值>'`（两项都要、互不相同、各 ≥32 字节；**都留空 = 管理台不开放**，客户端收发不受影响，这是安全状态不是漏配） |
| 外接桶 | `-e PB_STORAGE_TYPE=s3 -e PB_S3_ENDPOINT -e PB_S3_ACCESS_KEY -e PB_S3_SECRET_KEY`，再加 `-e PB_S3_OBJECT_ENCRYPT=sse-c -e PB_S3_MASTER_KEY='<openssl rand -base64 32>'` |
| 本地图片也要加密 | `-e PB_S3_OBJECT_ENCRYPT=sse-c -e PB_S3_MASTER_KEY='<openssl rand -base64 32>'`（主钥一旦有对象落库就**不可轮换**） |
| 改端口 | `-e PB_SERVER_PORT=8081` 并把 `-p` 的右侧同步成 8081 |
| 库地址只写完整串 | 用 `-e PB_DB_URL='jdbc:mysql://…'` 替代 `PB_DB_HOST`（两个都给时以 `PB_DB_URL` 为准） |

**手上已经有一个桶**的话，整条 s3 版命令长这样——还是同一个容器，只是把对象落到桶里：

```bash
docker run -d --name pushbox \
  -p 7404:7404 \
  -e PB_DB_HOST=192.0.2.20 -e PB_DB_PASSWORD='<强口令>' \
  -e PB_STORAGE_TYPE=s3 \
  -e PB_S3_ENDPOINT=http://192.0.2.30:7101 \
  -e PB_S3_ACCESS_KEY='<AK>' -e PB_S3_SECRET_KEY='<SK>' \
  -e PB_S3_BUCKET=pushbox \
  -e PB_S3_OBJECT_ENCRYPT=sse-c -e PB_S3_MASTER_KEY='<openssl rand -base64 32>' \
  -v pushbox-logs:/app/logs \
  docker.io/iamplusdev/push-box:latest
```

四件事在这一条上跟本地存储不一样，都会实跑撞：

- **`PB_STORAGE_TYPE=s3` 省不得**。镜像烘焙的默认是 `local`，只给 `PB_S3_*` 而不给这个开关，那几项
  一个字都不读——entrypoint 会点名 WARN「配了但读不到」，进程照旧把图片落本地目录。
- **`pushbox-media` 那个卷去掉了**：对象落桶，挂着也没用；日志卷还是要。桶本身**要你先建好**，
  镜像不建桶。桶可以跟别的系统共用，但 PushBox 只读写自己那套对象键，读不到别人写进去的东西。
- **明文在 s3 这档是拒启的**，不像 local 只出声：默认 `PB_S3_OBJECT_ENCRYPT=none`，而桶侧任何拿到
  同一组 AK/SK 的读者都能直接把图片读走。所以要么像上面那样 `sse-c` + 主钥（**主钥一旦有对象落库就
  不可轮换**，换了旧对象再也解不开），要么显式 `-e PB_ALLOW_PLAINTEXT_MEDIA=1` 表态。
- **`PB_S3_PATH_STYLE` 默认烘焙成 `true`**，MinIO / RustFS 是对的；接阿里云 OSS 或 AWS 时按服务商文档，
  必要时 `-e PB_S3_PATH_STYLE=false`。桶名不是 `pushbox`、区域不是 `us-east-1` 就改上面那两行。

三条不能省的：

- **图片与日志那两个卷要挂**。`PB_STORAGE_TYPE=local` 时对象字节落在 `PB_STORAGE_LOCAL_PATH`（镜像内默认
  `/app/data/media`），不挂卷等于写在容器可写层里，`docker rm` 之后全没。
- **端口以 `PB_SERVER_PORT` 为准**，默认 7404；`EXPOSE` 只是元数据，真正生效的是 `-p` 和这一个变量。
- **`/app/data` 归 uid 10001**。绑定挂载会把宿主目录的属主带进容器，宿主目录不是它可写时，
  entrypoint 的写入探针当场拒绝启动并把两种修法打出来（改用 named volume，或 `chown -R 10001:10001`）。

> 想自己出一份（比如机器在 ARM 上）：`docker build -f deploy/Dockerfile -t pushbox:local .`
> ——这条要克隆仓库，构建读的是仓库里的 `Dockerfile`，跟"跑起来"要的东西不是一回事。

**只在 NAS 的图形界面里装（飞牛 / 群晖 / 1Panel，没有 `.env` 那一层）？**
走 **[deploy/NAS部署指南.md](deploy/NAS部署指南.md)** + **[deploy/docker-compose.nas.yml](deploy/docker-compose.nas.yml)**：
那本文件里没有任何解析阶段的必填插值，改两行就能起，填表格里也就是同样那两个变量。

### 形态二：compose 完整生产形态（要反代 / 监控 / 自带桶时）

**为什么这一条要 `git clone` 而上面不用**：`docker-compose.yml`、`Caddyfile` 和那几段 sidecar 定义都是
**文件**，装不进 `pushbox` 镜像里；`.env` 只是这些文件取值的那层记账。只要一个容器时这些都用不到，
所以形态一既不克隆仓库也不碰 `.env`。要 Caddy 反代 + HTTPS、要 Prometheus/Grafana、或者想让 compose
顺手把 MySQL 与 RustFS 一起起来，才需要形态二。

#### 前置条件

- Docker 20.10+ 和 Docker Compose v2+
- 至少 2GB 内存 + 10GB 磁盘空间
- 一个域名（可选，用于 HTTPS）

#### 步骤 1: 克隆仓库并准备配置

```bash
# 克隆代码
git clone https://github.com/iamplusdev/push-box.git
cd push-box/deploy

# 复制环境变量模板
cp env/prod.env.example .env
```

#### 步骤 2: 生成密钥

```bash
# 生成并直接写入 .env：值只在生成时落在变量里，不打印到终端（滚存/日志会留痕）
PB_DB_PASSWORD=$(openssl rand -base64 24)
PB_S3_ACCESS_KEY=$(openssl rand -hex 16)
PB_S3_SECRET_KEY=$(openssl rand -base64 32)
PB_ADMIN_SECRET=$(openssl rand -base64 32)
PB_ADMIN_JWT_SECRET=$(openssl rand -base64 32)
PB_S3_MASTER_KEY=$(openssl rand -base64 32)

sed -i \
  -e "s|^PB_DB_PASSWORD=.*|PB_DB_PASSWORD=$PB_DB_PASSWORD|" \
  -e "s|^PB_S3_ACCESS_KEY=.*|PB_S3_ACCESS_KEY=$PB_S3_ACCESS_KEY|" \
  -e "s|^PB_S3_SECRET_KEY=.*|PB_S3_SECRET_KEY=$PB_S3_SECRET_KEY|" \
  -e "s|^PB_ADMIN_SECRET=.*|PB_ADMIN_SECRET=$PB_ADMIN_SECRET|" \
  -e "s|^PB_ADMIN_JWT_SECRET=.*|PB_ADMIN_JWT_SECRET=$PB_ADMIN_JWT_SECRET|" \
  -e "s|^PB_S3_MASTER_KEY=.*|PB_S3_MASTER_KEY=$PB_S3_MASTER_KEY|" \
  .env
```

`PB_ADMIN_SECRET` 是登录口令，`PB_ADMIN_JWT_SECRET` 是签 JWT 的密钥，用管理台就两个都要填且不能相同；只需单独记牢口令那一个（登录用），签名钥换掉等于作废全部已登录会话。**两项都留空 = 管理台整个不开放**（登录回 403「管理台未启用」），收发消息不受影响——这是一个安全状态，不是漏配；只填一个则由 entrypoint 判死。

#### 步骤 3: 确认地址与存储加密

模板里除密钥外的占位还需要按你的拓扑确认一次（`deploy/.env`）：

```bash
# 拓扑 A：复用已有的 MySQL / RustFS，把主机换成实际地址
PB_DB_URL=jdbc:mysql://<DB_HOST>:3306/pushbox?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8
PB_DB_USERNAME=pushbox
PB_S3_ENDPOINT=http://<S3_HOST>:7101
PB_S3_REGION=us-east-1
PB_S3_BUCKET=pushbox
PB_S3_PATH_STYLE=true

# 对象存储加密：prod 只认两种显式表态（这条只卡 s3 拓扑，见下面拓扑 C）
PB_S3_OBJECT_ENCRYPT=sse-c        # 配 PB_S3_MASTER_KEY（base64 的 32 字节，落库后不可轮换）
# PB_S3_OBJECT_ENCRYPT=none       # 明文桶：必须同时打开下面的逃生阀，否则 entrypoint 拒绝启动
# PB_ALLOW_PLAINTEXT_MEDIA=1

# ---- 拓扑 B：完全独立部署（取消注释，主机改成服务名）----
# PB_DB_URL=jdbc:mysql://mysql:3306/pushbox?...同上...
# PB_S3_ENDPOINT=http://rustfs:9000
# 还需要 MYSQL_ROOT_PASSWORD=<只在初始化用>（mysql / rustfs 服务段见 docker-compose.yml）

# ---- 拓扑 C：本地文件系统存储（零依赖单机部署）----
PB_STORAGE_TYPE=local
PB_STORAGE_LOCAL_PATH=/app/data/media
# PB_S3_OBJECT_ENCRYPT=none 或 sse-c + PB_S3_MASTER_KEY。
# local + none 只打一条 WARN 就启动，不需要 PB_ALLOW_PLAINTEXT_MEDIA——
# 对象就在部署者自己的盘上（逐文件 600 权限），硬拦只会逼人为了过检查去配一个用不到的桶。

# ---- 监控（可选，多传一个 -f docker-compose.monitoring.yml）----
GRAFANA_ADMIN_PASSWORD=<改掉默认 admin 密码>
```

`useSSL=false` / `http://` 意味着 SQL 与对象字节在链路上明文可听：同机或同一容器网络内通常只能如此，跨主机请改成 `useSSL=true` 与 `https://`（模板注释里写了口径）。**本地存储模式**（`PB_STORAGE_TYPE=local`）无需配置任何 S3/MinIO/RustFS 服务，图片落在容器内的 `PB_STORAGE_LOCAL_PATH`，`docker-compose.yml` 已把那里挂成 named volume `pushbox_server_media`（备份时用 `docker volume inspect` 取挂载点，见 [deploy/部署快速参考.md](deploy/部署快速参考.md)）。

#### 步骤 4: 启动服务

```bash
# 基础部署（仅 PushBox + Caddy 反代）
docker compose up -d

# 带监控栈部署（Prometheus + Grafana）——监控服务在单独一本文件里
docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up -d

# 查看日志
docker compose logs -f pushbox-server
```

首次启动需要 1-2 分钟（Flyway 数据库迁移 + Spring Boot 初始化）。

#### 步骤 5: 验证部署

```bash
# 健康检查：在容器里取（/healthz 只认容器环回，从宿主打过去是 404，见下面那条说明）
docker compose exec pushbox-server curl -s http://127.0.0.1:7404/healthz

# 预期最后一段：
# {"ok":true,"db":"up","storage":"up","uptime_ms":...}
```

`curl http://localhost:7404/healthz` 在**宿主上**拿不到这个结果：`InternalAccessFilter` 只看 `getRemoteAddr()`，
经端口映射进来的连接在容器里是网桥网关地址，会被回 **404**（拒绝时刻意不用 403，免得扫描器确认这里有个运维端点）。
要让宿主或监控直接读到它，就把这套 compose 网络分到的子网写进 `PB_SECURITY_INTERNAL_CIDRS`。

访问浏览器：
- **Web 管理台**: `https://<PB_CADDY_HOST>/`——注意是**根路径**，不是 `/admin`。管理台 SPA 与 API 打在同一个镜像、由同一个进程在同一个端口上吐出来（`deploy/Dockerfile` 的 node 阶段把 `web-admin/dist` 拷进 jar 的 `classpath:/static/`，`SpaWebConfig` 负责 history 路由的深链回退），所以没有第二个端口、也没有独立的 nginx。用 `PB_ADMIN_SECRET` 登录；自签证书要先信任 `deploy/ca` 里那根 CA，或改用 `docker-compose.nas.yml` 那条直连 7404 的内网明文路径。
- **运维端点**: `/healthz` 与 `/actuator/**` 只在容器环回、以及显式写进 `PB_SECURITY_INTERNAL_CIDRS` 的网段可读；Caddy 另外只 403 掉 `/actuator/*`。

#### 步骤 6: 换成自己的域名 / 证书（可选）

主机名只在 `.env` 里改一处：

```bash
PB_CADDY_HOST=pushbox.example.com
```

证书两种口径，`deploy/Caddyfile` 顶部注释里都写了：

1. **内部自签 CA**（默认）：用 `deploy/ca/gen-ca.sh` 签发（域名走 `PUSHBOX_HOST`，纯 IP 走 `PUSHBOX_IP`，SAN 必须包含客户端实际连的那个地址），把 `pushbox.crt` / `pushbox.key` 放进 `deploy/certs/`，再把根 CA 装进各端信任（Windows 需要 `-user Root`，Android 需要用户在系统里安装）。
2. **公网可信证书**：删掉 Caddyfile 里的 `tls` 那行，让 Caddy 自己申请 Let's Encrypt；前提是 80/443 从公网可达、DNS 已指向这台机器。各端无需装证书。

改完重启：

```bash
docker compose up -d caddy
docker compose logs --tail=50 caddy
```

### 常用运维命令

形态二（compose）：

```bash
# 查看服务状态
docker compose ps

# 查看实时日志
docker compose logs -f pushbox-server

# 备份数据库（拓扑 B；口令走 -e 传进容器环境，不写进命令行——命令行在宿主 ps 里可见）
docker compose exec -T -e MYSQL_PWD="$PB_DB_PASSWORD" mysql \
  mysqldump -u pushbox --single-transaction --quick pushbox > backup_$(date +%Y%m%d).sql

# 升级版本
docker compose pull
docker compose up -d

# 停止服务
docker compose down

# 完全清理（包括数据卷）
docker compose down -v
```

形态一（单容器）只有那一个容器，命令换成 `docker` 那套；**`docker rm` 不会带走 named volume**，
图片与日志都还在，别用 `docker rm -v`：

```bash
docker logs -f pushbox
docker restart pushbox
docker volume ls | grep pushbox-          # 那两个 named volume（图片与日志），docker rm 不会带走它们

# 升级：先取新镜像，删容器不会删卷，再用同一条 docker run 起一次
docker pull docker.io/iamplusdev/push-box:latest
docker rm -f pushbox
```

### 故障排查

**问题 1: 容器启动后立即退出**

```bash
docker compose logs pushbox-server    # 形态一用 docker logs pushbox

# entrypoint 的报错自己就带修法，照着那几行读比这一节全。四条最常见的：
# - PB_DB_HOST 填成 127.0.0.1（容器里的环回是它自己，不是宿主）
# - 库那层没发布端口 / 库容器自己在崩溃循环（docker logs <库容器> --tail 20 会明说）/ 防火墙拦了入站
# - 第一次起空库撞超时不算故障：库的初始化在 NAS 上普遍要 1～2 分钟，把 PB_DB_WAIT_SECONDS 调大（默认 60）
# - 本地存储目录不可写 → 改用 named volume，或把宿主目录 chown -R 10001:10001
```

**问题 2: 健康检查一直显示 db=down**

`db=down` 是 `/healthz` 真去借了一条连接、那条连接没起来。先在容器里取一次（`/healthz` 只认容器环回，
从宿主打过去是 **404**——`InternalAccessFilter` 只看 `getRemoteAddr()`，经端口映射进来的连接在容器里
看到的是网桥网关；拒绝时刻意不用 403，免得扫描器确认这里有个运维端点）：

```bash
docker compose exec pushbox-server curl -s http://127.0.0.1:7404/healthz    # 形态一：docker exec pushbox curl -s …
docker compose logs pushbox-server | grep -iE 'HikariPool|Access denied|Communications link|to database' | head
```

那两条已经能把"口令错 / 地址错 / 库名错"分开；**别在容器里现装 mysql 客户端**（容器跑 uid 10001，
`apt-get update` 直接 `Permission denied`）。要手工敲 SQL 就去库所在那台机器上，详见
[deploy/部署快速参考.md](deploy/部署快速参考.md) 的排查段。

**问题 3: Web 管理台返回 403**

先看是不是根本没开：`PB_ADMIN_SECRET` 与 `PB_ADMIN_JWT_SECRET` **两项都留空是合法状态**，管理台整个不开放，
一律回 403「管理台未启用」，客户端收发照旧。要开就把两项都配上（互不相同、各 ≥32 字节）。
口令本身不对时同理——403 不代表服务坏了。

---

## 一分钟上手（本机联调）

前置：JDK 21、MySQL 8（`ngram_token_size=2`）、Node 18+、Maven。**不需要对象存储**——dev 的存储默认是本地文件（`PB_STORAGE_TYPE=local`，图片落 `./data/media`），要验桶形态再显式给 `s3`。

```bash
# 1) 凭据只走环境变量，值只写在 deploy/env/local.env（已 gitignore）
cp deploy/env/local.env.example deploy/env/local.env   # 起步只必填 PB_DB_PASSWORD；模板每项都带 export
#    注意别拿 prod.env.example 当本机模板：那一份的口径是容器，键集与默认值都不同

# 2) 构建（机器默认 JAVA_HOME 常是 JDK 8，必须显式覆盖）
JAVA_HOME=/path/to/jdk-21 mvn -B -DskipTests package

# 3) 起服务端（默认 7404；脚本自己 source local.env）
bash deploy/run-dev.sh

# 4) 验收探针
node deploy/probe/p0-e2e.mjs
```

发一条消息试试：

```bash
curl -sS http://127.0.0.1:7404/message -H 'Content-Type: application/json' \
  --data-binary @payload.json
```

接口参考按鉴权分成三页，别混用（[入站 API](https://iamplusdev.github.io/push-box/api.html) 写消息进来 / [客户端 API](https://iamplusdev.github.io/push-box/client-api.html) 设备读自己的 Space / [管理台 API](https://iamplusdev.github.io/push-box/admin-api.html) 一条口令换 24h JWT、跨 Space 运维）；协议与数据模型的权威描述在 [软件设计方案](docs/软件设计方案.md)。页面源码在仓库的 `site/`，线上地址是 [官网](https://iamplusdev.github.io/push-box/)。

---

## 仓库地图

```
core/       Java 加密与协议库（X25519 / Ed25519 / AES-GCM 帧通道 / canonical 签名）
server/     Spring Boot 服务端：入站 API、设备与频道、WS 下发、抓取转存、搜索、治理
android/    Android 客户端（Kotlin + Compose），Gradle 工程
windows/    Windows 客户端（.NET 8 + WPF）
deploy/     探针、Dockerfile、compose、Caddyfile、自签 CA、备份脚本
docs/       设计文档、用户指南、开发指南
site/       官网静态站点源码（零构建，可直接 file:// 打开；线上地址 https://iamplusdev.github.io/push-box/）
testvectors/ 由 core 单测产出，供 Windows 侧逐字节对账
```

`core/` 与 `server/` 是同一个 Maven reactor；`android/` 与 `windows/` 各有独立构建体系。三端**不是三份协议实现**：Java 与 Kotlin 共享同一份 `core` 源码，C# 靠 `testvectors/vectors.json` 逐字节对账。

---

## 核心特性

| 特性 | 说明 |
|------|------|
| **自托管** | 服务端、数据库、对象存储全在你自己的机器上 |
| **多端同步** | Android + Windows 同时收到消息，已读状态互相同步 |
| **应用层加密** | WebSocket/REST 下行载荷用 AES-256-GCM 加密，TLS 中间人拿不到正文 |
| **明文存库** | 服务端可见明文，支持全文搜索、摘要生成、图片抓取 |
| **恢复码机制** | 重装或换新设备能找回全部历史 |
| **频道过滤** | 每台设备可挑选要接收的频道，默认全收 |

---

## 测试与验收

| 跑法 | 判什么 |
|------|--------|
| `JAVA_HOME=… mvn -B -pl core,server test` | 服务端与加密库（**308/308**，core 43 + server 265）。要先 `set -a && . deploy/env/local.env && set +a`：114 条挂 `PB_S3_ACCESS_KEY` 门控，缺凭据时是 194 执行 + 114 静默 skip，而 `mvn test` 的退出码仍然是 0 |
| `node deploy/probe/p0-e2e.mjs` | 端到端协议（**0 失败**；条数以脚本末行的 `N/N` 为准） |
| `cd android && ./gradlew :app:assembleDebug` | Android 编译通过 |
| `cd web-admin && npm ci && npm run build` | 管理台 `vue-tsc` 类型检查与 `dist/` 产物 |
| `dotnet test windows/PushBox.sln` | C# 协议向量 + 客户端逻辑（**554/554**，去掉会话筛选用例后） |
| `dotnet run --project windows/PushBox.LiveCheck` | PC 端真服务端判据（**12/12**） |

---

## 铁律（改代码前先读）

1. **凭据只走环境变量。** `PB_DB_PASSWORD`、`PB_S3_*` 只出现在 `deploy/env/local.env`。配置文件里出现明文口令就算事故。
2. **正文不进日志。** 入站 body、`content`、`summary`、搜索结果片段都不得出现在任何 appender 输出里。
3. **绝不绕过证书校验。** 找不到 `ServerCertificateCustomValidationCallback` 等钩子是设计如此。
4. **已读游标只增不减。** 客户端必须自己保 `MAX(...)`，否则会把别的端重新点亮红点。
5. **改了协议字段要三处同步：** `core/Frames.java`、`android/net/Wire.kt`、`deploy/probe/p0-e2e.mjs`。

---

## 信任边界

- **服务端→客户端加密**：每个设备一对密钥，WS 帧走 AES-256-GCM 通道，REST 响应走一次性 sealed-box。
- **服务端库里是明文**：这是刻意的，只有这样搜索、摘要、抓图才能工作。
- **端到端加密已被否决**：代价是"服务端不能搜索、不能生成摘要、不能抓图"。
- **投递单位是"设备"不是"Space"**：同一个 `device_id` 的新连接会顶掉旧的那条——把身份文件拷到另一台机器得到的是"轮流持有同一条连接"，不是"两台设备"。
- **Web 管理台面和业务 API 挂在同一个端口上，默认公网可达**：`/api/v1/admin/*` 前面只有登录口令加一把 24 小时的 HS256 JWT；`deploy/Caddyfile` 只 403 掉 `/actuator/*`，服务端 `InternalAccessFilter` 的白名单也只覆盖 `/healthz` 与 `/actuator/**`。所以"管理台在内网"不是软件给的保证，是部署者要做的事（收紧办法见 [SECURITY.md](SECURITY.md) 的「部署侧该自己做的事」与 `deploy/Caddyfile` 里那段注释示例）。
- **那把管理台 JWT 不能刷新、也不能吊销**：签名钥是独立的 `PB_ADMIN_JWT_SECRET`，所以改登录口令 `PB_ADMIN_SECRET` 不会让已签出去的 token 失效；能做的事只有等它按 `exp` 自然过期（≤24h），或换掉 `PB_ADMIN_JWT_SECRET`（代价是所有会话都要重新登录）。口令被刷的按 IP 锁定是**进程内 Caffeine**（`AbuseControlService.adminLoginAttempts`，代码注释里写明了"登录没有对应的库行可写"）——重启清零、多实例不共享。

详见 [软件设计方案 §1](docs/软件设计方案.md)。

---

## 持续集成（GitHub Actions）

推到 `main` 自动出各端产物、推 Docker Hub；打 `v*` 标签额外发布 GitHub Release；改动含 `site/**` 的
`main` 推送还会把官网发到 GitHub Pages（线上地址 <https://iamplusdev.github.io/push-box/>）。六个工作流都在
`.github/workflows/`，其中 `ci.yml` 是 PR 门禁（真 MySQL + 真对象存储，末尾断言"闸门用例一条都没被跳过"）。

| 工作流 | 触发 | 产出 |
|--------|------|------|
| `docker-publish.yml` | push main / `v*` 标签 / 手动 | buildx 多阶段构建（含 Web 管理台）并推 `iamplusdev/push-box`：标签上打本次的 `X.Y.Z` / `X.Y` / `latest`，main 上只打 `main`/`sha-*`（版本标签必须不可变）。构建前先跑版本闸门——tag、`pom.xml`、compose 三者版本号不一致就直接红，不会推出去一个自称某版、里面跑着另一版的镜像。手动触发默认是 **dry-run**（只构建不推），用它可以在不碰 Hub 的前提下验 `DOCKERHUB_*` 这对 secrets。**`v*` 标签事件首跑 2026-10-22 绿**（run `39724347017`，tag `v1.1.0`）：「版本号一致性闸门」「Docker metadata」「Build and push」「本次的标签清单」四步全 success，即 `1.1.0`/`1.1`/`latest` 三个标签随 buildx 一起推出。Hub 上的实际标签本机复核不到（`hub.docker.com`、`registry-1.docker.io` 都是 `http=000` 超时），这三枚标签存在只由那两步的退出码背书 |
| `build-apk.yml` | push main / `v*` 标签 | 只出 `assembleRelease`（R8 + 签名），用 `apksigner verify` 把关后上传 `pushbox-release-*.apk`；标签时发 Release。没配 keystore 时构建直接失败，不再退化成"发一只 debug 包"。**标签发 Release 首跑 2026-10-22 绿**（run `39724346953`）：`Verify release APK signature` 与 `Publish to GitHub Release` 两步 success，资产 `pushbox-release-v1.1.0.apk`（12.8 MB）已挂到 Release 页 |
| `build-msi.yml` | push main / `v*` 标签 | `windows-latest` 上 `dotnet publish self-contained win-x64` + Inno Setup 出 `setup.exe`，上传 artifact；标签时发 Release。**标签首跑 2026-10-22 绿**（run `39724347008`），资产 `PushBox-Setup-1.1.0-win-x64.exe`（58.7 MB）；`Sign installer` 仍 skipped——**安装包未签名**（`CODESIGN_*` 那对 secrets 未配） |
| `update-dockerhub-description.yml` | push main 且改动含 `README.md`（带 `paths:` 过滤的两个之一，另一个是 `site-deploy.yml`） | 把 `README.md` 同步为 Docker Hub 仓库说明。**2026-10-22 第一次跑绿**（run `39720441754`，push `main`、head `ab5c9fb7`，改动含 `README.md`）。此前它空了一整版：唯一那次 `main` 推送是"新建分支"（`before` 是全零），GitHub 算不出改动文件集，带 `paths:` 的它被跳过、另外四个照跑；因为从没被登记过，连网页上的「Run workflow」都不存在，只能等一次改到 `README.md` 的常规推送（排查过程与四条证据见 `发布检查清单.md`「Hub 仓库说明」）。**Hub 上的说明正文本机没复核到**——`hub.docker.com` 从这台机器三条路都是超时，这一句只有 CI 那一步的退出码背书 |
| `site-deploy.yml` | push main 且改动含 `site/**` / 手动 | 把 `site/` 那棵目录树原样发到一条**只装站点**的分支 `gh-pages`（分支的根树 == `site/` 的树，没有源码也没有构建产物），Pages 以它为源。没有构建步骤，用 `git commit-tree` + push 完成，只依赖 `actions/checkout`。**内容没变就不发**：`paths:` 挡掉"只改代码"的推送，树哈希比较再挡掉"同一份 site/ 被带进来两次"和手动重跑（dispatch 不吃 `paths:`，所以这层不可省）。不挂 `v*` 标签——官网与版本号无关。**首跑已在 2026-10-22 兑现**：run `39720441735` 推出 `gh-pages`（根树等于那次推送的 `HEAD:site`），Pages 构建 `39720519087` success，`https://iamplusdev.github.io/push-box/` 实测 200，页面里全部资源都是相对路径所以子路径下不缺样式。**它不开 GitHub Pages**：源没配置时这条链只推分支、站点地址打不开，第一次要么在网页 Settings → Pages 把源设成 `gh-pages` / `/`，要么拿管理员 token 调一次 `POST /repos/<仓库>/pages` |

出制品与门禁的那四个工作流没有 `paths:` 过滤：只要落到 `main` 或 `v*` 标签，哪怕只改一个像素也会整套跑一遍。**推其它分支名（例如发布前的 `publish` 分支）不触发任何工作流**，这也是"代码先上远端、CI 暂时不动"能成立的原因。`site-deploy.yml` 从非 `main` 分支手动触发且真要推上去时会被直接拒（Pages 只有一个地址，没有预览地址可回退），要越过这道确认得显式勾上 `force_branch`；勾 `dry_run` 不拦，因为它一个字节都不推。

**要在仓库 Settings → Secrets and variables → Actions 配的密钥：**

- 必需：`DOCKERHUB_USERNAME`、`DOCKERHUB_TOKEN`（Docker Hub access token，不是登录密码）。
- 必需（APK 工作流）：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。release 签名由这四个环境变量驱动，缺任何一个 `assembleRelease` 就地失败——debug 包不能升级成 release 包（签名不符，`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），客户端也只认 `pushbox-release-*` 资产。
- 可选（Windows 代码签名）：`CODESIGN_CERT_BASE64`、`CODESIGN_PASSWORD`，见 [docs/代码签名指南.md](docs/代码签名指南.md)；不配则产物未签名、首启有 SmartScreen 提示。

### 应用内更新

打了 `v*` 标签发布的 GitHub Release 就是各端「检查更新」的来源：Android 与 Windows 客户端读 `api.github.com/repos/iamplusdev/push-box/releases/latest`，比对 `versionName`/tag 的 semver，发现新版**先弹一只确认框**（"发现新版本 X，是否下载并安装？→更新/稍后"），**用户点确认才下载**——下载完 Android 拉系统安装面板让用户再确认一次，Windows 下 `PushBox-Setup-*-win-x64.exe` 后静默覆盖安装并提示重启；手动检查与冷启动发现都汇到同一只确认框。检查只在「冷启动静默查一次」和「设置里手动点检查」两处触发（未认证限额 60 次/小时/IP，不进轮询），冷启动那趟还受设置里「启动时自动检查更新」开关门控（默认开）。走的是公开 https，与自建的业务服务器无关。

两处 CI 专属适配（都不改本地开发用的文件）：`gradle-wrapper.properties` 已经写死官方 `services.gradle.org` 发行包，CI 上不再需要临时改写；APK 质量闸是"能编出来、签上名，且证书指纹等于 `android/release-cert.sha256` 里钉的基线"——换 key 会让老用户卸载重装、历史消息全丢，所以这一步直接判红而不是只打印。服务端测试**已经进 CI 了**——`ci.yml` 用 service container 起真 MySQL 8.4（带 `--ngram_token_size=2`）与真 RustFS，本地不需要 `deploy/env/local.env` 也能得到同一份判据；早先"因为依赖外部存储所以不放 CI"的说法已经作废。

> **2026-10-20 深夜：门禁这条链第一次在 GitHub runner 上全绿**——`ci.yml` 对 `publish` 的 dispatch（`run 39377846888`，head `60c86ad`）四个 job 全 success，其中「等 RustFS 就绪并建桶」与「断言 DB 闸门用例真的执行了」（`skipped == 0` 且用例数 ≥ 261）都是第一次真跑到；`build-apk.yml` 同日 dispatch（`run 39376573834`，同一个 head）也全绿，`Verify release APK signature` 这一步第一次真签出并验了名。**当天最后一次 dispatch（`run 39381072797`，head `5293801`）又把签名步升级成"证书 SHA-256 必须等于 `android/release-cert.sha256` 里的基线"并且绿了**，所以"CI 出的包用的就是那把正式 key"是 CI 自己验过的事实，不再靠间接推断。**这一路是四次 dispatch 逐段红出来的，四个根因都不在本仓代码里**：建桶步原来拉的 `minio/mc` 镜像在 Docker Hub 已取不到（→ 404，`dl.min.io` 的 mc 二进制 → 410 Gone），改用 runner 预装的 AWS CLI v2；`android-actions/setup-android@v3` 要装同样已下架的 legacy `tools` 包，改用 runner 预装 SDK 加一步"现读 `compileSdk`、断言 platform 与 build-tools 在位"的入镜检查；KSP 插件 marker 报"六个仓库都找不到"的根因是 runner 打 `maven.aliyun.com` 的三个仓库**整片 502**（境外数据中心 IP 被拒，本机在墙内看不见，所以本地一直绿），现已改成默认只走官方源、开发机在 `android/local.properties` 加 `pb.maven.cnMirror=true` 才插回镜像（见 `docs/开发指南.md`）；最后一步红在 `ANDROID_KEYSTORE_BASE64` 不是标准 base64（`Illegal base64 character 2d`，`2d` 就是 `-`，`certutil -encode` 的 PEM 包头产物就是这个形状），重存后通过，判据写在 `build-apk.yml` 文件头与 `发布检查清单.md`。
>
> **2026-10-22 收尾：上一条记的那两件"还没被证明"，这一版两件都兑现了**。一是 `main` 上 `ci.yml` 绿了——`MediaSsePipelineTest` 把 `pb.storage.type=s3` 写进自己的 `@SpringBootTest(properties=…)` 之后，head `f86eeac` 的 run `39722819597` 四个 job 全 success，第 8 步「断言 DB 闸门用例真的执行了」（`skipped == 0` 且用例数 ≥ 306）第一次在 `main` 上真跑到；下一笔 `4e2fd71` 的 run `39723738268` 同样全绿。**但 `main` 不是每次都绿**：紧跟着的 `7b148ea`（只改文档与两个 Release NOTE 模板，一个 C# 字节都没动）红在「Windows 端测试」——`dotnet test` 退 1，而唯一答得出"哪几条红了"的计数闸那一步当时被 skip，公开 annotation 只剩一句"exit code 1"；本机 554/554 复现不出（带 `TZ=UTC` 也是），下一笔 `f9b1c67`（run `39727438065`）又全绿。那一次的红因没有名字，出口已经修好了（`ci.yml` 那步改成 `if: always()` 并逐条写 annotation），过程记在 CHANGELOG 第二十九轮。二是 `v*` 标签那条发布路径第一次在 runner 上跑完并全绿：`build-apk.yml` run `39724346953`、`build-msi.yml` run `39724347008`、`docker-publish.yml` run `39724347017`（「版本号一致性闸门」与「本次的标签清单」两步都 success），Release 页 <https://github.com/iamplusdev/push-box/releases/tag/v1.1.0> 上两个资产——`pushbox-release-v1.1.0.apk`（12.8 MB，`Verify release APK signature` success，即证书 SHA-256 等于 `android/release-cert.sha256` 钉的基线）与 `PushBox-Setup-1.1.0-win-x64.exe`（58.7 MB，`Sign installer` 仍 skipped，**未签名**）。**这一版同时暴露一条自己的 bug**：Release 正文首行把镜像写成 `iamplusdev/push-box:v1.1.0`，而 `docker-publish.yml` 的 metadata 推的是去掉 `v` 的 `1.1.0` / `1.1` / `latest`——照着正文 `docker pull` 会指向一个 Hub 上不存在的标签。两个构建工作流的模板都已改成从 `${TAG#v}` 推标签（本地把这两步的 `run` 脚本原样抽出来、`gh` 打桩执行，输出实测为 `1.1.0`），但**已经发出去的那一页正文改不掉**：这台机器没有 `gh` CLI、也没有可用的仓库 token，所以 `v1.1.0` 那页的首行至今仍是错的，从下一个 tag 起才对。**仍未被独立复核的两件**：① Hub 上实际的标签清单——`hub.docker.com` 与 `registry-1.docker.io` 从这台机器都是连接超时（`http=000`，不是 404），"三个版本标签已推上去"只有 CI 那两步的退出码背书；② Pages 站点没有人眼看过排版，手上的证据只有 HTTP 200 与"`gh-pages` 根树 == `HEAD:site` 树"这条字节级判据。哪个 workflow 跑过几次、最新一次结论如何，用 `发布检查清单.md` 里那段按 workflow 分别数的命令现查，不要抄条数。

---

## 已知限制

- **Web 管理台是只读视图 + 定时任务，频道生命周期不在它界面上**：概览、消息搜索、频道/设备列表与详情可查（跨空间），能吊销设备、能建/改/启停/删定时任务。建频道与重置密钥**只有接口、没有按钮**（`POST /api/v1/admin/channels` 创建并返回一次明文 secret、`POST /api/v1/admin/channels/{channelNo}/rotate-secret` 换新钥），日常走这两件事请在 Android 或 Windows 的「频道」页做；已有频道的 secret 任何列表接口都不回显，只有创建/重置那一次给。设备的创建与治理写操作仍走客户端 + CLI。
- **桶里已有对象默认是明文**：切到 `sse-c` 只影响新写入的对象，历史对象不迁移。
- **端上数据库不加密**：Android 用 Room、PC 用 SQLite 明文存消息正文（可搜索的前提）；被包裹的只有身份私钥、token、恢复码与 PIN 散列，它们走 AndroidKeyStore 里那把不可导出的 AES-GCM 密钥。拿到 root/磁盘镜像仍能读出历史消息。
- **明文 HTTP 是可选项而不是默认护栏**：客户端允许 `http://IP:端口`（内网/Tailscale 场景），是否上 TLS 由部署者决定；密文只保证「服务端 → 客户端」这一跳，端到端不在设计范围内。
- **消息永久保留**：没有自动过期；容量治理靠报表 + 手动清理。

## 安全

漏洞请不要开公开 issue，走 [SECURITY.md](SECURITY.md) 里的私密渠道；GitHub 仓已开启私密安全报告。

## License

[MIT](LICENSE)

---

**文档版本**：v1.1.1  
**最后更新**：2026-10-22  
**主分支**：main  
**官网**：<https://iamplusdev.github.io/push-box/>（源码在 `site/`，改 `site/**` 推到 `main` 就自动发布）  
**仓库**：[GitHub](https://github.com/iamplusdev/push-box.git)（主仓，Issue 与 CI 都在这边）| [Gitee](https://gitee.com/iamplus/push-box.git)（手动同步的国内镜像，可能落后主仓）
