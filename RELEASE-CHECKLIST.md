# PushBox 发布检查清单

本文档定义每次正式发布前必须完成的验证步骤。自动化能覆盖的部分写在 `scripts/release-verify.sh`（先跑它，再动手查剩下的）。

> 下面的勾选状态是 **v1.1.0 这一轮**（截至 2026-10-20）的实况：勾上的每一项都对应一条真跑过的命令，没勾的就是还没做或做不了。发下一版时先把复选框清干净。
> 本轮刻意留空的：CI 门禁的真实运行（要等 push 到远端）、Prometheus/Grafana 起栈（`age` 与监控栈没在这台 WSL 上装/起）、镜像标签与 Docker Hub 推送、`max_level` 拦截用例。

---

## 前置条件

- [x] 三端本地全量复跑通过（2026-10-20 实跑，下面每条都写了真判据）：
  - 服务端 `mvn -B -pl server -am test`（凭据来自 `deploy/env/local.env`，只走环境变量）→ BUILD SUCCESS，surefire 汇总 **260 条 / 跳过 0**（server 222 + core 38），与 `.github/workflows/ci.yml` 末尾的基线断言同口径。**要真的 source 它**：111 个用例带 `@EnabledIfEnvironmentVariable(PB_S3_ACCESS_KEY)`，没有桶凭据时它们整批 skip，而 `mvn test` 的退出码仍然是 0——260 条看着像全绿，实际只执行了 149 条。另外 `mvn` 必须跑在 JDK 21 上（`server/pom.xml` 要 `release=21`），Windows 上 `JAVA_HOME` 若指向 JDK 8 会在 `pushbox-core` 直接炸 `class file version 61.0 ... only recognizes up to 52.0`
    - 本机复跑要先绕开一个环境坑：`127.0.0.1:3306` 现在是另一套 `--network host` 的 `mysql8`（mysql:8.0）容器在听，`pushbox` 用户对 `pushbox` 库没权限，Flyway 报 `Access denied for user 'pushbox'@'%' to database 'pushbox'`，106 条 DB 用例整批红——这是拓扑不是缺陷。本项目那套 `pushbox-test-mysql-1` 不发布 3306，Windows 也路由不到 docker 网桥（`172.18.0.3:3306` 不通）。可用的复现法：临时起一个一次性容器（`-p 13306:3306`，带上 `--ngram_token_size=2`，否则 FULLTEXT 的 ngram 索引按默认 3 建，和 CI 不是同一份 schema），用 `SPRING_DATASOURCE_URL` 环境变量指过去（**不动 `application-dev.yml`**），跑完 `docker rm -f`；桶沿用 `local.env` 那台存储机，因为本机 WSL 和镜像加速器都取不到 `minio/mc`
  - 管理台 `cd web-admin && npm ci && npm run build` → `vue-tsc -b` 无报错，`dist/index.html` 非空、25 个产物文件（CI 断言的正是这两条）
  - Windows `dotnet test PushBox.Crypto.Tests -c Release` → **556/556、退出码 0**（此前 `BubbleView` 的视觉树定位器过时挂着 8 条红；判分要把全量日志落盘再看退出码，`| tail` 会把失败压成"看着像绿"）
  - Android `./gradlew :app:assembleDebug` 与 `:app:assembleRelease` 均 BUILD SUCCESSFUL：debug 包 26.6 MB、release 包 12.7 MB，`apksigner verify --print-certs` 出 V2 签名（`CN=iamplus`，证书 SHA-256 `99071932aa9084636ba7f5769b818d43903f4cc124f464073e47c35fc38dec4e` —— 换 key 就比对这一串）。同一次构建顺带证明 `outputs/mapping/release/mapping.txt` 确实落在 CI artifact 归档的路径上，**它实测 205 MB**，比直觉里的"几 MB"大两个数量级，配额按这个体量算
