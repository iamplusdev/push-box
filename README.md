# PushBox

自托管的消息推送系统：任何程序（CI、告警、脚本、内网服务）用一条 `POST /message` 把消息投进来，PushBox 负责**存下来、抓图转存、加密下发**到你自己的 Android 手机与 Windows PC，通知在两端几乎同时出现（实测同机链路 49ms）。

不依赖厂商推送、不依赖第三方转发服务，服务端、数据、图片全在你自己的机器上。

## 快速开始

### 用户

- 📖 [用户使用手册](docs/用户使用手册.md) - 安装、配置、使用流程、常见问题
- 🌐 [官网](site/index.html) - 项目介绍与下载链接

### 开发者

- 🏗️ [软件设计方案](docs/PushBox-软件设计方案.md) - 架构、协议、加密、数据模型（权威技术参考）
- 💻 [开发指南](docs/开发指南.md) - 环境搭建、构建流程、调试技巧
- 🧪 [测试指南](docs/测试指南.md) - 单测、集成测试、端到端测试、真机走查清单
- 🔧 [Windows 开发环境安装教程](docs/Windows开发环境安装教程.md) - .NET SDK / Rider / Inno Setup 安装
- 📋 [更新日志](CHANGELOG.md) - 每个版本的改动记录

---

## 🐳 Docker 一键部署（推荐）

最简单的生产部署方式，5 分钟即可上线。

### 前置条件

- Docker 20.10+ 和 Docker Compose v2+
- 至少 2GB 内存 + 10GB 磁盘空间
- 一个域名（可选，用于 HTTPS）

### 步骤 1: 克隆仓库并准备配置

```bash
# 克隆代码
git clone https://github.com/iamplusdev/push-box.git
cd push-box/deploy

# 复制环境变量模板
cp env/prod.env.example .env
```

### 步骤 2: 生成密钥

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

`PB_ADMIN_SECRET` 是登录口令，`PB_ADMIN_JWT_SECRET` 是签 JWT 的密钥，两个都必须填且不能相同；只需单独记牢口令那一个（登录用），签名钥换掉等于作废全部已登录会话。

### 步骤 3: 确认地址与存储加密

模板里除密钥外的占位还需要按你的拓扑确认一次（`deploy/.env`）：

```bash
# 拓扑 A：复用已有的 MySQL / RustFS，把主机换成实际地址
PB_DB_URL=jdbc:mysql://<DB_HOST>:3306/pushbox?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8
PB_DB_USERNAME=pushbox
PB_S3_ENDPOINT=http://<S3_HOST>:7101
PB_S3_REGION=us-east-1
PB_S3_BUCKET=pushbox
PB_S3_PATH_STYLE=true

# 对象存储加密：prod 只认两种显式表态
PB_S3_OBJECT_ENCRYPT=sse-c        # 配 PB_S3_MASTER_KEY（base64 的 32 字节，落库后不可轮换）
# PB_S3_OBJECT_ENCRYPT=none       # 明文：必须同时打开下面的逃生阀，否则进程拒绝启动
# PB_ALLOW_PLAINTEXT_MEDIA=1

# ---- 拓扑 B：完全独立部署（取消注释，主机改成服务名）----
# PB_DB_URL=jdbc:mysql://mysql:3306/pushbox?...同上...
# PB_S3_ENDPOINT=http://rustfs:7101
# 还需要 MYSQL_ROOT_PASSWORD=<只在初始化用>（mysql / rustfs 服务段见 docker-compose.yml）

# ---- 拓扑 C：本地文件系统存储（零依赖单机部署）----
PB_STORAGE_TYPE=local
PB_STORAGE_LOCAL_PATH=/app/data/media
# PB_S3_OBJECT_ENCRYPT=none 或 sse-c + PB_S3_MASTER_KEY（与 S3 模式相同）

# ---- 监控（可选，多传一个 -f docker-compose.monitoring.yml）----
GRAFANA_ADMIN_PASSWORD=<改掉默认 admin 密码>
```

`useSSL=false` / `http://` 意味着 SQL 与对象字节在链路上明文可听：同机或同一容器网络内通常只能如此，跨主机请改成 `useSSL=true` 与 `https://`（模板注释里写了口径）。**本地存储模式**（`PB_STORAGE_TYPE=local`）无需配置任何 S3/MinIO/RustFS 服务，图片直接落盘到宿主目录，适合轻量级部署。

### 步骤 4: 启动服务

```bash
# 基础部署（仅 PushBox + Caddy 反代）
docker compose up -d

# 带监控栈部署（Prometheus + Grafana）——监控服务在单独一本文件里
docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up -d

# 查看日志
docker compose logs -f pushbox-server
```

首次启动需要 1-2 分钟（Flyway 数据库迁移 + Spring Boot 初始化）。

### 步骤 5: 验证部署

