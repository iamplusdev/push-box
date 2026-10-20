# PushBox v1.0.0 发布准备报告

**日期**：2026-10-19
**版本**：1.0.0（Android `versionName` = Windows `<Version>` = Docker tag；Android `versionCode` = 10000）
**状态**：三端本地构建与单测全绿，仓库尚未推送——这一版是首个公开版本，之前的 v1.26/1.27/1.28 是内部轮次编号，已归档进 [docs/开发轮次记录.md](docs/开发轮次记录.md)。

---

## 这一版收口了什么

### 1. 端上密钥不再裸写盘（Android）

身份私钥、token、恢复码、应用锁 PIN 散列以前直接以明文键值存在 DataStore 里，`adb backup` / root 读文件即取走。现在：

- 新增 [SecretVault.kt](android/app/src/main/java/cn/iamplus/pushbox/data/SecretVault.kt)：AndroidKeyStore 里一把**不可导出**的 AES-256-GCM 密钥，密封格式 `v1:<iv>:<密文>`。
- 新增 [PinHash.kt](android/app/src/main/java/cn/iamplus/pushbox/data/PinHash.kt)：PIN 从裸 SHA-256 换成 PBKDF2-HmacSHA256（120k 轮 / 16 字节盐 / 256 位），比对走 `MessageDigest.isEqual`。
- [IdentityStore.kt](android/app/src/main/java/cn/iamplus/pushbox/data/IdentityStore.kt) 首启把仍是裸明文的旧值就地补封一次，**不需要清数据重装**；已是 `v1:` 的一律跳过。
- 应用锁失败退避 5/15/30/60 秒，用 `elapsedRealtime()` 计时（改系统时间绕不过），倒计时期间提交按钮禁用；散列计算搬到 `Dispatchers.Default`，不在 UI 线程跑 12 万轮。

老散列（64 位十六进制）在第一次校验成功后自动升级成新格式，用户无感。

### 2. 全仓不再有真实地址与"默认预填的服务器"

- Android 注册页删掉写死的内网地址预填，改成空输入框 + 正则校验（`http(s)://host[:port][/path]`），错误提示区分"没填"和"填得不像 URL"；不猜 scheme，避免用户填 `192.168.x.x` 时静默走 https 然后超时。
- 提交前扫过一遍仓库：文档、README、官网页、部署脚本、代码注释里的真实内网/Tailscale 地址全换成占位符（`<DB_HOST>` / `<S3_HOST>` / `<存储机>`），测试夹具改用不会撞到任何真实机器的地址。
- `application-dev.yml` 的对象存储地址与管理台口令改成 `${PB_S3_ENDPOINT}` / `${PB_ADMIN_SECRET:dev-admin-123456}`：真实值只在本机 gitignored 的 `deploy/env/local.env`，缺键由 `DevCredentialsGuard` 当场点名（不再伪装成 `Access denied` 或 URI 解析错）。

### 3. 发布链路只出签名 release 包

- `build.gradle.kts` 的 release 签名由四个环境变量驱动（`ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`）：base64 解码到 `build/pb-release.keystore`（不进 Git），没配就直接注册一个 `requireReleaseSigning` 任务把 `assembleRelease` 拦下来——**不再出现"未签名的 release 包混进 Releases"**。
- CI 只收集 `pushbox-release-*.apk` 并用 `apksigner verify` 把关；`gradle-wrapper.properties` 写官方 CDN，工作流里那段"临时改写 wrapper"的步骤删掉了。
- 客户端「检查更新」只认 `pushbox-release-*` 资产；只有 debug 包时提示"暂无可安装的正式版本"，因为 debug 签名装不上 release（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。

### 4. 服务端把管理台与运维端点收紧

