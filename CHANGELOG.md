# PushBox 更新日志

本文档记录每个版本的核心改动、新增功能与修复的缺陷。详细技术决策见 [软件设计方案](docs/PushBox-软件设计方案.md)。

首个正式版本是 v1.0.0。发布前的 28 轮内部流水（原编号 v1.1–v1.28）已归档到
[开发轮次记录](docs/开发轮次记录.md)，那里按时间保留每一轮的缺陷、判据与验证结论。

---

## v1.1.0（2026-10-19）—— 管理台定时推送

### 服务端
- **定时推送任务**：新增 `scheduled_tasks` 表（Flyway V8）与 `ScheduledTaskService`，运维在 Web 管理台挑一条已有频道、写一段 cron，服务端按点把消息灌进那条频道。入库与下发复用 `POST /message` 的同一条链路（`IngestService.ingestInternal`），差别只在凭据：库里只存 `secret_hash`，定时任务拿不到明文密钥，所以由管理台 JWT 这一侧授权、不重放 secret
- **调度用扫表 + CAS，不建内存定时器**：`next_fire_at` 就是轮询游标，`@Scheduled` 每 15s 扫一轮（`pb.scheduled.sweep-interval`），到期行靠 `UPDATE ... WHERE task_id=? AND next_fire_at=<旧值> AND enabled=1` 抢占。多实例同时扫到同一行只有一个改得动，因此不需要 `SELECT ... FOR UPDATE` 占连接，重启也不丢已排好的任务
- **幂等按整分钟分桶**：`client_msg_id = sched:<task_id>:<分钟槽>`，配合 `messages.uk_msg_idem`，亚分钟级 cron（`* * * * * *`）的实际效果是每分钟一条。2026-10-19 在真栈上跑了 80s 验证：跨一个分钟边界只落 2 条，`last_result` 全程 `ok`、无错误残留
- **cron 必须 6 段**（秒 分 时 日 月 周，Spring `CronExpression`）：5 段 unix 写法当场 400 并提示"需要 6 段"，不会按误写的语义静默跑成另一套时刻
- **任务正文按字节受目标频道 `max_body_bytes` 约束**：与投递时用的是同一把尺子。此前按字符判会让"32K 个汉字"的任务创建成功、然后每分钟失败一次
- **`level` 落库前归一化到 `Level` 的 wire 值**：`warn` 这类近义写法触发时会被 `Level.fromWire` 静默降级成 `info`，任务看着是 warn、消息却是 info，排查起来没有任何报错可循

### Web 管理台
- 新增"定时推送"页（`web-admin/src/views/ScheduledTasks.vue`）：选频道、填标题/正文/强度/图片外链与 cron，列表直接显示上次触发结论（`ok`/`failed` + 错误摘要 + 上次消息 ID）与下次触发时间，支持启停与删除。列表只回 `content_head`，编辑时才取全文与图片
- 六个管理台端点：`GET`/`POST /api/v1/admin/scheduled-tasks`、`GET`/`PUT /{id}`、`POST /{id}/enabled`、`DELETE /{id}`。任务请求体上限单独放宽到 64KB——正文本身允许 32K 字符，沿用 8KB 的通用上限会把合法任务挡在门外

### 修复
- **频道强度标签**：`web-admin/src/utils/format.ts` 把 `warn` 当成了合法 wire 值，而库里存的是 `warning`，warning 级频道一直渲染成默认灰标签
- **入站 `strict_auth` 的重复闸门**：`IngestService.ingestAccepted` 里那段 ts/nonce/sign 窗口校验与调用方 `checkStrictAuth` 完全重复，已删除。闸门只留调用方一处；内部路径（定时推送）没有明文密钥、HMAC 无从计算，整层跳过