- [x] `bash scripts/release-verify.sh` 零 FAIL
- [x] CHANGELOG.md 已更新并标注发布日期
- [x] 版本号一致（Android `versionName` / Windows `<Version>` 与 `MyAppVersion` / Docker 镜像 tag / 四个 pom / `web-admin/package.json`，一次由 `scripts/set-version.sh` 写全），且 `versionCode` = `major*10000 + minor*100 + patch` 且**严格大于上一版**（写小了装不上：`INSTALL_FAILED_VERSION_DOWNGRADED`）
- [ ] CI 门禁已经真的跑过一遍（工作流被 git 跟踪、目标分支是 `main`、四个 job 全绿）——"文件在本机存在"不等于门禁在运行，这一条以前就是这样空了一整版
- [ ] 发布分支就是要打的那份代码：`git status` 干净、`git log` 顶端与 tag 意图一致

---

## 服务端发布

### 配置验证
- [x] `deploy/env/prod.env.example` 已同步最新环境变量（新增变量必须同时改 `server/src/main/resources/application-prod.yml`，否则被静默忽略——`PB_SERVER_PORT` 与 `PB_STORAGE_TYPE` 都曾是这样：文档里有、进程里没有，`scripts/release-verify.sh` 现在把这两条钉成了判据）
- [x] 必填项分两层：`PB_DB_URL` / `PB_DB_PASSWORD` / `PB_ADMIN_SECRET` / `PB_ADMIN_JWT_SECRET` 永远必填（缺任何一个 compose 在 up 之前就报错）；`PB_S3_ENDPOINT` / `PB_S3_ACCESS_KEY` / `PB_S3_SECRET_KEY` 只在 `PB_STORAGE_TYPE=s3`（默认）时必填——它们在 compose 里是可空的，留空由 `S3Config` 在启动时逐个点名
- [x] `PB_ADMIN_SECRET`（登录口令）与 `PB_ADMIN_JWT_SECRET`（JWT 签名钥）是两个不同的强随机值：`openssl rand -base64 32`；相同或过短都会拒绝启动
- [x] `PB_S3_OBJECT_ENCRYPT` 明确表态：`sse-c`（配 `PB_S3_MASTER_KEY`，base64 的 32 字节，**落库后不可轮换**）；或 `none` + `PB_ALLOW_PLAINTEXT_MEDIA=1`（迁移期逃生阀，常态应留空）
- [x] **桶存在**：服务端不建桶，仓库里也没有 provisioning 步骤，`PB_S3_BUCKET` 指的桶必须先 `mc mb --ignore-existing`（命令见 `deploy/QUICK-START.md`「桶要建一次」）。判据是 `mc ls`，**不是** `/healthz`
- [x] `PB_S3_ENDPOINT` 的端口跟着拓扑走：`rustfs/rustfs:latest` 镜像听 **9000**（Console 9001），§11.1 那台远端实例挂在 **7101**，两边互抄都会中招
- [x] `PB_CADDY_HOST` 是 Caddy 站点的主机匹配值：请求的 `Host` 不在它上面就落到 Caddy 的空响应里（`Content-Length: 0` 的 200，不是 404，容易被当成"通了"）。`deploy/Caddyfile` 只监听 80 做 HTTP 反代、**不管证书**，TLS 由前面的 Nginx/Traefik 终止（示例见 README），所以本清单不再有 `deploy/certs/` 与 SAN 那一条
- [x] `PB_SECURITY_INTERNAL_CIDRS` 留空即"只放行容器环回"（compose 的默认值就是空）。要 Prometheus 抓指标才把这条网络实际分到的子网写进来（`docker network inspect pushbox_default`），别写 `172.16.0.0/12` 整段
- [ ] TLS 口径确认过：`useSSL=false` 与 `http://` 意味链路明文可听，跨主机部署应改 `useSSL=true&requireSSL=true` 与 `https://`