- `PB_ADMIN_JWT_SECRET`（HS256 签名钥）与 `PB_ADMIN_SECRET`（登录口令）拆成两个值：以前共用一个，改口令等于作废全部已发 token，结果是两个都长期不换。compose 用 `${VAR:?}` 强制、入口脚本再校验长度与"两者不同"，短了或留空拒绝启动。
- 登录失败按来源 IP 记数，达阈值（`pb.ingest.credential-fail-lock=5`，窗口 `lock-window=5m`）返回 429 并带 `Retry-After`。
- `/actuator/**` 与 `/healthz` 由 `InternalAccessFilter` 只放行环回 + `PB_SECURITY_INTERNAL_CIDRS`；Caddy 侧另有 `respond @metrics 403`，两道门不互相依赖。
- prod 不再允许"忘配加密 = 静默明文"：`PB_S3_OBJECT_ENCRYPT=none`（含留空）时 `ProdPlaintextMediaGuard` 拒绝启动，除非显式打开 `PB_ALLOW_PLAINTEXT_MEDIA`（迁移期逃生阀，只影响新写入对象）。

### 5. 版本基线

`scripts/set-version.sh` 只接受严格 `X.Y.Z`，并据此推导 `versionCode = major*10000 + minor*100 + patch`；解析不出（`0.1.0-dev`、脏树后缀）就报错退出、不改任何文件——写进更小的 versionCode 会让老用户装不上。三端各写死一处版本，由 `scripts/release-verify.sh` 比对 Android / Windows / Docker 三处是否一致，并断言 versionCode 与 versionName 对得上。

### 6. 部署资产与文档一致性

- `deploy/env/prod.env.example`：占位符化、补齐 `PB_ADMIN_JWT_SECRET` / `PB_ALLOW_PLAINTEXT_MEDIA` / `PB_SECURITY_INTERNAL_CIDRS` / `PB_CADDY_HOST`，并写清 TLS 口径（`useSSL=false`、`http://` 意味着什么，跨主机该怎么改）。
- `deploy/docker-compose.yml`：8080 绑到 `127.0.0.1`（文档里的 `curl localhost:7404/healthz` 现在真的能通，公网仍打不开）。
- `deploy/Caddyfile`：主机名参数化成 `{$PB_CADDY_HOST:pushbox.internal}`，三处引用同一个值；顶部注释写清"自签 CA"与"公网证书"两条路，以及缺证书时的现象（caddy 一直 restarting）。
- `deploy/Dockerfile` 入口脚本：不再打印密钥片段（以前会 `echo` 主钥前 8 位），admin 两项改成 fail-fast。
- `README.md` / `deploy/QUICK-START.md`：密钥生成不再回显明文值，`sed` 直接写进 `.env`；补齐限速/429、签名钥轮换会作废会话、备份口令走 `-e MYSQL_PWD` 而非命令行等说明。
- `scripts/release-verify.sh`：新增 versionCode↔versionName、env 驱动签名、不再收集 debug 包、部署模板不含真实地址、入口脚本不打印密钥片段、compose 强制 `PB_ADMIN_JWT_SECRET` 等断言；顺手修掉 `((PASS_COUNT++))` 在 `set -e` 下首次自增即退出脚本的坑。

---

## 验证结果（实测）

| 项 | 结果 |
|----|------|
| Android `:app:assembleDebug` | BUILD SUCCESSFUL（仅遗留 deprecation 警告） |
| Android `:app:assembleRelease` | BUILD SUCCESSFUL，12.7 MB（debug 26.7 MB），`apksigner verify` 出 V2 签名；无 keystore 时被 `requireReleaseSigning` 拦下。R8 的 `mapping/release/mapping.txt` **实测 205 MB** —— CI 把它归档成 workflow artifact（公开仓保留期上限 90 天），别按"几 MB"估配额 |
| 服务端 `mvn -B -pl server -am test` | 2026-10-20 带齐凭据复跑：**270 条全执行（core 38 + server 232），0 失败 0 错误 0 跳过**，BUILD SUCCESSFUL。没 source `deploy/env/local.env` 时是 149 执行 + 112 跳过、退出码照样 0，所以"跳过必须为 0"才是判据（`ci.yml` 末尾的断言与此同口径） |
| Windows `dotnet test PushBox.Crypto.Tests` | 556/556（Debug 与 Release 各一遍） |
| Windows `dotnet build` | 0 警告 0 错误 |
| `bash scripts/release-verify.sh` | 见下方"发布前动作"，跑通即零 FAIL |
| Docker 镜像构建 | 2026-10-20 用隔离 project **真起过一次** `docker compose up`（MySQL / RustFS 复用 WSL 测试栈），`deploy/Dockerfile` 的多阶段构建与容器 healthcheck 都过了，一轮撞出五个"配置写了、进程不消费"的缺陷（见 commit 3dd95d5）。**仍未验**的是从 Docker Hub 拉 CI 产物那条路：镜像还没推过，要等首次 `v*` tag |
| 管理台 `cd web-admin && npm ci && npm run build` | `vue-tsc -b` 无报错，`dist/index.html` 非空 + 25 个产物文件 |