```bash
# 健康检查
curl http://localhost:7404/healthz

# 预期输出：
# {"status":"UP","db":"up","storage":"up"}
```

访问浏览器：
- **Web 管理台**: `https://<PB_CADDY_HOST>/admin`（用 `PB_ADMIN_SECRET` 登录；自签证书要先信任 `deploy/ca` 里那根 CA，或直接改用 `http://<服务器IP>:7404` 走内网明文——服务端只在环回/`PB_SECURITY_INTERNAL_CIDRS` 段放行 `/actuator`，公网打不开它）
- **健康检查**: 在服务端机器上 `curl http://127.0.0.1:7404/healthz`

### 步骤 6: 换成自己的域名 / 证书（可选）

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

### 故障排查

**问题 1: 容器启动后立即退出**

```bash
# 查看错误日志
docker compose logs pushbox-server

# 常见原因：
# - 环境变量未设置（检查 .env 文件）
# - 数据库连接失败（检查 PB_DB_URL 和密码）
# - S3 存储不可达（检查 PB_S3_ENDPOINT 和网络）
```

**问题 2: 健康检查一直显示 db=down**

```bash
# 进入容器检查数据库连接
docker compose exec pushbox-server bash
# 在容器内执行：
java -jar /app/app.jar --debug | grep "HikariPool"
```

**问题 3: Web 管理台返回 403**

检查 `PB_ADMIN_SECRET` 是否正确设置且非空。

---

## 一分钟上手（本机联调）

前置：JDK 21、MySQL 8（`ngram_token_size=2`）、S3 兼容桶（本项目用 RustFS）、Node 18+、Maven。

```bash
# 1) 凭据只走环境变量，值只写在 deploy/env/local.env（已 gitignore）
cp deploy/env/prod.env.example deploy/env/local.env   # 填 PB_DB_PASSWORD / PB_S3_*

# 2) 构建（机器默认 JAVA_HOME 常是 JDK 8，必须显式覆盖）
JAVA_HOME=/path/to/jdk-21 mvn -B -DskipTests package

# 3) 起服务端（默认 8080；脚本自己 source local.env）
bash deploy/run-dev.sh

# 4) 验收探针
node deploy/probe/p0-e2e.mjs
```

发一条消息试试：

```bash
curl -sS http://127.0.0.1:7404/message -H 'Content-Type: application/json' \
  --data-binary @payload.json
```

详细 API 见 [软件设计方案 §5.1](docs/PushBox-软件设计方案.md)。

---

## 仓库地图

```
core/       Java 加密与协议库（X25519 / Ed25519 / AES-GCM 帧通道 / canonical 签名）
server/     Spring Boot 服务端：入站 API、设备与频道、WS 下发、抓取转存、搜索、治理
android/    Android 客户端（Kotlin + Compose），Gradle 工程
windows/    Windows 客户端（.NET 8 + WPF）
deploy/     探针、Dockerfile、compose、Caddyfile、自签 CA、备份脚本
docs/       设计文档、用户指南、开发指南
site/       官网静态站点（零构建，可直接 file:// 打开）
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
| `JAVA_HOME=… mvn -B -pl core,server test` | 服务端与加密库（**261/261**，core 38 + server 223）。要先 `source deploy/env/local.env`：112 条挂 `PB_S3_*` 门控，缺凭据时整批静默 skip，而 `mvn test` 的退出码仍然是 0 |
| `node deploy/probe/p0-e2e.mjs` | 端到端协议（**0 失败**；条数以脚本末行的 `N/N` 为准） |
| `cd android && ./gradlew :app:assembleDebug` | Android 编译通过 |
| `cd web-admin && npm ci && npm run build` | 管理台 `vue-tsc` 类型检查与 `dist/` 产物 |
| `dotnet test windows/PushBox.sln` | C# 协议向量 + 客户端逻辑（**556/556**） |
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

详见 [软件设计方案 §1](docs/PushBox-软件设计方案.md)。

---

## 持续集成（GitHub Actions）

推到 `main` 自动出各端产物、推 Docker Hub；打 `v*` 标签额外发布 GitHub Release。五个工作流都在
`.github/workflows/`，其中 `ci.yml` 是 PR 门禁（真 MySQL + 真对象存储，末尾断言"闸门用例一条都没被跳过"）。

| 工作流 | 触发 | 产出 |
|--------|------|------|
| `docker-publish.yml` | push main / `v*` 标签 | buildx 多阶段构建（含 Web 管理台）并推 `iamplusdev/push-box`，打 `latest`/`sha-*`/semver 标签 |
| `build-apk.yml` | push main / `v*` 标签 | 只出 `assembleRelease`（R8 + 签名），用 `apksigner verify` 把关后上传 `pushbox-release-*.apk`；标签时发 Release。没配 keystore 时构建直接失败，不再退化成"发一只 debug 包" |
| `build-msi.yml` | push main / `v*` 标签 | `windows-latest` 上 `dotnet publish self-contained win-x64` + Inno Setup 出 `setup.exe`，上传 artifact；标签时发 Release |
| `update-dockerhub-description.yml` | push main 且改动含 `README.md`（这是五个工作流里**唯一**带 `paths:` 过滤的） | 把 `README.md` 同步为 Docker Hub 仓库说明 |

前四个工作流没有 `paths:` 过滤：只要落到 `main` 或 `v*` 标签，哪怕只改一个像素也会整套跑一遍。**推其它分支名（例如发布前的 `publish` 分支）不触发任何工作流**，这也是"代码先上远端、CI 暂时不动"能成立的原因。

**要在仓库 Settings → Secrets and variables → Actions 配的密钥：**

- 必需：`DOCKERHUB_USERNAME`、`DOCKERHUB_TOKEN`（Docker Hub access token，不是登录密码）。
- 必需（APK 工作流）：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。release 签名由这四个环境变量驱动，缺任何一个 `assembleRelease` 就地失败——debug 包不能升级成 release 包（签名不符，`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），客户端也只认 `pushbox-release-*` 资产。
- 可选（Windows 代码签名）：`CODESIGN_CERT_BASE64`、`CODESIGN_PASSWORD`，见 [docs/CODESIGN-GUIDE.md](docs/CODESIGN-GUIDE.md)；不配则产物未签名、首启有 SmartScreen 提示。