### 安全审查
- [x] 无明文密钥提交到 Git（`git grep -I -n -E '(PASSWORD|SECRET|KEY)=[A-Za-z0-9+/]{16,}'` 只应命中占位符；真实值只在 gitignored 的 `.env` / `local.env`）
- [x] `.idea/` 仍被忽略：IDEA 会把运行配置里的 Environment variables 原样写进 `.idea/workspace.xml`，本机这份现在就带着 dev 的库口令与桶密钥。用 `set-user-env.ps1`（写用户环境变量）而不是往运行配置里填，就是为了让明文不进工程目录——填过的那次要靠 `.gitignore` 兜，所以 `scripts/release-verify.sh` 直接断言这三条忽略规则还在（`deploy/env/local.env`、`.env`、`.idea/`）
- [x] `deploy/Caddyfile` 保留 `@metrics path /actuator/*` → `respond 403`；服务端侧还有第二道门（`InternalAccessFilter` 只放行环回 + `PB_SECURITY_INTERNAL_CIDRS`），确认没有把其中一道当成全部依赖。**注意 Caddyfile 只挡 `/actuator/*`，`/healthz` 是经 Caddy 可达的**——真正把运维端点关在里面的是第二道门（`PB_SECURITY_INTERNAL_CIDRS` 留空）
- [x] 管理台登录限速：连续失败按来源 IP 锁定并返回 429（默认 5 次 / 5 分钟窗口）；Caddy 在前面时服务端看到的是代理地址，别指望它区分客户端
- [ ] 日志抽样：`docker compose logs pushbox-server | grep -iE 'recovery|token|secret|BEGIN PRIVATE'` 无命中；消息正文不进日志（§14 口径）
- [x] 启动阶段的拒绝项要能在容器日志里看到**键名而不是键值**：`deploy/Dockerfile` 的入口脚本负责 SSE-C 与 JWT 长度这两关

### 数据备份
- [x] 备份机装了 `mysqldump`、`gzip`、`age` 三件（`backup.sh` 现在开头就查，缺哪件报哪件并退出 1；2026-10-20 实跑时本机 WSL 两样都没有，这条不是假想敌）
- [ ] 执行备份：`AGE_RECIPIENT=<公钥> MYSQL_HOST=<DB_HOST> PB_DB_PASSWORD=... bash deploy/backup/backup.sh`（库内是明文，产物必须 age/gpg 加密后才出机器）。库在容器里（拓扑 B）时用 `docker compose exec` 那条变体，见 `deploy/QUICK-START.md`
- [ ] 验备份真的解得开：`age -d -i ~/.age/keys.txt <dump>.age | gunzip | tail -3` 看到 `Dump completed`
- [x] **恢复演练**（只有导出的备份等于没有备份）：`gunzip -c dump.sql.gz | docker compose exec -i mysql mysql -uroot <新库>` 之后逐表比行数，并确认 `flyway_schema_history` 顶到了本次的迁移版本
- [x] 记录当前 schema 版本，回滚时对照：
  ```bash
  docker compose exec -T -e MYSQL_PWD="$PB_DB_PASSWORD" mysql \
    mysql -u pushbox pushbox -e "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3"
  ```
- [ ] 新版本含 Flyway 迁移时，先在预发/备份副本上跑一次 `mvn -B -pl server -am flyway:migrate` 或直接起一次容器观察 `flyway_schema_history` 是否只追加、`success=1`

### 健康检查
- [x] 容器起来后在**容器内**取 `/healthz` → `{"ok":true,"db":"up","storage":"up"}`：
  ```bash
  docker compose exec -T pushbox-server bash -c \
    'exec 3<>/dev/tcp/127.0.0.1/${PB_SERVER_PORT:-7404}; printf "GET /healthz HTTP/1.0\r\n\r\n" >&3; cat <&3'
  ```
  从宿主 `curl http://localhost:7404/healthz` **拿不到 200**：端口虽然只绑环回，容器里看到的源地址是网桥网关而不是 127.0.0.1，`InternalAccessFilter` 会拒（这是设计口径，不是故障；要从宿主看就把那条子网写进 `PB_SECURITY_INTERNAL_CIDRS`）
- [x] `docker compose ps` 里 `pushbox-server` 是 `healthy`、`caddy` 没有反复 restarting。**`healthy` 只等于"TCP 探活通了"**：`/healthz` 的 `storage` 判的是"配置项非空"（`HealthzController.storageConfigValid`），既不探端点可达也不探桶存在，所以配错端口/没建桶时它照样 `up`
- [x] WebSocket 正常：`curl -i -H "Upgrade: websocket" -H "Connection: Upgrade" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" -H "Sec-WebSocket-Version: 13" http://<PB_CADDY_HOST>/ws` 应回 `101` 与正确的 `Sec-WebSocket-Accept`；TLS 在前时是 `wss://`，看服务端日志有 `auth_ok`
- [x] `GET /actuator/health` 从容器环回 200 且只回 `{"status":"UP"}`（组件明细被 `show-details: never` 挡住）；从没放行的源地址拿到的是 **404 而不是 403**——`InternalAccessFilter` 刻意不确认"这里确实有个运维端点"