### 构建与部署
- **修复 v1.0.2 统一版本号时漏改的父 POM**：根 `pom.xml` 与 `core/pom.xml` 的 `<parent><version>` 没跟着走，整个 Maven 反应堆 `Non-resolvable parent POM`，服务端与 Docker 镜像直接构建不出来。`scripts/set-version.sh` 因此把 root/core/server 三处 pom 与两份 compose 的镜像 tag 一起纳入覆盖面
- **Caddyfile 反代端口透传**：`reverse_proxy pushbox-server:{$PB_SERVER_PORT:7404}`，此前写死 7404，自定义服务端口时反代指向一个不存在的上游
- **WSL 镜像构建卡死的根因**：容器里 `repo.maven.apache.org` 解析到 AAAA 记录，而 Docker bridge 网络没有可用的 IPv6 出口，Maven 下载一直挂着（同一 URL 在宿主机走 IPv4 秒回）。构建改用 `docker buildx build --network=host`；排查口径与另外两个坑（Caddy 按 `PB_CADDY_HOST` 匹配 Host，用 `127.0.0.1` 访问得到 200 空响应体；这台机器上的 Windows `curl` 会把内联中文参数按 GBK 发出）记进 [QUICK-WSL-TEST.md](deploy/QUICK-WSL-TEST.md) 的 Q1.7/Q1.8

### 生产部署资产（2026-10-20 生产 compose 全链路实跑发现并修复）

实跑方式是：用一个隔离的 compose project 起生产 `docker-compose.yml`（端口 17404/18080），复用测试栈里已有的 MySQL 与 RustFS 作外部网络，逐项验证密钥闸门、内网白名单、Caddy、管理台登录、WebSocket、定时推送、备份与恢复。下面每一条都是实跑撞出来的，不是纸面推演。

- **监控栈拆成独立文件** `deploy/docker-compose.monitoring.yml`：Prometheus/Grafana 原本靠 `profiles:` 与主栈同居，但 compose 的 `${VAR:?}` 插值是全文件生效、跟 `profiles` 无关——不开监控的部署也会被 `GF_SECURITY_ADMIN_PASSWORD is missing` 挡在 `up` 之前。主文件里现在不留任何"只有监控才需要"的必填项，`release-verify.sh` 加了断言钉住这条
- **`PB_SERVER_PORT` 补上真正的消费点**：此前只有 compose 端口映射、容器 healthcheck 和 Caddy 反代认这个变量，服务端进程依旧听 7404——改端口的人看到"起来了、健康检查也过"，实际所有流量打在一个没人监听的口上。现在 `application-prod.yml` 的 `server.port` 从 `${PB_SERVER_PORT:7404}` 取值
- **`PB_STORAGE_TYPE` / `PB_STORAGE_LOCAL_PATH` 从死变量变成透传变量**：prod profile 不加载 `application-dev.yml`，而 `application-prod.yml` 里没人读这两个键，于是"单机无对象存储"的生产配置填了 `local` 也照样去连 S3。补齐 `pb.storage.type` / `local-path` 映射，并把变量清单写进 `prod.env.example`
- **内网白名单默认值改成 fail-closed**：`PB_SECURITY_INTERNAL_CIDRS` 原来是 `${VAR:-172.16.0.0/12}`——compose 的 `:-` 在变量为空字符串时也会替换，等于每个忘记配置的部署都默认把整段私有网段放行到 `/healthz` 与 `/actuator/*`（消息正文、频道枚举都在这两个端点上）。默认值改为空，只留 loopback；要放行监控必须显式写桥接子网。注意闸门拒绝时返回 **404 而非 403**（不暴露端点存在性），Caddy 的 `@metrics → 403` 只覆盖 `/actuator/*`，不覆盖 `/healthz`
- **S3 三项必填改为可选**：`PB_S3_ENDPOINT/ACCESS_KEY/SECRET_KEY` 从 `${VAR:?}` 降成 `${VAR:-}`，否则 `local` 模式根本起不来；空值的后果改由 `S3Config` 在 `s3` 模式下拒绝启动兜住，本地 storage 路径与明文媒体各自有守卫（`ProdPlaintextMediaGuard`、Dockerfile 入口脚本里的 JWT 长度检查）
- **RustFS 端口与建桶口径纠正**：镜像监听 9000（S3）/9001（Console），文档和 `wsl-test.env` 里写的 7101 是另一台存储机上那个实例的端口，照抄过来得到的是 connection refused。更坑的是 `/healthz` 的 `storage` 只校验配置项非空，既不探端点也不查桶，所以"端点写对、桶没建"这种配置健康检查全绿、每条带图消息停在 `failed`。QUICK-START 新增"桶要建一次"章节（`mc alias set` + `mc mb --ignore-existing`），CI 与 compose 注释都记录了 9000/7101 的区别
- **备份脚本两处硬化**：`backup.sh` 开头加依赖预检（`mysqldump`/`gzip`/`age` 缺任意一个立刻退出，不再"跑了个空备份还报成功"）；mysqldump 加 `--no-tablespaces`——8.4 默认导出表空间需要 PROCESS 权限，普通业务账号会在 stderr 打印 `Access denied` 然后**以 0 退出**，产出一个缺 DDL 的归档