### 应用内更新

打了 `v*` 标签发布的 GitHub Release 就是各端「检查更新」的来源：Android 与 Windows 客户端读 `api.github.com/repos/iamplusdev/push-box/releases/latest`，比对 `versionName`/tag 的 semver，发现新版**先弹一只确认框**（"发现新版本 X，是否下载并安装？→更新/稍后"），**用户点确认才下载**——下载完 Android 拉系统安装面板让用户再确认一次，Windows 下 `PushBox-Setup-*-win-x64.exe` 后静默覆盖安装并提示重启；手动检查与冷启动发现都汇到同一只确认框。检查只在「冷启动静默查一次」和「设置里手动点检查」两处触发（未认证限额 60 次/小时/IP，不进轮询），冷启动那趟还受设置里「启动时自动检查更新」开关门控（默认开）。走的是公开 https，与自建的业务服务器无关。

两处 CI 专属适配（都不改本地开发用的文件）：`gradle-wrapper.properties` 已经写死官方 `services.gradle.org` 发行包，CI 上不再需要临时改写；APK 质量闸就是"能编出来并签上名"。服务端测试**已经进 CI 了**——`ci.yml` 用 service container 起真 MySQL 8.4（带 `--ngram_token_size=2`）与真 RustFS，本地不需要 `deploy/env/local.env` 也能得到同一份判据；早先"因为依赖外部存储所以不放 CI"的说法已经作废。

> 门禁的真实运行要等代码推到远端。2026-10-20 先把四个 job 的本地等价命令各跑了一遍并全绿：服务端 261 条（跳过 0）、管理台 `npm ci && npm run build`、Windows 556/556、Android `assembleDebug` + `assembleRelease`。

---

## 已知限制

- **Web 管理台支持频道创建与密钥重置**：概览、消息搜索、频道/设备列表与详情可查（跨空间），并提供 `POST /api/v1/admin/channels` 创建新频道（返回明文 secret）、`POST /api/v1/admin/channels/{channelNo}/rotate-secret` 重置密钥；已有频道的 secret 不在列表中回显，只有创建/重置时返回一次。设备的创建与治理写操作仍走客户端 + CLI。
- **桶里已有对象默认是明文**：切到 `sse-c` 只影响新写入的对象，历史对象不迁移。
- **端上数据库不加密**：Android 用 Room、PC 用 SQLite 明文存消息正文（可搜索的前提）；被包裹的只有身份私钥、token、恢复码与 PIN 散列，它们走 AndroidKeyStore 里那把不可导出的 AES-GCM 密钥。拿到 root/磁盘镜像仍能读出历史消息。
- **明文 HTTP 是可选项而不是默认护栏**：客户端允许 `http://IP:端口`（内网/Tailscale 场景），是否上 TLS 由部署者决定；密文只保证「服务端 → 客户端」这一跳，端到端不在设计范围内。
- **消息永久保留**：没有自动过期；容量治理靠报表 + 手动清理。

## 安全

漏洞请不要开公开 issue，走 [SECURITY.md](SECURITY.md) 里的私密渠道；GitHub 仓已开启私密安全报告。

## License

[MIT](LICENSE)

---

**文档版本**：v1.1.0  
**最后更新**：2026-10-20  
**主分支**：main  
**仓库**：[GitHub](https://github.com/iamplusdev/push-box.git)（主仓）| [Gitee](https://gitee.com/iamplus/push-box.git) | [Gitea](https://gitea.iamplus.cn/iamplus/push-box.git)（镜像，暂未同步）