---

## 发布前要做的动作（顺序）

1. 确认 GitHub secrets：`DOCKERHUB_USERNAME`、`DOCKERHUB_TOKEN`、四个 `ANDROID_KEYSTORE_*`；Windows 代码签名 `CODESIGN_CERT_BASE64` / `CODESIGN_PASSWORD` 可选。
2. `bash scripts/release-verify.sh` 零 FAIL。
3. 本地三端复跑（上表那四条命令）。
4. 首次推送仓库到 GitHub / Gitee / Gitea，`main` 分支。
5. 打 tag：`git tag -a v1.0.0 -m "首个正式版本"` → push tag，三条工作流各自出产物并挂 GitHub Release。
6. 部署侧按 [RELEASE-CHECKLIST.md](RELEASE-CHECKLIST.md) 走：先 `deploy/backup/backup.sh` 备份再 `docker compose up -d`。

---

## 已知边界（不是缺陷，是取舍）

- **不做端到端加密**：加密覆盖"服务端 → 客户端"一跳，服务端库里是明文才能全文搜索。这是本项目的核心取舍，见设计文档 §4。
- **端上数据库不加密**：Android Room / PC SQLite 明文存正文；被 Keystore 包裹的只有凭据类密钥。root 或磁盘镜像仍能读出历史消息。
- **桶里的历史对象仍是明文**：`sse-c` 只影响新写入对象，不做迁移；`PB_S3_MASTER_KEY` 一旦启用**不可轮换**（换了旧对象再也解不开）。
- **明文 HTTP 是允许项而不是被禁项**：内网/Tailscale 场景保留 `http://IP:端口`，是否上 TLS 由部署者决定；客户端不再限制 `network_security_config`。
- **判死阈值两端不同**：Android `interval × 2`（默认 50s），PC `heartbeat + 15s`（默认 40s），协议文档写的就是 40s，差异是刻意的。
- **真机/真客户端验收仍要人跑**：xunit 与 JUnit 只锁协议与渲染行为，拔网线、SmartScreen、安装面板这类只能实测。

---

## 长期提醒

1. **签名凭据丢失等于无法更新**：Android keystore 与 Windows PFX 各自备份到 Git 之外的安全位置；换 Android 签名 key 等于让全部老用户卸载重装。
2. **每次 release 后备份 `mapping.txt`**（`android/app/build/outputs/mapping/release/`），否则线上崩溃栈还原不了。
3. **`versionCode` 只许涨**：`set-version.sh` 已经强制，别手改 `build.gradle.kts` 绕过它。
4. **真实地址与凭据永不入库**：值只进 `deploy/.env`（生产）与 `deploy/env/local.env`（本机联调），两者都在 `.gitignore` 里；提交前跑一遍 `release-verify.sh` 的地址扫描。

---

## 文档索引

| 文档 | 用途 |
|------|------|
| [RELEASE-CHECKLIST.md](RELEASE-CHECKLIST.md) | 发布前逐项人工确认 |
| [CHANGELOG.md](CHANGELOG.md) | v1.0.0 变更摘要 |
| [docs/开发轮次记录.md](docs/开发轮次记录.md) | 内部轮次（v1.26 及更早）的原始记录 |
| [docs/CODESIGN-GUIDE.md](docs/CODESIGN-GUIDE.md) | Windows 代码签名证书方案 |
| [docs/R8-CONFIGURATION.md](docs/R8-CONFIGURATION.md) | Android 混淆配置与验证 |
| [docs/PushBox-软件设计方案.md](docs/PushBox-软件设计方案.md) | 设计与决策依据 |
| [deploy/QUICK-START.md](deploy/QUICK-START.md) | 5 分钟部署 |