### CI 门禁

- `.github/` 解除 gitignore，新增 `ci.yml`（PR 与 `main` push 触发）：`server` job 起 `mysql:8.4 --ngram_token_size=2` 与 RustFS 服务容器，用 `minio/mc` 预建 `pushbox` 桶，跑真实 `mvn -B -pl server -am test` 后按 surefire XML 汇总设闸（`skipped != 0` 或 `tests < 261` 直接判失败；261 = 2026-10-20 实测 server 223 + core 38）——此前"DB 闸门用例按约定跳过"意味着本地一句 `mvn test` 就能把大半测试静默跳掉还报绿。另含 `web-admin` 构建、Windows 加密库 `dotnet test`、Android `assembleDebug` 三个 job。**`ci.yml` 自身还没在 CI 上跑过**（要落到 `main` 或 PR → main 才触发；2026-10-20 在 GitHub runner 上真跑绿的只有 `docker-publish.yml` 那次 dry-run，而它不跑测试）
- `scripts/release-verify.sh` 新增"部署变量必须有人消费"检查块：逐个断言 compose 透传的 `PB_*` 在 `application-prod.yml` 有对应占位符、监控变量不在主文件、工作流入库且 `ci.yml` 不参与版本号注入。**判据是零 FAIL**，WARN 只负责报数不判失败（具体条数会随本机环境变，不写进日志）
- **`docker-publish.yml` 不再在构建现场改写版本号，改成构建前核对**：旧写法在 `v*` 事件上跑 `set-version.sh "${GITHUB_REF_NAME#v}"`，于是"tag 说 1.2.3、pom 写 1.1.0"也能构建成功，推上去的是一个自称 1.2.3、里面跑着 1.1.0 的镜像——而 Docker Hub 推出去收不回来，被人拉走的那份 manifest 永远错着。现在新增"版本号一致性闸门"，在 buildx 与 login **之前**现读 `pom.xml` 与 `deploy/docker-compose.yml` 比对，不一致直接红并写出该补的命令。取值把 `<version>` 尖括号一起锚定：本机试过 pom 写成 `1.1.0-SNAPSHOT`，宽正则会把 `-SNAPSHOT` 读成 `1.1.0`，闸门就成了假绿（本仓全仓零 SNAPSHOT，所以判死）。闸门读出的那个值同时盖进镜像的 `org.opencontainers.image.version`——此前谁查这一版都会被骗：WSL 实测本机编出来的那份继承的是基底的标签 `{"org.opencontainers.image.title":"ubuntu","org.opencontainers.image.version":"26.04"}`，查出来的是 Ubuntu 的版本号。手动触发新增 `dry_run` 输入（默认勾上）：只构建并 load 进 runner，用来在不往 Hub 留任何东西的前提下验 `DOCKERHUB_*` 这对 secrets 与 metadata 算出的标签清单；`push=false` 必须配 `load=true`，否则 buildx 因"没有导出目标"直接失败
- **这条链第一次在 GitHub runner 上真跑过（2026-10-20，对 `publish` 分支点 dry-run dispatch）**：9 步全 success，含凭据校验、版本闸门、metadata 与多阶段构建，其中 `Login to Docker Hub` 是真登录——`DOCKERHUB_*` 这对 secrets 因此从"配好了"升级为"验过能用"。而跑完后 `hub.docker.com/v2/repositories/iamplusdev/push-box/tags` 仍是 **404**（对照组 `library/mysql` 返 200，排除网络假象），这才是"确实一个字节都没推上去"的证据。两个证据缺一个都不算验证过
- `scripts/release-verify.sh` 里"三个出制品 workflow 都该有版本注入"这条改成按 workflow 分别断言（apk/msi 注入、docker-publish 只核对），并新增反向判据：`docker-publish.yml` 里出现真正的注入命令就 FAIL。**判"有没有注入"必须锚定行首的命令**，不能整文件 `grep 'set-version.sh'`——第一版就是这么写的，结果闸门自己的报错文案里那句"先跑 set-version.sh 再重打 tag"被读成了"我在现场改写版本号"，一条正确的改动被判成 FAIL

