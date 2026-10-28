# PushBox

自托管的消息推送系统：任何程序（CI、告警、脚本、内网服务）用一条 `POST /message` 把消息投进来，PushBox 负责**存下来、抓图转存、加密下发**到你自己的 Android 手机与 Windows PC，通知在两端几乎同时出现（实测同机链路 49ms）。

不依赖厂商推送、不依赖第三方转发服务，服务端、数据、图片全在你自己的机器上。

## 快速开始

### 用户

- 📖 [用户使用手册](docs/用户使用手册.md) - 安装、配置、使用流程、常见问题
- 🐳 [Docker 部署](#-docker-部署) - 一个容器起步只要库地址和库口令；[infra/部署快速参考.md](infra/部署快速参考.md) 是同一套口径的速查
- 🗄️ [NAS 部署指南](infra/NAS部署指南.md) - 飞牛 / 群晖 / 1Panel 的图形界面里逐条填变量那种装法
- 🌐 [官网](https://iamplusdev.github.io/push-box/) - 项目介绍与下载链接（站点源码在仓库的 `site/`）

### 开发者

- 🏗️ [软件设计方案](docs/软件设计方案.md) - 架构、协议、加密、数据模型（权威技术参考）
- 💻 [开发指南](docs/开发指南.md) - 环境搭建、构建流程、调试技巧
- 🧪 [测试指南](docs/测试指南.md) - 单测、集成测试、端到端测试、真机走查清单
- 🚶 [人工走查清单](docs/人工走查清单.md) - 自动化盖不到的三端行为，每条带演法与判据
- 🔧 [Windows 开发环境安装教程](docs/Windows开发环境安装教程.md) - .NET SDK / Rider / Inno Setup 安装
- 📋 [更新日志](CHANGELOG.md) - 每个版本的改动记录

---

## 🐳 Docker 部署

### 形态一：单容器起步（推荐，一个变量都不用给）

镜像里**没有任何配置**——没有 `ENV`，没有必填变量，没有 `__SET_ME__` 占位值（见 [infra/Dockerfile](infra/Dockerfile)）。
第一次启动它自己在 7404 上开一个安装页，你在浏览器里把库地址、存储、管理台口令填进去，
服务端把结果写进 `/app/data/config/pushbox.json` 然后**接着启动，不用重启容器**；启动完成后安装页会**自己跳到管理台**，不用手动刷新：

```bash
docker run -d --name pushbox \
  -p 7404:7404 \
  -v pushbox_data:/app/data \
  docker.io/iamplusdev/push-box:latest
```

然后浏览器打开 http://127.0.0.1:7404/ ——看到的就是安装页。没配置完时，`/dashboard`、`/login` 这些地址也会 302 回这一页，所以同一个地址在配置前后一直能用。

提交前先在 `docker logs pushbox` 里找「安装口令」那串 32 位字符，粘到安装页顶部的口令框里。
没配完的那段窗口里，**谁都能打开这张空表**，但只有从日志里拿到口令的人能写配置——所以口令别贴到公网聊天里。

之所以不再走环境变量：NAS 的「创建容器」表单只能逐行填 ENV，于是"要填几项"变成了十几个键名与占位值的组合，
填错任何一项的表现都是**容器起来了但半死**；而一个网页表单能把"库连不上"这种错当场说清楚，还能顺手把库建出来。

不想敲命令、要在飞牛/群晖的「创建容器」表单里填的，照 [NAS 部署指南](infra/NAS部署指南.md)：
镜像声明了唯一一个 `VOLUME /app/data`，所以「存储位置」只有**一行**要选——把它指到 NAS 的共享文件夹，
配置、图片、日志就都保住了；环境变量那一栏**一行都不用填**。

**安装页会替你建库，但不会替你建用户。** 它连上库服务器之后执行 `CREATE DATABASE IF NOT EXISTS pushbox`，
所以那个用户得先存在、并且有建库与建表的权限。在库所在那台机器上跑一次就够了——
NAS 上装的 MySQL 就填它的局域网 IP，**别填 `127.0.0.1`**（容器里的环回是它自己，安装页会当场把这句话告诉你）：

```sql
CREATE USER IF NOT EXISTS 'pushbox'@'%' IDENTIFIED BY '<强口令>';   -- 必须是 '%'：另一个容器连过来的来源不是 localhost
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES
  ON pushbox.* TO 'pushbox'@'%';
FLUSH PRIVILEGES;

SELECT @@ngram_token_size;   -- 期望 2；不是 2 就搜不出中文长句
```

另外两条边界，别在起容器之后才发现：**库只能是 MySQL 8.0/8.4**（`V1__init.sql` 用了
`utf8mb4_0900_ai_ci` 与 `FULLTEXT … WITH PARSER ngram`，MariaDB 与 MySQL 5.7 过不去），
而 **Hub 上那份镜像只有 linux/amd64**（发布工作流没传 `platforms`），ARM 机型要自己 `docker build`。

#### 安装页上有哪些项

| 分组 | 项 | 说明 |
|------|------|------|
| 数据库 | 地址 / 端口 / 库名 / 用户名 / 密码 | 五段分开填，JDBC URL 由服务端拼（那一整条串是最容易填错的一项）。旁边有「测试连接」按钮，先看到"这个地址/口令对不对"再提交整份配置 |
| 存储 | **启用对象存储**（开关） | 关 = 本地目录，图片落 `/app/data/media`，零依赖；开 = 下面那几项必填 |
| 存储 | 地址 / 区域 / 桶名 / Access Key / Secret Key / Path Style | Path Style 默认开，MinIO / RustFS 是对的；接阿里云 OSS 或 AWS 时按服务商文档关掉 |
| 存储 | **加密存储**（开关）+ 加密口令 | 关 = 明文；开 = 每对象客户钥（SSE-C），口令由 Argon2id 派生出 KEK，盐与校验值存 `app_state`。口令至少 12 个字符 |
| 存储 | 主钥（高级项） | base64 的 32 字节（`openssl rand -base64 32`），与口令**二选一**，同时填安装页会拒绝——两者派生出的密钥不同，无法判断该用哪一把 |
| 存储 | 图片抓取放行主机 | 这一项管的是图片**从哪抓**，不是存哪里：抓取器默认拒绝一切回环 / 私网 / CGNAT 地址（防 SSRF），所以图床在内网时必须在这里点名。写 `host` 或 `host:port`，逗号分隔，**精确等值**匹配，不带 `http://` 与路径，也没有通配。漏填的表现是每条图片都静默失败（`media_objects.error=ssrf_blocked`），客户端只看到图不见了。与它相对的"整段放开私网"（`pb.media.allow-private`）刻意**不在表单上** |
| 管理台 | 登录口令 | **留空 = 管理台整个不开放**（登录回 403「管理台未启用」），收发消息不受影响，这是安全状态不是漏配。JWT 签名钥不用你填，服务端自动生成并回写配置文件 |
| 运维 | 网段白名单 | `/healthz` 与 `/actuator/**` 的额外放行网段，环回永远放行。留空 = 只有容器自己能读 |
| 其它 | 服务端口 | 默认 7404。容器内改端口要同步改 `-p` 的右侧与 Caddyfile 的上游，所以**通常别改**——要换端口就换宿主侧的映射 |

提交时它会**真连一次库和桶**（桶那一步是真 putObject + deleteObject），任何一项不过就不写文件、把具体原因回给你。
所以"配了一半"这种状态在新模型下不存在：要么这份配置能跑，要么它根本没落盘。

**KEK 一旦有对象落库就不可轮换**——换了口令或主钥，之前写进去的图片再也解不开。这一条在表单上没法拦，只能写在这里。

三条不能省的：

- **`/app/data` 那个卷要挂**。配置、图片、日志全在这一个目录里，不挂等于写在容器可写层，`docker rm` 之后全没。
- **`/app/data` 归 uid 10001**。绑定挂载会把宿主目录的属主带进容器，宿主目录不是它可写时，
  entrypoint 的写入探针当场拒绝启动并把两种修法打出来（改用 named volume，或 `chown -R 10001:10001`）。
- **端口以 `-p` 的左侧为准**，容器内固定 7404；`EXPOSE` 只是元数据。

> 想自己出一份（比如机器在 ARM 上）：`docker build -f infra/Dockerfile -t pushbox:local .`
> ——这条要克隆仓库，构建读的是仓库里的 `Dockerfile`，跟"跑起来"要的东西不是一回事。

**重装或换机器**：安装页上有「从库里找回上一次的配置」，填对库凭据就能把整份配置拉回来
（副本存在 `app_state.runtime_config_v1`）。不想用网页也可以直接写文件，模板见
[infra/pushbox.json.example](infra/pushbox.json.example)，位置用 `--pb.config-file=/path/to/pushbox.json` 指定。

**只在 NAS 的图形界面里装（飞牛 / 群晖 / 1Panel，没有 `.env` 那一层）？**
走 **[infra/NAS部署指南.md](infra/NAS部署指南.md)** + **[infra/docker-compose.nas.yml](infra/docker-compose.nas.yml)**：
那本文件整本零插值，改一行端口映射就能起，剩下的全在安装页上填。

### 形态二：compose 完整生产形态（要反代 / 监控 / 自带库和桶时）

**为什么这一条要 `git clone` 而上面不用**：`docker-compose.yml`、`Caddyfile` 和那几段 sidecar 定义都是
**文件**，装不进 `pushbox` 镜像里。只要一个容器时这些都用不到，所以形态一既不克隆仓库也不碰 `.env`。
要 Caddy 反代 + HTTPS、要 Prometheus/Grafana、或者想让 compose 顺手把 MySQL 与 RustFS 一起起来，才需要形态二。

**`.env` 现在只装第三方容器的口令**：服务端的配置一项都不在里面，它只在安装页上填。

#### 前置条件

- Docker 20.10+ 和 Docker Compose v2+
- 至少 2GB 内存 + 10GB 磁盘空间
- 一个域名（可选，用于 HTTPS）

#### 步骤 1: 克隆仓库并准备 .env

```bash
git clone https://github.com/iamplusdev/push-box.git
cd push-box/infra

# 模板里只有反代域名、宿主端口，以及拓扑 B/监控那几个第三方容器的口令
cp env/prod.env.example .env
```

只起服务端 + Caddy（拓扑 A，库和桶都是你现成的）时，`.env` 里**必填的只有 `PB_CADDY_HOST`**；
`MYSQL_ROOT_PASSWORD` / `PB_DB_PASSWORD` / `PB_S3_*` / `GRAFANA_ADMIN_PASSWORD` 分别只在你取消注释
mysql、rustfs 服务段或挂上监控那本文件时才需要——它们喂的是**那些第三方镜像**，服务端一个都不读。

#### 步骤 2: 启动服务

```bash
# 基础部署（仅 PushBox + Caddy 反代）
docker compose up -d

# 带监控栈部署（Prometheus + Grafana）——监控服务在单独一本文件里
docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up -d

# 查看日志
docker compose logs -f pushbox-server
```

Caddy 的 `depends_on: service_healthy` 不会被安装页卡住：没配置完的容器停在安装页上，那时 `/healthz`
根本不存在，但 7404 已经有人监听——所以 healthcheck 判的是**端口通不通**，不是 `/healthz`。

#### 步骤 3: 在浏览器里完成安装

打开 `http://<PB_CADDY_HOST>/`（或 `http://127.0.0.1:7404/`），填表、提交。安装页也走同一条反代，
不需要额外的端口或路径规则。各项含义见上面那张表。

安装口令在服务端容器日志里：`docker compose logs pushbox-server`（或 `docker logs` 对应容器），
找到「安装口令」那串 32 位字符粘到安装页顶部。

用 compose 自带的 mysql/rustfs（拓扑 B）时，表单里填 `mysql` 与 `http://rustfs:9000` ——
同一网络内用服务名，不要填宿主 IP。桶要**先建好**：镜像不建桶，安装页的连通性检查会真写一个对象再删掉，
桶不存在就直接报回来。

首次启动需要 1-2 分钟（Flyway 数据库迁移 + Spring Boot 初始化）。

#### 步骤 4: 验证部署

```bash
# 健康检查：在容器里取（/healthz 只认容器环回，从宿主打过去是 404，见下面那条说明）
docker compose exec pushbox-server bash -c 'exec 3<>/dev/tcp/127.0.0.1/7404 && echo up'
docker compose exec pushbox-server curl -s http://127.0.0.1:7404/healthz

# 预期最后一段：
# {"ok":true,"db":"up","storage":"up","uptime_ms":...}
```

`curl http://localhost:7404/healthz` 在**宿主上**拿不到这个结果：`InternalAccessFilter` 只看 `getRemoteAddr()`，
经端口映射进来的连接在容器里是网桥网关地址，会被回 **404**（拒绝时刻意不用 403，免得扫描器确认这里有个运维端点）。
要让宿主或监控直接读到它，就在安装页的「运维网段」里把这套 compose 网络分到的子网填进去
（值落在配置文件的 `security.internalCidrs`）。

访问浏览器：
- **Web 管理台**: `https://<PB_CADDY_HOST>/`——注意是**根路径**，不是 `/admin`。管理台 SPA 与 API 打在同一个镜像、由同一个进程在同一个端口上吐出来（`infra/Dockerfile` 的 node 阶段把 `apps/web-admin/dist` 拷进 jar 的 `classpath:/static/`，`SpaWebConfig` 负责 history 路由的深链回退），所以没有第二个端口、也没有独立的 nginx。用安装页里填的那个管理台口令登录；自签证书要先信任 `infra/ca` 里那根 CA，或改用 `docker-compose.nas.yml` 那条直连 7404 的内网明文路径。
- **运维端点**: `/healthz` 与 `/actuator/**` 只在容器环回、以及安装页「运维网段」里填的网段可读；Caddy 另外只 403 掉 `/actuator/*`。

#### 步骤 5: 换成自己的域名 / 证书（可选）

主机名只在 `.env` 里改一处：

```bash
PB_CADDY_HOST=pushbox.example.com
```

证书两种口径，`infra/Caddyfile` 顶部注释里都写了：

1. **内部自签 CA**（默认）：用 `infra/ca/gen-ca.sh` 签发（域名走 `PUSHBOX_HOST`，纯 IP 走 `PUSHBOX_IP`，SAN 必须包含客户端实际连的那个地址），把 `pushbox.crt` / `pushbox.key` 放进 `infra/certs/`，再把根 CA 装进各端信任（Windows 需要 `-user Root`，Android 需要用户在系统里安装）。
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
配置、图片与日志都还在，别用 `docker rm -v`：

```bash
docker logs -f pushbox
docker restart pushbox
docker volume ls | grep pushbox          # pushbox_data 那一个卷（配置 + 图片 + 日志），docker rm 不会带走它

# 升级：先取新镜像，删容器不会删卷，再用同一条 docker run 起一次
docker pull docker.io/iamplusdev/push-box:latest
docker rm -f pushbox
```

**改配置**：装完之后再想改，直接编辑 `/app/data/config/pushbox.json`（宿主上就是那个卷里的同名文件）然后重启容器。
把那个文件删掉，下次启动就回到安装页——库里的副本还在，用「从库里找回上一次的配置」能把原来那份拉回来，
不用重填一遍。注意**加密口令与主钥不能这么换**：KEK 一旦有对象落库就不可轮换，改了旧图片再也解不开。

### 故障排查

**问题 1: 容器启动后立即退出**

```bash
docker compose logs pushbox-server    # 形态一用 docker logs pushbox

# entrypoint 只剩一条判据，它的报错自己就带修法：
# - /app/data 不可写（绑到宿主目录时属主通常是 root，而容器进程是 uid 10001）
#   → 改用 named volume，或 chown -R 10001:10001 <你挂给 /app/data 的那个宿主目录>
#
# 配置类的错不再让容器退出：它们在安装页上就当场报出来了，落不了盘。
```

**问题 2: 打开 7404 看到的还是安装页**

说明配置文件不在进程的工作目录下。容器里它固定在 `/app/data/config/pushbox.json`；
如果 `/app/data` 挂的是宿主目录而属主不对，写入会失败（安装页会把这句原话回给你）。
装完一次之后安装页就不再出现——它只在配置文件不存在时启动。

**问题 3: 健康检查一直显示 db=down**

`db=down` 是 `/healthz` 真去借了一条连接、那条连接没起来。先在容器里取一次（`/healthz` 只认容器环回，
从宿主打过去是 **404**——`InternalAccessFilter` 只看 `getRemoteAddr()`，经端口映射进来的连接在容器里
看到的是网桥网关；拒绝时刻意不用 403，免得扫描器确认这里有个运维端点）：

```bash
docker compose exec pushbox-server curl -s http://127.0.0.1:7404/healthz    # 形态一：docker exec pushbox curl -s …
docker compose logs pushbox-server | grep -iE 'HikariPool|Access denied|Communications link|to database' | head
```

那两条已经能把"口令错 / 地址错 / 库名错"分开；**别在容器里现装 mysql 客户端**（容器跑 uid 10001，
`apt-get update` 直接 `Permission denied`）。要手工敲 SQL 就去库所在那台机器上，详见
[infra/部署快速参考.md](infra/部署快速参考.md) 的排查段。

改配置的办法就是上面那条：编辑 `/app/data/config/pushbox.json` 再 `docker restart`，
或者删掉它回安装页重填（安装页的「测试连接」按钮能先把口令对不对判清楚）。

**问题 4: Web 管理台返回 403**

先看是不是根本没开：安装页里**管理台口令留空是合法状态**，管理台整个不开放，
一律回 403「管理台未启用」，客户端收发照旧。要开就在那个字段填一个至少 8 个字符的口令
（JWT 签名钥由服务端自动生成，不用你管）。口令本身不对时同理——403 不代表服务坏了。

---

## 一分钟上手（本机联调）

前置：JDK 21、MySQL 8（`ngram_token_size=2`）、Node 18+、Maven。**不需要对象存储**——安装页里不勾就是本地文件存储，图片落 `./data/media`，要验桶形态再勾上。

```bash
# 1) 构建（机器默认 JAVA_HOME 常是 JDK 8，必须显式覆盖）
JAVA_HOME=/path/to/jdk-21 mvn -B -DskipTests package

# 2) 起服务端。第一次启动没有配置文件，它自己在 7404 上开一个安装页然后停在那里
bash infra/run-dev.sh
```

浏览器打开 http://127.0.0.1:7404/ ，在表单里填库地址/端口/库名/用户名/密码，勾不勾对象存储，管理台口令，加密口令。提交前它会**真连一次库和桶**，连不上就当场说清是哪一项。提交成功后配置写进 `apps/server/data/config/pushbox.json`（这个目录已 gitignore——里面有明文口令），**同一个进程接着启动 Spring，不需要重启**；服务端就绪后安装页自动跳到管理台（端口框改了的话会提示去新地址，而不是往旧端口跳）。之后再启动就直接读那份文件、不再出现安装页。

```bash
# 3) 验收探针
node infra/probe/p0-e2e.mjs
```

要重装或换机器：安装页上有「从库里找回上一次的配置」，填对库凭据就能把整份配置拉回来（配置副本存在 `app_state.runtime_config_v1`）。想指另一份配置文件用 `--pb.config-file=/path/to/pushbox.json`，模板见 `infra/pushbox.json.example`。

发一条消息试试：

```bash
curl -sS http://127.0.0.1:7404/message -H 'Content-Type: application/json' \
  --data-binary @payload.json
```

接口参考按鉴权分成三页，别混用（[入站 API](https://iamplusdev.github.io/push-box/api.html) 写消息进来 / [客户端 API](https://iamplusdev.github.io/push-box/client-api.html) 设备读自己的 Space / [管理台 API](https://iamplusdev.github.io/push-box/admin-api.html) 一条口令换 24h JWT、跨 Space 运维）；协议与数据模型的权威描述在 [软件设计方案](docs/软件设计方案.md)。页面源码在仓库的 `site/`，线上地址是 [官网](https://iamplusdev.github.io/push-box/)。

---

## 仓库地图

```
apps/
  server/     Spring Boot 服务端：入站 API、设备与频道、WS 下发、抓取转存、搜索、治理
  web-admin/  Web 管理台（Vue 3 + Element Plus），打进服务端 jar 的 classpath:/static/
  android/    Android 客户端（Kotlin + Compose），Gradle 工程
  windows/    Windows 客户端（.NET 8 + WPF）
packages/
  core/       Java 加密与协议库（X25519 / Ed25519 / AES-GCM 帧通道 / canonical 签名）
infra/        探针、Dockerfile、compose、Caddyfile、自签 CA、备份脚本
assets/       多端共用的图标与导出稿（Android 自适应图标、Web favicon、商店图）
contracts/    跨端契约；testvectors/ 由 core 单测产出，供 Windows 侧逐字节对账
docs/         设计文档、用户指南、开发指南；docs/release/ 放发布清单与准备报告
site/         官网静态站点源码（零构建，可直接 file:// 打开；线上地址 https://iamplusdev.github.io/push-box/）
scripts/      构建与发版脚本（release*.sh、set-version.sh、install-page-smoke.sh）
output/       各端编译产物（jar / apk / exe），不入库，由 release 脚本写入
```

`packages/core/` 与 `apps/server/` 是同一个 Maven reactor；`apps/android/` 与 `apps/windows/` 各有独立构建体系。三端**不是三份协议实现**：Java 与 Kotlin 共享同一份 `packages/core` 源码，C# 靠 `contracts/testvectors/vectors.json` 逐字节对账。

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
| `JAVA_HOME=… mvn -B -pl :pushbox-core,:pushbox-server test` | 服务端与加密库（**375/375**，core 50 + server 325）。不再 source 任何环境变量：配置读的是 surefire 工作目录下的 `apps/server/data/config/pushbox.json`（就是安装页写出的那份）。分两档门控——只需连得上库的 14 个类挂 `databaseReady`，开发机上照跑；要真桶的 media 5 个类挂 `objectStorageReady`，本地存储模式下跳过。所以本机是 **342 执行 + 33 跳过**，而 `mvn test` 的退出码仍然是 0 |
| `node infra/probe/p0-e2e.mjs` | 端到端协议（**0 失败**；条数以脚本末行的 `N/N` 为准） |
| `cd apps/android && ./gradlew :app:assembleDebug` | Android 编译通过 |
| `cd apps/web-admin && npm ci && npm run build` | 管理台 `vue-tsc` 类型检查与 `dist/` 产物 |
| `dotnet test apps/windows/PushBox.sln` | C# 协议向量 + 客户端逻辑（**676/676**） |
| `dotnet run --project apps/windows/PushBox.LiveCheck` | PC 端真服务端判据（**12/12**） |

---

## 铁律（改代码前先读）

1. **凭据只落在一个文件里，那个文件绝不入库。** 库口令、Access/Secret Key、加密口令与管理台口令全在
   `data/config/pushbox.json`（容器里 `/app/data/config/pushbox.json`），容器内权限 600。
   明文是刻意的——生产部署一定要有可复核的凭据落点，而"只从环境变量读"换来的是一堆占位值和一个拒绝启动的
   入口脚本，部署者仍然要把真值写在某个文件里（compose 的 `environment`、NAS 的表单），只是那个文件没人管权限。
   所以判据从"配置里不许有明文"换成"**明文只在这一个地方，且它进不了提交**"：`/data/` 与 `/server/data/`
   都在 `.gitignore` 里，`release-verify.sh` 除了查规则还在，还会拿 `git status` 问一次这两个目录有没有被收进去。
2. **正文不进日志。** 入站 body、`content`、`summary`、搜索结果片段都不得出现在任何 appender 输出里。
3. **绝不绕过证书校验。** 找不到 `ServerCertificateCustomValidationCallback` 等钩子是设计如此。
4. **已读游标只增不减。** 客户端必须自己保 `MAX(...)`，否则会把别的端重新点亮红点。
5. **改了协议字段要三处同步：** `packages/core/src/main/java/dev/pushbox/core/protocol/Frames.java`、`apps/android/app/src/main/java/cn/iamplus/pushbox/net/Wire.kt`、`infra/probe/p0-e2e.mjs`。

---

## 信任边界

- **服务端→客户端加密**：每个设备一对密钥，WS 帧走 AES-256-GCM 通道，REST 响应走一次性 sealed-box。
- **服务端库里是明文**：这是刻意的，只有这样搜索、摘要、抓图才能工作。
- **端到端加密已被否决**：代价是"服务端不能搜索、不能生成摘要、不能抓图"。
- **投递单位是"设备"不是"Space"**：同一个 `device_id` 的新连接会顶掉旧的那条——把身份文件拷到另一台机器得到的是"轮流持有同一条连接"，不是"两台设备"。
- **Web 管理台面和业务 API 挂在同一个端口上，默认公网可达**：`/api/v1/admin/*` 前面只有登录口令加一把 24 小时的 HS256 JWT；`infra/Caddyfile` 只 403 掉 `/actuator/*`，服务端 `InternalAccessFilter` 的白名单也只覆盖 `/healthz` 与 `/actuator/**`。所以"管理台在内网"不是软件给的保证，是部署者要做的事（收紧办法见 [SECURITY.md](SECURITY.md) 的「部署侧该自己做的事」与 `infra/Caddyfile` 里那段注释示例）。
- **那把管理台 JWT 不能刷新、也不能吊销**：签名钥是配置文件里独立的 `admin.jwtKey`（安装页不让你填，服务端自动生成并回写），所以改登录口令 `admin.password` 不会让已签出去的 token 失效；能做的事只有等它按 `exp` 自然过期（≤24h），或换掉 `admin.jwtKey`（代价是所有会话都要重新登录）。口令被刷的按 IP 锁定是**进程内 Caffeine**（`AbuseControlService.adminLoginAttempts`，代码注释里写明了"登录没有对应的库行可写"）——重启清零、多实例不共享。

详见 [软件设计方案 §1](docs/软件设计方案.md)。

---

## 持续集成（GitHub Actions）

三条发布链互相独立：服务端、Android、Windows 各有自己的版本号与标签前缀（`server-v*` / `android-v*` /
`windows-v*`），**只改服务端就只打 `server-v*`，两条客户端流水线不会被触发**；旧的 `v*` 标签仍然保留，
一次发三端。**这三条链只认标签，不认分支**：推 `main` 不会重出一份要发给用户下载的 APK/MSI，也不会往
Docker Hub 写任何东西——改代码能不能编、契约有没有被改坏，是 `ci.yml` 的事。改动含 `site/**` 的 `main`
推送还会把官网发到 GitHub Pages（线上地址 <https://iamplusdev.github.io/push-box/>）。六个工作流都在
`.github/workflows/`，其中 `ci.yml` 是独立于发布的门禁（真 MySQL + 真对象存储，末尾断言"闸门用例一条都
没被跳过"），每次推 `main` 都跑全量——它判的是三端契约有没有被改坏，不跟着发布链走。

**分支只有一个**：日常提交与评审都在 `main`（GitHub 上的默认分支也是它），`gh-pages` 是 `site-deploy.yml`
写出去的站点分支、不手工提交，历史血脉放在本地 `archive/legacy-history` 与 `archive/orphan-old-line`
（不推远端）。以前还有一条 `publish` 用来"代码先上远端、流水线不动"，2026-10-23 已删——那个用途现在由
"出制品的三条流没有分支触发"直接满足，不需要一条独立分支来躲 CI。

| 工作流 | 触发 | 产出 |
|--------|------|------|
| `docker-publish.yml` | `server-v*` 或 `v*` 标签 / 手动 dispatch（**没有分支触发**：推 `main` 不碰这条链） | buildx 多阶段构建（含 Web 管理台）并推 `iamplusdev/push-box`：**只有标签事件才推 Hub**，打的是本次的 `X.Y.Z` / `X.Y` / `latest`；分支上 dispatch 一律只构建（它仍要能验 `DOCKERHUB_*` 登录与 Dockerfile 编不编得出来），导出目标用一条只活在 runner 本地的占位标签，Hub 上不会多任何东西。**回滚点就是留在 Hub 上的历史版本标签**，不再靠一个每次构建都移动的 `main`。构建前先跑版本闸门——tag、`pom.xml`、compose 三者版本号不一致就直接红，不会推出去一个自称某版、里面跑着另一版的镜像。手动触发默认是 **dry-run**（只构建不推）。**`v*` 标签事件首跑 2026-10-22 绿**（run `39724347017`，tag `v1.1.0`）：「版本号一致性闸门」「Docker metadata」「Build and push」「本次的标签清单」四步全 success，即 `1.1.0`/`1.1`/`latest` 三个标签随 buildx 一起推出。Hub 标签清单已用 WSL 里的 Hub 凭据实查（Windows 宿主仍连不上 `hub.docker.com`，WSL 可连）：当页是 `1.1.5` / `1.1` / `latest` / `main` / `sha-a478f0e` / `1.1.4` / `sha-2d8f3b0` / `1.1.3` / `sha-d8c1486` / `sha-d4a889d`——这次没有删旧版本标签。**组件标签首跑 2026-10-22 亦绿**（run `39763487572`，tag `server-v1.1.2`，推出 `1.1.2` / `1.1` / `latest`）；**v1.1.3 补丁链同日绿**（run `39767681288`，tag `server-v1.1.3`，推出 `1.1.3` / `1.1` / `latest`，Hub config blob 复核 `Volumes` 为两个持久化目录）；**v1.1.4 同日绿**（run `39779506621`，tag `server-v1.1.4`，推出 `1.1.4` / `1.1` / `latest`，首次带上 GUI 占位 ENV；该镜像有一处"主钥被占位口令误杀"的缺陷）；**v1.1.5 修复链同日绿**（run `39780463627`，tag `server-v1.1.5`，推出 `1.1.5` / `1.1` / `latest`，从 Hub 拉回镜像复核 entrypoint 与源码渲染结果 md5 一致）。**2026-10-23 追加两处订正**：① 上面那份「当页是 … `main` / `sha-a478f0e` …」是当时的实查快照，之后这条链做了两件事——`type=ref,event=branch` 与 `type=sha` 已从 metadata 里删掉（见本节开头），旧标签也被手动清走；本次订正时用 WSL 复查 Hub 的 tags 接口得到 `count: 0`、`main` 单查 404，也就是**仓库一个标签都没有**，`infra/docker-compose.yml` 钉的 `:1.1.6` 暂时拉不到，要等下一次标签事件（或在 `server-v1.1.6` 上点一次不勾 dry-run 的 dispatch）才补得回来。② 同一个 commit `d8d6476` 在 tag 事件 / 分支事件 / 手动 dispatch 下量到三个不同 digest（`ca14f281…` / `d9b35cea…` / `90bed7ef…`），根因是 buildx 的 gha 层缓存复用未变动的阶段、jar 时间戳因此不同，**镜像本来就不可复现**；判断"这个镜像里是哪份代码"要读 OCI label `org.opencontainers.image.revision`，别看标签名，也别拿 digest 当"内容变了"的证据 |
| `build-apk.yml` | `android-v*` 或 `v*` 标签 / 手动 dispatch（**没有分支触发**：APK 是要挂上 Release 给人下载的制品，每次合并重打没有意义；Android 能不能编由 `ci.yml` 的「Android 编译」job 每次推 `main` 判） | 只出 `assembleRelease`（R8 + 签名），用 `apksigner verify` 把关后上传 `pushbox-release-*.apk`；标签时发 Release。没配 keystore 时构建直接失败，不再退化成"发一只 debug 包"。**标签发 Release 首跑 2026-10-22 绿**（run `39724346953`）：`Verify release APK signature` 与 `Publish to GitHub Release` 两步 success，资产 `pushbox-release-v1.1.0.apk`（12.8 MB）已挂到 Release 页；**组件标签首跑同日绿**（run `39763487166`，`android-v1.1.2`，资产 `pushbox-release-1.1.2.apk`） |
| `build-msi.yml` | `windows-v*` 或 `v*` 标签 / 手动 dispatch（**没有分支触发**：安装包同理；C# 能不能编由 `ci.yml` 的 Windows job 里新增的 `dotnet build 主工程` 那一步判） | `windows-latest` 上 `dotnet publish self-contained win-x64` + Inno Setup 出 `setup.exe`，上传 artifact；标签时发 Release。**标签首跑 2026-10-22 绿**（run `39724347008`），资产 `PushBox-Setup-1.1.0-win-x64.exe`（58.7 MB）；`Sign installer` 仍 skipped——**安装包未签名**（`CODESIGN_*` 那对 secrets 未配）；**组件标签首跑同日绿**（run `39763487499`，`windows-v1.1.2`，资产 `PushBox-Setup-1.1.2-win-x64.exe`） |
| `update-dockerhub-description.yml` | push main 且改动含 `README.md`（带 `paths:` 过滤的两个之一，另一个是 `site-deploy.yml`） | 把 `README.md` 同步为 Docker Hub 仓库说明。**2026-10-22 第一次跑绿**（run `39720441754`，push `main`、head `ab5c9fb7`，改动含 `README.md`）。此前它空了一整版：唯一那次 `main` 推送是"新建分支"（`before` 是全零），GitHub 算不出改动文件集，带 `paths:` 的它被跳过、另外四个照跑；因为从没被登记过，连网页上的「Run workflow」都不存在，只能等一次改到 `README.md` 的常规推送（排查过程与四条证据见 `发布检查清单.md`「Hub 仓库说明」）。**Hub 上的说明正文本机没复核到**——`hub.docker.com` 从这台机器三条路都是超时，这一句只有 CI 那一步的退出码背书 |
| `site-deploy.yml` | push main 且改动含 `site/**` / 手动 | 把 `site/` 那棵目录树原样发到一条**只装站点**的分支 `gh-pages`（分支的根树 == `site/` 的树，没有源码也没有构建产物），Pages 以它为源。没有构建步骤，用 `git commit-tree` + push 完成，只依赖 `actions/checkout`。**内容没变就不发**：`paths:` 挡掉"只改代码"的推送，树哈希比较再挡掉"同一份 site/ 被带进来两次"和手动重跑（dispatch 不吃 `paths:`，所以这层不可省）。不挂任何标签——官网与版本号无关。**首跑已在 2026-10-22 兑现**：run `39720441735` 推出 `gh-pages`（根树等于那次推送的 `HEAD:site`），Pages 构建 `39720519087` success，`https://iamplusdev.github.io/push-box/` 实测 200，页面里全部资源都是相对路径所以子路径下不缺样式。**它不开 GitHub Pages**：源没配置时这条链只推分支、站点地址打不开，第一次要么在网页 Settings → Pages 把源设成 `gh-pages` / `/`，要么拿管理员 token 调一次 `POST /repos/<仓库>/pages` |

**2026-10-23 改口径**：以前"三条构建链在 `main` 上按自己的目录过滤"（改 `apps/server/**` 就跑
`docker-publish.yml`，改 `apps/windows/**` 就跑 `build-msi.yml`…）已经不成立了——`paths` 只是把触发面收窄，
并没有取消"推一次代码就重出一份制品"这件事，而现在三条链**根本没有分支触发**，只剩组件标签与手动
dispatch，所以 `paths` 一并删掉了（留着反而误导：它会让人以为 push 还能出包）。`packages/core/**` 是
Java 侧共享源码（`:pbcore` 直接编译它），它动了会不会连累两端，现在只在"打哪个标签"这一刻才有关联——
标签事件本来就不评估 `paths`（GitHub 对 tag 推送不算改动文件集；否则 tag 指到的提交恰好没碰这些目录
就会静默不发布）。`ci.yml` 每次都全量跑（`pull_request` 与推 `main`），那是门禁，不是发布。
带 `paths:` 过滤的只剩两条纯文本同步链：`site-deploy.yml`（`site/**`）与
`update-dockerhub-description.yml`（`README.md`）。`site-deploy.yml` 从非 `main` 分支手动触发且真要推上去时会被直接拒（Pages 只有一个地址，
没有预览地址可回退），要越过这道确认得显式勾上 `force_branch`；勾 `dry_run` 不拦，因为它一个字节都不推。

**要在仓库 Settings → Secrets and variables → Actions 配的密钥：**

- 必需：`DOCKERHUB_USERNAME`、`DOCKERHUB_TOKEN`（Docker Hub access token，不是登录密码）。
- 必需（APK 工作流）：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。release 签名由这四个环境变量驱动，缺任何一个 `assembleRelease` 就地失败——debug 包不能升级成 release 包（签名不符，`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），客户端也只认 `pushbox-release-*` 资产。
- 可选（Windows 代码签名）：`CODESIGN_CERT_BASE64`、`CODESIGN_PASSWORD`，见 [docs/代码签名指南.md](docs/代码签名指南.md)；不配则产物未签名、首启有 SmartScreen 提示。

### 应用内更新

打了 `android-v*` / `windows-v*` 标签（或旧的 `v*`）发布的 GitHub Release 就是两端「检查更新」的来源：
客户端读 `releases?per_page=30` 列表，按资产名（`pushbox-release-*.apk` / `PushBox-Setup-*-win-x64.exe`）
挑出属于自己平台的那一条，再从 tag 里去掉 `android-v` / `windows-v` / `v` 前缀得到版本号。**不能再用
`/releases/latest`**：分端发布后每个平台各占一个 Release，那个接口只返回"最后发的那个"，另一平台会读到
别人的 tag 或看不到自己的新版本。发现新版**先弹一只确认框**（"发现新版本 X，是否下载并安装？→更新/稍后"），
**用户点确认才下载**——下载完 Android 拉系统安装面板让用户再确认一次，Windows 下
`PushBox-Setup-*-win-x64.exe` 后静默覆盖安装并提示重启；手动检查与冷启动发现都汇到同一只确认框。检查只在「冷启动静默查一次」和「设置里手动点检查」两处触发（未认证限额 60 次/小时/IP，不进轮询），冷启动那趟还受设置里「启动时自动检查更新」开关门控（默认开）。走的是公开 https，与自建的业务服务器无关。

两处 CI 专属适配（都不改本地开发用的文件）：`gradle-wrapper.properties` 已经写死官方 `services.gradle.org` 发行包，CI 上不再需要临时改写；APK 质量闸是"能编出来、签上名，且证书指纹等于 `apps/android/release-cert.sha256` 里钉的基线"——换 key 会让老用户卸载重装、历史消息全丢，所以这一步直接判红而不是只打印。服务端测试**已经进 CI 了**——`ci.yml` 用 service container 起真 MySQL 8.4（带 `--ngram_token_size=2`）与真 RustFS，并当场写一份 `apps/server/data/config/pushbox.json` 指过去；本机没有桶也能跑掉 299 条，只有真要桶的 33 条 media 用例跳过，而 CI 上跳过必须为 0。早先"因为依赖外部存储所以不放 CI"的说法已经作废。

> **2026-10-20 深夜：门禁这条链第一次在 GitHub runner 上全绿**——`ci.yml` 对 `publish` 的 dispatch（`run 39377846888`，head `60c86ad`）四个 job 全 success，其中「等 RustFS 就绪并建桶」与「断言 DB 闸门用例真的执行了」（`skipped == 0` 且用例数 ≥ 261）都是第一次真跑到；`build-apk.yml` 同日 dispatch（`run 39376573834`，同一个 head）也全绿，`Verify release APK signature` 这一步第一次真签出并验了名。**当天最后一次 dispatch（`run 39381072797`，head `5293801`）又把签名步升级成"证书 SHA-256 必须等于 `apps/android/release-cert.sha256` 里的基线"并且绿了**，所以"CI 出的包用的就是那把正式 key"是 CI 自己验过的事实，不再靠间接推断。**这一路是四次 dispatch 逐段红出来的，四个根因都不在本仓代码里**：建桶步原来拉的 `minio/mc` 镜像在 Docker Hub 已取不到（→ 404，`dl.min.io` 的 mc 二进制 → 410 Gone），改用 runner 预装的 AWS CLI v2；`android-actions/setup-android@v3` 要装同样已下架的 legacy `tools` 包，改用 runner 预装 SDK 加一步"现读 `compileSdk`、断言 platform 与 build-tools 在位"的入镜检查；KSP 插件 marker 报"六个仓库都找不到"的根因是 runner 打 `maven.aliyun.com` 的三个仓库**整片 502**（境外数据中心 IP 被拒，本机在墙内看不见，所以本地一直绿），现已改成默认只走官方源、开发机在 `apps/android/local.properties` 加 `pb.maven.cnMirror=true` 才插回镜像（见 `docs/开发指南.md`）；最后一步红在 `ANDROID_KEYSTORE_BASE64` 不是标准 base64（`Illegal base64 character 2d`，`2d` 就是 `-`，`certutil -encode` 的 PEM 包头产物就是这个形状），重存后通过，判据写在 `build-apk.yml` 文件头与 `发布检查清单.md`。
>
> **2026-10-22 收尾：上一条记的那两件"还没被证明"，这一版两件都兑现了**。一是 `main` 上 `ci.yml` 绿了——`MediaSsePipelineTest` 把 `pb.storage.type=s3` 写进自己的 `@SpringBootTest(properties=…)` 之后，head `f86eeac` 的 run `39722819597` 四个 job 全 success，第 8 步「断言 DB 闸门用例真的执行了」（`skipped == 0` 且用例数 ≥ 306）第一次在 `main` 上真跑到；下一笔 `4e2fd71` 的 run `39723738268` 同样全绿。**但 `main` 不是每次都绿**：紧跟着的 `7b148ea`（只改文档与两个 Release NOTE 模板，一个 C# 字节都没动）红在「Windows 端测试」——`dotnet test` 退 1，而唯一答得出"哪几条红了"的计数闸那一步当时被 skip，公开 annotation 只剩一句"exit code 1"；本机 554/554 复现不出（带 `TZ=UTC` 也是），下一笔 `f9b1c67`（run `39727438065`）又全绿。那一次的红因没有名字，出口已经修好了（`ci.yml` 那步改成 `if: always()` 并逐条写 annotation），过程记在 CHANGELOG 第二十九轮。二是 `v*` 标签那条发布路径第一次在 runner 上跑完并全绿：`build-apk.yml` run `39724346953`、`build-msi.yml` run `39724347008`、`docker-publish.yml` run `39724347017`（「版本号一致性闸门」与「本次的标签清单」两步都 success），Release 页 <https://github.com/iamplusdev/push-box/releases/tag/v1.1.0> 上两个资产——`pushbox-release-v1.1.0.apk`（12.8 MB，`Verify release APK signature` success，即证书 SHA-256 等于 `apps/android/release-cert.sha256` 钉的基线）与 `PushBox-Setup-1.1.0-win-x64.exe`（58.7 MB，`Sign installer` 仍 skipped，**未签名**）。**这一版同时暴露一条自己的 bug**：Release 正文首行把镜像写成 `iamplusdev/push-box:v1.1.0`，而 `docker-publish.yml` 的 metadata 推的是去掉 `v` 的 `1.1.0` / `1.1` / `latest`——照着正文 `docker pull` 会指向一个 Hub 上不存在的标签。两个构建工作流的模板都已改成从 `${TAG#v}` 推标签（本地把这两步的 `run` 脚本原样抽出来、`gh` 打桩执行，输出实测为 `1.1.0`），但**已经发出去的那一页正文改不掉**：这台机器没有 `gh` CLI、也没有可用的仓库 token，所以 `v1.1.0` 那页的首行至今仍是错的，从下一个 tag 起才对。**仍未被独立复核的两件**：① Hub 上实际的标签清单——`hub.docker.com` 与 `registry-1.docker.io` 从这台机器都是连接超时（`http=000`，不是 404），"三个版本标签已推上去"只有 CI 那两步的退出码背书；② Pages 站点没有人眼看过排版，手上的证据只有 HTTP 200 与"`gh-pages` 根树 == `HEAD:site` 树"这条字节级判据。哪个 workflow 跑过几次、最新一次结论如何，用 `发布检查清单.md` 里那段按 workflow 分别数的命令现查，不要抄条数。
>
> **2026-10-22 晚：分端发布兑现，旧包清空**。`server-v1.1.2` / `android-v1.1.2` / `windows-v1.1.2` 三条组件标签流水线各自跑绿（run `39763487572` / `39763487166` / `39763487499`），GitHub 上只留两个客户端 Release：`android-v1.1.2`（`pushbox-release-1.1.2.apk`，12.8 MB）、`windows-v1.1.2`（`PushBox-Setup-1.1.2-win-x64.exe`，58.7 MB，仍未签名）；服务端只推镜像。`v1.1.0` / `v1.1.1` 的 Release 与标签、本地 `v1.0.0` 标签已删，Hub 上只剩 `1.1.2` / `1.1` / `latest` / `main` / `sha-9f8e201`——上一段说的"v1.1.0 页面首行标签写错"随页面删除不复存在。更新检查用线上列表实测：Windows 客户端代码选中 `windows-v1.1.2`，Android 同一套选取口径选中 `android-v1.1.2`；两端在真机/桌面上的更新弹窗仍未见人点过，官网排版也仍未见人眼复核。
>
> **2026-10-22 晚补丁：v1.1.3 只修镜像挂载元数据**。飞牛「创建容器」的「存储位置」一栏按镜像的 `Config.Volumes` 渲染，v1.1.2 没声明 `VOLUME`，所以那一栏没有任何行。v1.1.3 在 `infra/Dockerfile` 声明 `/app/data/media` 与 `/app/logs`，发布后从 Hub 的 config blob 复核到 `Volumes` 正是这两项；`S3` 仍刻意不预置（AK/SK 是凭据，半套又会误导），要用就在表单里加七行（**`v1.1.4` 起改为把凭据键烘焙成 `__SET_ME__` 占位值，让表单直接显示可编辑行、entrypoint 拒绝未替换的占位值；`v1.1.3` 及更早仍是旧行为**）。`server-v1.1.3` 只触发服务端链（run `39767681288`），当时 Hub 上只剩 `1.1.3` / `1.1` / `latest` / `main` / `sha-d8c1486`，旧的 `1.1.2` 与 `sha-9f8e201` 已删。**边界**：飞牛表单本身仍没有实测，只验到镜像元数据；Android/Windows 版本仍是 1.1.2。
>
> **2026-10-22 再补两版：v1.1.4 上表单、v1.1.5 修主钥路径**。v1.1.4（run `39779506621`）把五枚凭据键以 `__SET_ME__` 烘焙进镜像 ENV，GUI 的「环境变量」页因此直接出现可改的行；发布后发现只配 `PB_S3_MASTER_KEY` 的用户会被那行占位口令误判成"两个来源同时配了"而拒绝启动。v1.1.5（run `39780463627`）在 entrypoint 的二选一检查前把占位值 `unset` 掉，并用 Hub 上的 `1.1.5` 镜像实跑过主钥场景（日志出"主钥长度验证通过"、没有"只能二选一"）；镜像内 `/app/entrypoint.sh` 与源码渲染结果的 md5 同为 `dd70ee6aca3bc69b39c71a1360ca30d2`。**Hub 上没有删旧标签**：`1.1.5` / `1.1` / `latest` / `main` / `sha-a478f0e` / `1.1.4` / `sha-2d8f3b0` / `1.1.3` / `sha-d8c1486` / `sha-d4a889d` 都在。**边界**：图形界面的实际渲染仍没有实机截图，只验到镜像 config；Android/Windows 版本仍是 1.1.2。
>
> **2026-10-23：v1.1.6 发出去过，同日整条发布线重置回 1.1.0**。上午 `bash scripts/release.sh --component=server --push --yes` 发了服务端 v1.1.6（tag `server-v1.1.6` @ `d8d6476`，Hub 上 `1.1.6` / `1.1` / `latest` 同 digest），这是安装页配置模型第一次随版本发布，Android / Windows 当时仍是 1.1.2。**当天下午这条线被主动撤销**：Hub 上的标签在此之前已被手动清光（WSL 复查 `count: 0`、五枚单查全 404），GitHub 上留着一段"号排在后面、镜像根本不在"的半截历史不如重来——三端版本号统一写回 **1.1.0**，历史 tag 与两条客户端 Release 由维护者删除，下一个真正发出去的版本就是 `server-v1.1.0` / `android-v1.1.0` / `windows-v1.1.0`。**这次重置的代价要写明白**：Android 的 `versionCode` 由 `major*10000 + minor*100 + patch` 推导，1.1.0 得 10100，比已发出的 1.1.2 的 10102 小，已装 1.1.2 的设备覆盖安装会撞 `INSTALL_FAILED_VERSION_DOWNGRADED`，**必须先卸载重装**。**读上面那几条 2026-10-22 记录的方式**：它们的 Hub 标签清单、Release 资产、run 号全是**当轮实查快照**，不代表现在的仓库状态；本轮实测（Maven 332 / dotnet 554 / 安装页冒烟 5-0 / 闸门 88-0）与执行顺序记在 CHANGELOG 第三十六轮。

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

**文档版本**：v1.1.0（2026-10-23 发布线重置：三端号统一写回 1.1.0，1.1.1–1.1.6 的历史 tag 与 Release 撤销，见 CHANGELOG 第三十六轮）  
**最后更新**：2026-10-23  
**主分支**：main  
**官网**：<https://iamplusdev.github.io/push-box/>（源码在 `site/`，改 `site/**` 推到 `main` 就自动发布）  
**仓库**：[GitHub](https://github.com/iamplusdev/push-box.git)（主仓，Issue 与 CI 都在这边）| [Gitee](https://gitee.com/iamplus/push-box.git)（手动同步的国内镜像，可能落后主仓）