---

## Android 发布

### 签名验证
- [x] CI 上四个 keystore secrets 都已配置（缺任何一个 `assembleRelease` 直接失败，不会悄悄出未签名包）。2026-10-20 配的是 `ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD` —— 这条只算"配好了"，门禁还没真跑过（见前置条件），首次 push 后要看 job 日志里签名那段
- [x] 签名验证通过：`build-tools/37.0.0/apksigner.bat verify --print-certs app/build/outputs/apk/release/app-release.apk` → V2 签名，`CN=iamplus, OU=iamplus, O=iamplus, L=ZhengZhou, ST=HeNan, C=CN`（本机 `assembleRelease` 走的是 `android/local.properties` + `android/dev/pushbox-release.jks`；CI 走 secrets，两边必须是同一把 key）
- [x] **签名证书与上一版一致**（换 key 等于老用户必须卸载重装，历史消息全丢）：v1.1.0 是首个公开版本，没有上一版可比，所以这一轮把基线钉成指纹 —— 证书 SHA-256 `99071932aa9084636ba7f5769b818d43903f4cc124f464073e47c35fc38dec4e`，以后每版比对这一串
- [ ] Keystore 与 `mapping.txt` 备份到安全位置（不在 Git 中；R8 崩溃栈全靠 mapping）。**这轮两处都是欠的**：keystore 备份被明确推迟；`mapping.txt` 现在只进 workflow artifact，公开仓的保留期上限是 90 天，过期就再也 retrace 不了 —— 真要长期可回溯得自己存一份

### 兼容性测试
- [ ] 在 API 26（Android 8.0）真机/模拟器上安装成功
- [ ] 在 API 34（Android 14）真机/模拟器上运行正常
- [ ] 通知权限请求正常弹出（首次启动）
- [ ] 生物识别解锁在支持的设备上工作；不支持的设备退回 PIN 且不给空白按钮
- [ ] 应用锁 PIN：连续错 4 次的退避是 5/15/30/60 秒，倒计时结束后能再试，锁定期输入框不给提交
- [ ] 老版本升级安装（不清数据）：身份私钥/token/恢复码从裸明文就地补封一次，"我的"页仍能显示恢复码

### 功能验证
- [ ] 首次启动注册页的服务端地址是空输入框（不预填任何内网地址），填 `http://IP:端口` 能过校验、填垃圾给明确报错
- [ ] 创建频道后能收到测试消息
- [ ] 长按会话行弹出菜单（置顶/免打扰/清空等）
- [ ] 图片消息能正确加载和保存
- [ ] 深色模式切换正常
- [ ] 「检查更新」只列 `pushbox-release-*` 资产；只有 debug 包时提示"暂无可安装的正式版本"而不是去装 debug

### 性能检查
- [ ] 冷启动时间 < 3 秒（中端设备）
- [ ] 消息列表滚动流畅（60 FPS）
- [ ] 内存占用 < 200MB（100 条消息场景）

---

## Windows 发布