### 测试与验证
- 新增 `ScheduledTaskLogicTest` 18 个单测（cron 解析与下次时间计算、CAS 抢占失败不投递、msg_key 分钟桶、内部入口不带密钥、频道上限与字节判定、`level` 归一化），纯 Mockito 不碰容器
- 全量 `mvn -B -pl server -am test` 收集 261 条用例（server 223 + core 38），**source 了 `deploy/env/local.env` 那次是 0 失败 0 跳过**；不带桶凭据时同一份代码只执行 149 条、跳过 112 条（这批用例带 `@EnabledIfEnvironmentVariable(PB_S3_ACCESS_KEY)`），而 `mvn test` 的退出码仍然是 0——这正是 `ci.yml` 要按 surefire XML 断言 `skipped == 0` 的原因。`web-admin` 的 `vue-tsc` 类型检查与 `vite build` 通过
- **补上内部摄入路径的 `max_level` 截断用例**：`IngestServiceTest` ⑫ `internalPathIsTruncatedLikeThePublicOne` 直接调 `ingestInternal()`（定时推送走的那条），断言 `error` 打进 `max_level=warning` 的频道时，响应与库里 `messages.level` 都是 `warning`。此前只有公开 `/message` 路径（该类的 ①）覆盖过截断，内部路径一条都没有；把断言写在 `ScheduledTaskLogicTest` 里是不行的——那个类的 `IngestService` 是 mock，`captor` 只能看到请求发出去了，看不见落库值
- 真实 WSL 栈端到端验收：`pushbox/server` 镜像经 Caddy 反代建任务 → sweep 触发 → `messages` 落 `sched:<id>:<分钟>`、`sender_id=scheduled`、中文正文 UTF-8 完好 → 列表回 `last_result=ok` → 停用 → 删除；Flyway 迁移到 V8，管理台 SPA 产物（含定时推送懒加载 chunk）确认烘进了 jar
- **`testvectors/vectors.json` 从此按 key 排序输出**（`CryptoVectorsTest` 显式开 Jackson 的 `ORDER_MAP_ENTRIES_BY_KEYS`）。此前每跑一次 core 测试，文件都会换一套 key 顺序：内容逐字节相同，git 却报 40+ 行 diff，评审里再没人能判断"协议向量被改过"这件事是真的还是顺序噪声。改完实测两次连跑 sha 一致，Windows 侧 `dotnet test` 仍 **556/556**（C# 只按 key 取值，与顺序无关）

---

## v1.0.2（2026-10-19）—— 管理台功能增强

### 服务端
- **管理台频道创建**：新增 `POST /api/v1/admin/channels` 端点，支持在 Web 管理台直接创建频道并展示明文 secret（可复制），不再局限于 Android 端创建
- **频道密钥重置**：新增 `POST /api/v1/admin/channels/{channelNo}/rotate-secret` 端点，管理员可随时轮换频道密钥，旧密钥立即失效并自动解除频道锁定
- **请求体输入验证**：管理台接口添加 8KB 大小限制，与服务端其他端点保持一致的安全基线

### 设计决策调整
- **D28 修订**：Web 管理台从"只读"升级为"受控写入"，保留频道创建与密钥管理能力，但依然不回显已有频道的 secret（只有创建/重置时返回一次）