### 安装包验证
- [ ] `setup.exe` 能通过 Windows SmartScreen（需代码签名证书；未签名时 CI 只告警不失败，发布说明里要写清）
- [ ] 卸载只清"指向本程序目录"的注册表键与开机自启项；**用户数据（`%LOCALAPPDATA%\PushBox\`）默认保留**，重装即恢复——这是设计口径，不是漏清
- [ ] 单实例机制正常（第二次启动提示已运行）
- [ ] 装机后「关于」页显示 `1.0.0`，`dotnet` 侧 `AppVersion` 与 `.iss` 的 `MyAppVersion` 同源

### 系统兼容性
- [ ] Windows 10 (19041+) 安装成功
- [ ] Windows 11 运行正常
- [ ] 高 DPI 屏幕下 UI 不模糊
- [ ] 托盘图标右键菜单正常
- [ ] 主窗口冷启不崩（`XamlParseException` 类问题由 `ThemeTest` 在 CI 拦，见 `dotnet test`）

### 功能验证
- [ ] 首次启动引导流程完整（服务器地址 → 设备名 → 恢复码）
- [ ] 三栏布局响应式调整（窗口宽度变化时）
- [ ] 消息气泡头像显示正确
- [ ] 设置页危险操作按钮为红色（清空/归档/吊销）
- [ ] 自动更新检查在启动时静默执行
- [ ] 拔网线 ≥ heartbeat+15s 后判死并带 `since_seq` 补拉，重连后消息按 seq 顺序显示

### 日志检查
- [ ] 日志文件落在 `%LOCALAPPDATA%\PushBox\logs\`
- [ ] 日志中无明文 recovery code 或 secret key（入口只收类型化字段、落盘前五层网）
- [ ] 异常堆栈包含足够调试信息

---

## Docker 镜像发布

### 镜像质量
- [ ] 镜像大小 < 500MB（多阶段构建优化）
- [ ] 以非 root 用户运行（`USER pushbox`）
- [x] 健康检查配置正确（TCP 探活，非 curl——JRE 镜像里没有 curl，且 `/healthz` 会真连库）
- [x] 入口脚本不打印任何密钥片段（只报键名）

### 标签管理
- [ ] `latest` 标签指向最新稳定版
- [ ] 语义化版本标签正确（如 `1.0.0`、`1.0`）
- [ ] SHA 短哈希标签生成（如 `sha-a1b2c3d`）
- [ ] `deploy/docker-compose.yml` 的 `image:` 与本次 tag 一致

### 部署验证
- [x] `docker compose up -d` 能成功启动所有服务。**主文件里不该再有任何"只有监控才需要"的必填项**：compose 的 `${VAR:?}` 插值作用于整本文件、与 profile 是否激活无关，所以 `GRAFANA_ADMIN_PASSWORD` 一旦留在主文件，"只起服务端"的部署会在解析阶段就报 required variable missing（prometheus/grafana 现在在 `docker-compose.monitoring.yml`）
- [x] 改了 `PB_SERVER_PORT` 的部署要确认进程真的听在这个端口：compose 的端口映射、`healthcheck`、Caddy 上游三处都读它，第四处是 `application-prod.yml` 的 `server.port`。缺第四处时容器会一直是"起了但映射到没人听的口"
- [ ] Prometheus 能抓取指标（`http://localhost:9090/targets`）：`docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up -d`，并把这条网络分到的子网写进 `PB_SECURITY_INTERNAL_CIDRS`；不写的话进程好好的、targets 全 `403`
- [ ] Grafana 仪表盘显示正常（`http://localhost:3000`，`GRAFANA_ADMIN_PASSWORD` 已改掉默认值）

### 功能验证（服务端）
- [x] 定时推送（v1.1.0 新增）：管理台建一条 `0 * * * * *` 的任务 → 列表里 `last_result=ok`、`last_fire_at` 逐分钟推进 → 库里 `messages.client_msg_id` 是 `sched:<task_id>:<分钟序号>` 且**每分钟恰好一行**（去重靠它 + `uk_msg_idem`）→ 停用后不再增长 → 删除后任务数归零
- [x] 五个 cron 段的表达式要被 `invalid_param` 拒掉（Spring 的 `CronExpression` 要 6 段），`level` 传不认识的值不能悄悄变成别的级别
- [ ] 定时任务打到 `max_level` 低于任务级别的频道时不能发出去（频道上限优先于任务配置）
- [x] 多实例对同一张 `scheduled_tasks` 轮询不会重复投递：`next_fire_at` 的 CAS 认领（`UPDATE ... WHERE task_id=? AND next_fire_at=<旧值> AND enabled=1`）是唯一保证——2026-10-20 用两个服务端实例连同一个库跑过，95 秒内正好 2 行

---

## 文档同步