### 部署（WSL Docker）
- **WSL 部署套件**：新增 `deploy/docker-compose-wsl.yml`、`wsl-test.env`、`deploy-wsl.sh`/`deploy-wsl.ps1` 一键脚本，配套快速指南 [QUICK-WSL-TEST.md](deploy/QUICK-WSL-TEST.md) 与 [WSL-DEPLOY.md](deploy/WSL-DEPLOY.md)
- **MySQL/RustFS 数据目录改用 Docker volume**：Windows NTFS 上的 bind mount 无法满足 MySQL 对 `private_key.pem.temp` 的 POSIX 权限操作，导致容器启动失败；改为 Docker volume 后 MySQL、服务端、RustFS 均健康运行
- **内网白名单 CIDR 修正**：`PB_SECURITY_INTERNAL_CIDRS` 由 `127.0.0.1/8` 改为网段地址格式 `127.0.0.0/8`，此前服务端因主机位非 0 启动即报错
- **WSL 服务端口绑定放开到 0.0.0.0**：`pushbox-server` 端口不再只绑 `127.0.0.1`，Android 模拟器可经 `10.0.2.2:7404` 直连 WSL 内的服务端联调
- **已知注意点**：WSL 中 Docker 若继承了系统代理，构建镜像前需清除 `http_proxy`/`https_proxy`，否则代理拒绝连接导致构建失败（排查口径见快速指南 Q1）

---

## v1.0.1（2026-10-19）—— 安全加固与可观测性增强

### 服务端
- **零依赖本地存储**：新增 `pb.storage.type=local` 模式，替代 S3/RustFS，适用于无对象存储服务的单机部署；数据落盘自动设置 POSIX 600 权限
- **健康检查增强**：`/healthz` 端点现在同时检测数据库连通性与本地存储可写性，区分 DB down 与 storage down
- **磁盘空间监控**：本地存储模式下暴露 `pushbox.storage.disk.usage.percent` 与 `pushbox.storage.disk.free.bytes` 指标，用于 Prometheus/Grafana 预警
- **消息摄入性能监控**：新增 `pushbox.ingest.duration` Timer 指标，追踪从请求受理到入库完成的总耗时
- **输入验证加固**：`/register` 与 `/join` 端点添加 8KB 请求体大小限制，防止 OOM DoS 攻击
- **JWT 解析安全性提升**：管理台登录使用 JSON 解析器替代正则表达式，防止 crafted payload 绕过 exp 校验
- **备份脚本适配**：支持本地存储卷打包，优先级为 local > RustFS volume > S3 mc mirror

### 测试与质量
- 新增 27 个单元测试：LocalFileStorage（15）、KeyWrap seal/open（12）、HealthzController 存储检测（5）
- 修复 MediaObjectsConfigTest 在本地存储模式下的兼容性问题
- 所有 204 个测试通过（0 失败，0 错误）

### 文档
- 更新软件设计方案 §4.5，补充本地文件系统存储拓扑说明
- README 与快速开始文档拆分 Docker Compose 与纯后端两种部署方式

---

## v1.0.0（2026-10-19）—— 首个正式版本

自托管的推送中枢：一台自己的服务端，把消息加密下发到 Android 与 Windows 两端，频道由用户自己建、外部系统用一条 curl 就能发进来。三端与 Web 管理台的功能在这一版全部落地。

### 服务端
- 唯一入站写入接口 `POST /api/v1/message`：频道凭证鉴权、幂等去重、正文截断标记、图片外链抓取转存后与文字同帧下发
- 长连接下发按设备逐条封信封（每条连接自己的会话钥与帧序号），断线重连带 `since_seq` 补拉，补拉批次分页可配
- 设备与频道治理：注册/接管、恢复码加入、恢复码轮换、频道号重置、发送者白名单、频道静音与订阅模式
- 配置变更即时广播（`config` 帧），凭证轮换不需要客户端重连
- 滥用防护：图片抓取限速、每日配额、容量报表；外链抓取失败进重试队列而不是丢消息
- Web 管理台（Vue3 + Element Plus，随镜像烘入）：概览、消息搜索、会话/频道/设备治理、跨空间只读视图