- [x] `README.md` / `deploy/QUICK-START.md` 的安装步骤与实际流程一致（尤其 env 变量清单与密钥生成命令）
- [x] `CHANGELOG.md` 包含本次发布的所有重要变更
- [x] `docs/PushBox-软件设计方案.md` 的决策与实现一致（安全口径、限速阈值、判死阈值两端差异）
- [x] 官网 `site/` 七个页面的版本徽章、`pushbox-server-*.jar` 文件名与测试计数已跟本次版本对齐。根因也堵了：`scripts/set-version.sh` 现在会一起改 `site/*.html`，`scripts/release-verify.sh` 多了一条"站点徽章必须只有一种取值且等于 pom"的断言（v1.1.0 这轮就是它抓出来的：构建配置全对，站点还挂着 1.0.0）
- [x] 文档里的测试计数改成实测值：**Java 260（core 38 + server 222，跳过 0）**、**C# 556**。以前 README / 开发指南 / 测试指南 / `site/` 四处都写 197 与 553，是两版之前的数字
- [ ] 管理台那组 `/api/admin/scheduled-tasks` 只在 `docs/PushBox-软件设计方案.md` 与 CHANGELOG 里有描述，官网没有管理 API 页（站点口径是公开入口 `/message` + 客户端协议）。要给出站用户提供 curl 级参考，得新建一页——本轮没做
- [x] "三份端侧 README"这个说法本身是假的：`android/README.md` 与 `windows/README.md` 都不存在，端侧说明在 `docs/用户使用手册.md` 与 `docs/开发指南.md`（这条判据已按实际文件改口）
- [x] 故障排查指南补充了新发现的常见问题
- [x] 提交前扫一遍真实地址/凭据泄漏：`grep -rInE '\b(192\.168\.[0-9]+\.[0-9]+|100\.94\.[0-9]+\.[0-9]+)\b' --include='*' .`（文档与代码只用占位符，测试夹具用 RFC 保留段）

---

## 回滚预案

服务端：

```bash
# 1. 记下当前镜像与 schema 版本，便于对照
docker compose ps && docker inspect pushbox-server-1 --format '{{.Config.Image}}'

# 2. 起旧版本（改 .env 或 compose 里的 image tag，或直接 docker compose down 后用旧 tag up）
docker compose up -d --no-build pushbox-server   # image 已回退到 docker.io/iamplusdev/push-box:<上一版>

# 3. 只有确认新版本改过 DDL、且旧版本读不动时，才恢复数据库备份（这一步会丢新库里的数据）
age -d -i ~/.age/keys.txt backup/<时间戳>/pushbox-<时间戳>.sql.gz.age | gunzip | \
  docker compose exec -T mysql mysql -u pushbox pushbox
```

- **Flyway 迁移默认只前滚**：新版本跑过迁移后，旧镜像启动会因为 `flyway_schema_history` 里有更高版本而报错。要么旧镜像配 `spring.flyway.baseline-*`/忽略校验（不推荐），要么按上面的方式恢复备份。
- **客户端回滚**：
  - **Android**：GitHub Releases 保留历史 APK，但降级安装需要先卸载（`INSTALL_FAILED_VERSION_DOWNGRADED`），会丢本地历史——所以 APK 的 `versionCode` 只许涨。
  - **Windows**：Inno Setup 默认 `AllowDowngrades=yes`（本仓库未改这一项），覆盖安装即回滚；配置文件与本地库按版本无关的字段读写，降级后能继续用。
- 首个版本（v1.0.0）没有"上一版"可回滚：出问题直接 `docker compose down` + 保留备份，修复后发 v1.0.1。

---

## 发布后监控

- [ ] 首小时看服务端日志异常率与 `/actuator` 指标（`pushbox_*` 计数、HikariCP 活跃连接、抓取失败计数）
- [ ] 用户反馈渠道畅通（GitHub Issues）
- [ ] 准备热修复分支（`hotfix/`）应对紧急问题
- [ ] 客户端「检查更新」确实能拿到新 Release（Android 有 `pushbox-release-*.apk`、Windows 有 `PushBox-Setup-*-win-x64.exe`）

---

**最后更新**：2026-10-20（v1.1.0，生产 compose 全链路实跑后）  
**负责人**：开发团队轮值