### Android 客户端
- 首启注册 / 恢复码加入、扫码填恢复码、恢复码卡片禁截图
- 常驻长连接（前台服务）+ 极致省电轮询两档；断网重连后消息严格按 seq 顺序显示
- 通知、桌面角标、会话免打扰、静默时段、通知总开关
- 微信风格消息页：图片九宫格、单格失败态、缺失媒体回捞、会话内搜索并点击定位到命中消息
- App 锁（PIN + 生物识别）、外观（浅/深/跟随系统、字号、摘要行数）、频道管理与设备管理
- 启动被动检查 GitHub Releases 并在确认框里下载安装（可关）

### Windows 客户端（Win10/11）
- .NET 8 + WPF 三栏主界面，托盘常驻、系统通知、头像与媒体缓存
- 与 Android 共用同一份加密与协议实现（`core/` 源码直接共享），行为对齐：主动心跳 + 入站超时判死 + 带游标补拉
- 会话/频道/设备/设置各页功能对齐 Android；删除与清空有回滚语义，已读游标只增不减
- 自包含单文件发布 + Inno Setup 安装包（per-user，卸载只清属于本程序的键）

### 安全与端上存储
- 信任边界只有一条：服务端 → 客户端的下行帧与 REST 响应体加密。服务端入库为明文以支持检索，这是拍板的取舍而非缺陷
- 首次连接记下服务端公钥指纹（TOFU），之后每次密钥交换都要对得上
- Android 的私钥 / token / 恢复码 / PIN 验证串在落盘前经 AndroidKeyStore 里一把不可导出的 AES-GCM 密钥密封，那份私有文件里只剩 `v1:<iv>:<密文>`；老数据读到就就地补封，不需要清数据重装
- App 锁口令用 PBKDF2-HMAC-SHA256（12 万轮、随机盐）校验，恒定时间比对，连续失败按 5/15/30/60 秒退避；旧的无盐 SHA-256 在首次解锁成功时自动升级
- 一切密钥与口令只经环境变量注入，仓库里没有任何凭据；`docker-compose` 用 `${VAR:?}`，缺一项就拒绝启动

### 发布与构建
- 三端版本号统一由 `scripts/set-version.sh` 从 tag 注入，`versionCode = major*10000 + minor*100 + patch` 保证单调，非 `X.Y.Z` 的版本一律拒绝写入
- Android release 必须由 CI 用环境变量注入的 keystore 签名，缺签名直接构建失败；客户端只认 `pushbox-release-*` 资产，不再回退 debug 包——装上签名不同的包会让下一次升级只能卸载重装
- GitHub Actions：APK / MSI / Docker 镜像三条流水线，产物只收正式签名的 release 包；Windows 安装包先收集后签名，并以 `Get-AuthenticodeSignature` 校验
- Android R8 混淆与资源压缩开启，关键类由 `proguard-rules.pro` 保护

### 已知边界
- 允许用 `http://内网IP:端口` 直连自建服务端（拍板保留），首启不再预填任何示例地址
- 本地消息库（Android Room / Windows SQLite）是明文：只有拿到已解锁状态的设备才读得到，历史内容与服务端一样不做静态加密
- PC 端没有轮询兜底那一档（15 分钟 WorkManager 周期补拉属于 Android 的极致省电模式）
- 判死阈值两端刻意不同：Android 是 `心跳间隔 × 2`（默认 50s），PC 沿用协议里的 `心跳 + 15s`（默认 40s）

### 验证
- `dotnet build` Debug/Release 0 警告 0 错误；`dotnet test PushBox.Crypto.Tests` 556/556 全绿
- `mvn -B test`（core + server）通过；服务端体检 `PushBox.LiveCheck` 十条判据跑在自起的 8081 上
- `./gradlew assembleDebug` 与 `assembleRelease` 均 BUILD SUCCESSFUL
- Android 真机验收：断网重连补拉幂等、卸载重装靠恢复码找回历史、未注册设备什么都拿不到、消息顺序正确
