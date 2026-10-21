# PushBox 更新日志

本文档记录每个版本的核心改动、新增功能与修复的缺陷。详细技术决策见 [软件设计方案](docs/软件设计方案.md)。

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
- **任务分类标签 `category`**（Flyway `V9__scheduled_task_category.sql`，`VARCHAR(32) NOT NULL DEFAULT ''`）：给管理台的列表筛选用的**自由标签**，不是字典表——服务端只做 trim 与 32 字符截断，不校验取值、不建外键、不建分类管理页，空串就是"未分类"。选这个形状的理由是"给列表加个筛选"用不到一张需要一组 CRUD 端点伺候的字典表；反过来它也因此必须有个"空=未分类"的约定，不能让 null 进列（列是 NOT NULL，塞 null 会被 MySQL 严格模式当场拒掉，任务连建都建不出来）
- **入站 JSON 不再放过"尾部残留 token"**（2026-10-21，落点在全项目唯一的 `core/src/main/java/dev/pushbox/core/protocol/Json.java`）：Jackson 默认看完第一个完整对象就返回，后面的字节不检查，于是 `{"name":"x"}y` 这种请求体是"解析成功 + 请求真执行 + 200"，多出来的部分被安静丢掉。当天在旧版服务端上实测过：这样真的建出了一条频道；而 `{"name":"a"}{"name":"b"}` 两个对象拼一块时只有第一个生效，提交者以为两段都进去了。开了 `FAIL_ON_TRAILING_TOKENS` 之后同一份请求体经 `RequestBodyJson` 收成 400 信封（`invalid_param` + "请求体必须是合法 JSON"），尾部只留空白或换行仍然放过——那不是 token。**代价要说清**：这把尺子是 HTTP 的 `byte[]` 端点、WS 入站帧与管理台 JWT payload 解析共用的，所以这是协议级收紧，不是某个端点的小补丁；`FAIL_ON_UNKNOWN_PROPERTIES=false` 那份向前兼容没动。HTTP 那一侧有两组用例（管理台 + 设备侧）和活服务端实测兜着；WS 那一侧仍没有覆盖 `PushboxWsHandler` 的集成测试（2026-10-21 补的 6 条是 mock 级单元用例，不经过真实 socket），目前只证明了"这种输入会在 `Json.read` 抛"
- **`POST /api/v1/admin/channels` 的 `groupId` 改成必填且要真实存在**（2026-10-21，`AdminController.requireKnownGroup`）：此前是 `request.groupId() != null ? … : auth.groupId()`，而管理台分支的 `auth.groupId()` 是 `DeviceGuard` 造出来的字面量 `"admin"`（管理 JWT 没有设备行），`groups` 表里根本没这行；设备侧的频道列表按 `PbStore.listChannels(groupId)` 过滤，所以这样建出来的频道**入库成功、管理台看得见、任何真设备永远查不到**，而且入站照常发消息、没有任何一处报错。现在省略或全空白 → 400 `缺少 groupId：频道要建在设备所在的那个 Space 下，否则设备查不到`；给了一个 `groups` 里查不到的 id（含字面量 `admin`）→ 400 `groupId 不存在：这个 Space 里还没有设备注册过`；顺带把首尾空白 trim 掉再落库。校验用的是 `PbStore.findGroup()`——这个方法一直在仓库里，只是**从没有人调用过**。**为什么不在 `ChannelService.create` 收口**：设备侧那一条传的是真设备行的 `auth.groupId()`，本来就必然存在，在服务层加校验会让每次建频道多查一次库、还会把 `ChannelServiceTest` 那批 mock 出来的 `store` 一起打进 null 分支。**要留个底的两件事**：① 已经建在 `admin` Space 里的老频道不会被这次改动搬走，仍然只有管理台看得见，要给用户收到只能在真实 Space 下重建再归档旧的（本机联调库现查是 6 条，全部已是归档态、活跃 0 条）；② **管理台界面本来就没有"新建频道"入口**（`web-admin/src` 里 `createChannel` 零命中，`api/channels.ts` 只有列表/详情/删除三个函数），所以这条只影响照 `site/admin-api.html` 用 curl 的建站者，不影响任何点击路径

### Web 管理台

- 新增"定时推送"页（`web-admin/src/views/ScheduledTasks.vue`）：选频道、填标题/正文/强度/图片外链与 cron，列表直接显示上次触发结论（`ok`/`failed` + 错误摘要 + 上次消息 ID）与下次触发时间，支持启停与删除。列表只回 `content_head`，编辑时才取全文与图片
- **定时推送页的分类筛选与新建时选分类**：列表多一列「分类」，页头多一个筛选下拉；下拉的选项**从当前列表里现取**（不额外查字典表），里面还多一个"未分类"项——因为 `el-select` 拿到空串会显示成 placeholder，所以用 `__unset__` 当哨兵值，判据仍是"这一行的 `category` 是空串"。表单里的分类框是 `filterable allow-create`：既能在已有标签里选，也能当场敲一个新的，与服务端"自由标签、不校验取值"是同一套语义。筛选走前端 `computed`，与这个页面既有的关键字搜索一致（列表本来就是一次全量拉回来的）。**保存前还硬判一次 32 字**：分类用的是 `allow-create` 的下拉，它的输入框没有 `maxlength` 可挡，不拦就会被服务端静默截到 32 字——截出来的那个词和用户想写的已经不是同一个分类了
- 六个管理台端点：`GET`/`POST /api/v1/admin/scheduled-tasks`、`GET`/`PUT /{id}`、`POST /{id}/enabled`、`DELETE /{id}`。任务请求体上限单独放宽到 64KB——正文本身允许 32K 字符，沿用 8KB 的通用上限会把合法任务挡在门外

### 修复
- **WS 补拉阈值改为配置项并补 6 条 handler 用例**（2026-10-21）：`GAP_THRESHOLD` 原来硬编码 2000，注释里写的 `--pb.ws.gap-threshold` 从没绑过；现在挂在 `PbProperties.Ws`（默认 2000、下限 1）。新增 `PushboxWsHandlerTest` 6 条：hello 帧、签名不符拒绝、kx + `resume_ok`、补拉逐批取空、gap-threshold 生效、心跳判死/发 ping。全量 **288 条**（core 43 + server 245）0 失败 0 跳过；不带凭据时 174 执行 + 114 跳过
- **所有 `byte[]` 请求体端点的畸形体：500 → 400 信封**（2026-10-20 深夜到夜里，实测暴露的那条健壮性缺口）。收口落在新增的 `RequestBodyJson`（`read` 与 `readTree` 两个方法），它把 `Json.readBytes` / `Json.readNode` 抛的 `IllegalArgumentException` 一律转成 `ApiException.invalidParam("请求体必须是合法 JSON")`——措辞与 `GlobalExceptionHandler.handleBadJson` 同一句话，客户端不必为这一族端点另写一套解析。覆盖面是**八个端点、七处调用**：管理台 `POST /channels`、`POST /scheduled-tasks`、`PUT /scheduled-tasks/{taskId}`、`POST /scheduled-tasks/{taskId}/enabled`，设备侧 `POST /channels`、`/channels/join`、`/channels/leave`、`/channels/subscription-mode`、`PUT /channels/{channelNo}`、`POST /channels/{channelNo}/senders`，以及 `POST /devices/register` + `/devices/join`（这两条共用 `DeviceController.validateAndParse`，所以是一处调用两个端点）。`PUT /channels/{channelNo}` 走 `readTree` 而不是 `read`：它要区分"字段没提交"与"提交 null"，绑记录类会把这些治理位静默清空；树形那条还额外挡掉空体与非对象根，报的是另一句 `请求体必须是一段 JSON 对象`。**两条更省事的改法被明确否掉**：改成 `@RequestBody` 直绑 record 会把先量 8KB/64KB 上限的那一步一起删掉（绑 `byte[]` 就是为了拿到原始字节的长度）；在全局处理里补一支 `IllegalArgumentException → 400` 会把不属于这一族的解析失败（比如 JWT payload）一并改掉，且症状面比收口大。越界提示（`请求体过大` / `请求体过大（上限 65536 字节）`）原样保留；当时"尾部多一段垃圾仍按 Jackson 默认接受"这一条已经在 2026-10-21 由上面服务端那条收紧掉，现在同属 400 那一族。
  - **`MessageQueryController` 不在这份名单里，此前文档把它算进去了**：`PUT /messages/state/read` 那处早就自己写了 `catch (Exception)` → 400，从来不是 500 源。所以"设备侧九处"是错的，实际是六处频道 + 一处设备（两个端点）。
  - 新增两组纯 Mockito 用例：`AdminControllerBodyTest` 4 条（4 个端点 × 4 种坏体共 16 组断言 400 + `invalid_param` + 那句 `hint`，坏体集合里用根数组 `[1,2,3]` 当"类型不符"样本、避免某个端点把它悄悄绑上；启用开关的空体；越界仍是"请求体过大"；正常体照样落库与启停生效）、`DeviceControllerBodyTest` 5 条（六个频道写端点与 register/join 的坏体、空体、越界仍报"请求体过大"、PUT 的两类越界分句报、合法体仍进得 service 且 PATCH 只带 `muted` 时其余治理位仍是 null）。实跑 9/9 绿，那一跑的全量套件是 **273 条**（server 235 / core 38，0 失败 0 跳过），`ci.yml` 基线当晚从 265 抬到 270、2026-10-21 随上面那条分类标签的 3 条用例抬到 273。
  - 活服务端复核两轮都是真打的：把 `mvn package` 出的新 jar `docker cp` 进 `pushbox-test-pushbox-server-1`、`sha256sum` 对上才重启（管理台那轮第一次 cp 其实没落盘，这一步不能省），管理台 4 端点 × 5 种输入**全部 400**；夜里补完设备侧后，`POST /devices/register` 与 `/devices/join` 各打坏 JSON / 非 JSON / 空体三种，500 → 400 逐条对上，9011 字节的请求体仍回 `请求体过大`。打完立刻 `--force-recreate` 还原成镜像自带的 `9e7e4ca0…`（`3c3d764` 的产物，**不含这两项修复**），所以那套 WSL 栈现在仍是 500，别拿它当"没修成功"的证据。
  - **改之前先查了两端客户端**：Android 的 `PushBoxApi.exchange` 在非 2xx 时直接抛 `ApiError(code, 原始文本)`、**根本不解析错误体**，全项目按 HTTP 码分支的只有头像那处 `404`；Windows 的 `ApiError` 用 `JsonDocument` 宽容选读 `error`/`hint`，读不到就当空串，也不绑类型；**两端都没有 4xx/5xx 分档、没有对 401/409/429 的特判、没有按码清设备身份、没有幂等键**（Windows 唯一的待发队列是已读上报，它不分码一律回队）。结论：500 换成 400 只改掉上屏文案（从一串 Spring 默认体变成中文 `hint`），不动任何一端的重试或身份逻辑。频道那六个端点要设备签名，curl 打不到解析那一跳，**它们的 400 只有单测证据，没有活服务端证据**。
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

- `.github/` 解除 gitignore，新增 `ci.yml`（PR 与 `main` push 触发）：`server` job 起 `mysql:8.4 --ngram_token_size=2` 与 RustFS 服务容器，预建 `pushbox` 桶，跑真实 `mvn -B -pl server -am test` 后按 surefire XML 汇总设闸（`skipped != 0` 或 `tests < 261` 直接判失败；261 是当时实测的用例总数，基线只许涨，当晚补完管理台坏请求体的 4 条用例后抬到 265，夜里补完设备侧那 5 条后抬到 270）——此前"DB 闸门用例按约定跳过"意味着本地一句 `mvn test` 就能把大半测试静默跳掉还报绿。另含 `web-admin` 构建、Windows 加密库 `dotnet test`、Android `assembleDebug` 三个 job
- **`ci.yml` 第一次在 GitHub runner 上跑就红了两个 job（2026-10-20，`publish` 推成 `main` 那一次），两个根因都在外部依赖上**：
  - `server` job 的「等 RustFS 就绪并建桶」死在拉镜像：`minio/mc:latest` 报 `pull access denied ... repository does not exist`（exit 125）。查实的现状是 **`minio/mc` 与 `minio/minio` 都已从 Docker Hub 下架**（`hub.docker.com/v2/repositories/minio/{mc,minio}` 实测 404），连 `dl.min.io/client/mc/release/linux-amd64/mc` 也返回 **410 Gone**，所以"从宿主或另起一个 mc 容器"这句运维口径整体作废。改成 runner 自带的 AWS CLI v2（Ubuntu 24.04 镜像 Readme 实测预装 2.37.13）：`aws --endpoint-url http://127.0.0.1:9000 s3api create-bucket --bucket pushbox` 再 `head-bucket` 复核。**换之前先在本地对真 `rustfs/rustfs:latest` 容器跑过**：`create-bucket` 退 0、同一命令重复执行幂等、`head-bucket` 退 0；再把提取自 YAML 的这段步骤套上 `aws` shim 复跑一遍，确认等待循环与"runner 上没有 aws 就明确报错"的分支都按预期走。顺带把 `deploy/QUICK-START.md` 的建桶章节补上 aws 写法（用户侧也取不到 mc 了），并把 `docker-compose-wsl.yml` 那条 rustfs 的建桶注释改成实测可用的 `--network container:<rustfs>`（那份 compose 不给 rustfs 发布端口，`--network host` 打不到 9000——这个写法同样在本地起了隔离网络实测过）。
  - `android` job 的「Set up Android SDK」死在 `android-actions/setup-android@v3`：它要装已经下架的 legacy `tools` 包，`Failed to find package 'tools'` + sdkmanager 退出 1（`/usr/local/lib/android/sdk/cmdline-tools/16.0/bin/sdkmanager`）。删掉这一步，直接用 runner 预装的 SDK（同一份镜像 Readme：platforms 含 `android-36`、build-tools 含 36.0.0/36.1.0、platform-tools 37.0.1，且 `ANDROID_HOME`/`ANDROID_SDK_ROOT` 已导出，真缺组件时 AGP 会自己走 sdkmanager 补），换成一步"入镜检查"：从 `android/app/build.gradle.kts` 现读 `compileSdk`，断言 `platforms/android-<CS>` 存在且 `build-tools` 非空，红的时候直接说出缺哪一项。`build-apk.yml` 同口径改掉（它后面那步 `apksigner` 就是从 `build-tools` 取的）。四种分支（正例 / 缺 platform / 空 build-tools / 无 `ANDROID_HOME`）是把脚本从 YAML 里抽出来在 bash 里跑的，不是看一眼写的。顺便把这条链上一个悬着的风险查实：Gradle 9.6.1 支持在 Java 25 上运行（官方兼容性表：Java 25 自 9.1.0 起支持），所以 android job 用 JDK 25 不是问题。
  - **这两处修完当时还不等于门禁绿**：`ci.yml` 那条 261/跳过 0 的断言排在失败的建桶步之后，至此**一次都没执行过**；勾清单里"CI 门禁真跑过一遍"要靠下一次 `workflow_dispatch`（对 `publish` 点即可，`ci.yml` 没有发布步，零不可逆），别拿本地等价命令顶账。**这一句到 2026-10-20 深夜已经兑现**，逐条见下面那条"门禁第一次在 runner 上全绿"。
- **SDK 入镜检查在 runner 上绿了，同一条链往后暴露出第三处红，且本地复现不出来（2026-10-20，对 `publish` 手动 dispatch `build-apk.yml`，run `39370419457`，head `685acb4`）**：「核对 runner 预装的 Android SDK」这一步 success（上一版红的 `setup-android` 确实被换掉了），失败挪到后面的 `./gradlew :app:assembleRelease`，配置阶段报 `UnknownPluginException: Plugin [id: 'com.google.devtools.ksp', version: '2.3.12', apply: false] was not found`，六个仓库全列为"已搜索"。**已排掉的方向都实测过**：marker 的 pom 在 `repo1.maven.org` / `repo.maven.apache.org` / `plugins.gradle.org/m2`（跟 303）/ aliyun `public` / aliyun `gradle-plugin` 上全是 200，只有 aliyun `google` 与 `dl.google.com` 是 404——**这正好是 Gradle 期望的搜索顺序，第一个命中就该是 aliyun `public`**；`symbol-processing-gradle-plugin-2.3.12.{pom,module,jar}` 也在；central 的 `maven-metadata.xml` 里 `2.3.12` 的 `lastUpdated` 是 20260909，不是当天刚发、镜像没跟上的新版本；报错只点名 ksp，说明 AGP 9.2.1 与 kotlin-compose 2.2.10 在同一台 runner 上解析成功了，即 runner 到这些仓库是通的。**所以"仓库真没有"和"网络不通"两条都不成立，缺的是 runner 当时的视角**。为此在 `build-apk.yml` 构建步之前加了一步只打印不判失败的探测（同一个 commit 现读 `ksp` 版本，用 runner 自己的网络把 marker 在六个仓库上的 HTTP 状态码打一遍）；脚本是从 YAML 里抽出来在 bash 跑过的，本机输出即上面那六个状态码。**根因就是这一步定下来的，不是猜的**：第二次 dispatch（head `171b076`，run `39373315480`）探测步自己打出 `502 / 502 / 502 / 404 / 200 / 200`——**GitHub runner 打 `maven.aliyun.com` 的三个仓库整片返 502**（境外数据中心 IP 被拒），而 `settings.gradle.kts` 把镜像写在 `pluginManagement` 与 `dependencyResolutionManagement` 的**最前面**，插件解析被这个 502 带进"哪个仓库都没有"的分支；中央仓库和插件门户其实是 200，本机在墙内看不见那个 502，所以本地一直绿。**修法（维护者定的口径）：默认只走官方源，镜像改由开发机自己开**——`android/local.properties`（已 gitignore）加 `pb.maven.cnMirror=true` 才把三个 aliyun 仓库插回最前面，开关逻辑在两个块里各读一次文件（`pluginManagement` 是单独先编译的一段，看不见脚本其余部分的声明，这是试出来的：第一版抽了个顶层 `fun`，`help` 直接报 `Unresolved reference`）。判据用冷 `GRADLE_USER_HOME` 现跑：关掉开关 `--info help` → **`aliyun` 命中 0 次、BUILD SUCCESSFUL（2m53s，KSP marker 从官方源解析通过）**；打开开关换一个全新冷缓存目录 → **`aliyun` 命中 456 次（google 219 / public 232 / gradle-plugin 5）、BUILD SUCCESSFUL（47s）**，两条分支都实测过，不是看一眼写的。探测步留在 `build-apk.yml` 里当这一次修复的保险，**跑绿之后就删掉**——它每次都会打三行已经没有意义的 502。同步把 `docs/开发指南.md` 新增"Gradle 依赖仓库"一节写清这个开关和别再写死镜像的理由。
- `scripts/release-verify.sh` 新增"部署变量必须有人消费"检查块：逐个断言 compose 透传的 `PB_*` 在 `application-prod.yml` 有对应占位符、监控变量不在主文件、工作流入库且 `ci.yml` 不参与版本号注入。**判据是零 FAIL**，WARN 只负责报数不判失败（具体条数会随本机环境变，不写进日志）
- **签名 secrets 第一次真被使用，同时暴露它的格式判据（2026-10-20，第三次 dispatch `build-apk.yml`，head `5f31903`，`run 39375007556`）**：仓库口径改掉之后，runner 的配置阶段一路走过 SDK 入镜检查与插件/依赖解析——**这就是"镜像 502 那条已经解决"的 runner 侧证据**：整个 job 只有第 10 步 `Build release APK` 红，1–9 全 success。红在 `android/app/build.gradle.kts` 的 keystore 解码：`Illegal base64 character 2d`。`2d` 是 `-`，也就是 **`ANDROID_KEYSTORE_BASE64` 里那一串不是标准 base64**——最像 Windows `certutil -encode` 的产物（带 `-----BEGIN/END-----` 包头和换行），也可能是 base64url 字母表。原来那句 `IllegalArgumentException` 说不出区别，而这一条红掉等于发不出包，所以改成先按标准字母表解，解不开就报**形状**（长度、有没有 PEM 包头、有没有 `-`/`_`、有没有换行、有没有 `+`/`/`）再失败；**只报形状不报内容**——那一串本身就是密钥材料，打进日志等于泄露。三条分支都在本机拿真 keystore 跑过（`--no-daemon :app:assembleRelease`，用仓库里 `local.properties` 的三个口令 + `base64 -w0 dev/pushbox-release.jks`）：正例 **BUILD SUCCESSFUL 1m17s，产出 12,740,698 字节的 release APK**，`apksigner verify --print-certs` 出的证书 SHA-256 与 RELEASE-CHECKLIST 钉的基线 `99071932…dec4e` 逐字相同——**这是"CI 那条 env 驱动签名路径能用同一把 key"的第一次实测**；PEM 包头那条报"长度 3688，含 PEM 包头/包尾，含 base64url 的 - 或 _，含换行或空格"，base64url 那条报"长度 3640，含 - 或 _，不含 + 或 /"，两条都 rc=1；再把三份日志拿去比对 keystore 的 base64 前 80 字符，**三份都没命中**（判据是命令，不是"我看了一眼没打"）。所以剩下的事只有一件：把 GitHub secrets 里那一项换成 `base64 -w0 android/dev/pushbox-release.jks` 的一整行输出。探测仓库那一步已经完成使命（502 就是它打出来的），随本次一起删掉，留着就是每次三行没有意义的 502。
- **门禁第一次在 GitHub runner 上全绿（2026-10-20 深夜，head `60c86ad`，两次对 `publish` 的 `workflow_dispatch`）**：`ci.yml` `run 39377846888` 四个 job 全 success——服务端 job 里第 6 步「等 RustFS 就绪并建桶」（换成 runner 自带 AWS CLI v2 的那一步）与第 8 步「断言 DB 闸门用例真的执行了」（`skipped == 0` 且用例数 ≥ 261）**都是这条断言第一次在 runner 上真执行并通过**，另三个 job（管理台 `npm ci && npm run build` + 产物非空、Android `assembleDebug`、Windows `dotnet test`）全绿；`build-apk.yml` `run 39376573834` 同码全绿，`Build release APK` 与 `Verify release APK signature` 两步第一次真跑到，即**四个 keystore secrets 全被用过且解得开、签得上、验得过**（`Publish to GitHub Release` 与 `Calculate mapping.txt checksum` 是 skipped，因为它们 `if: startsWith(github.ref,'refs/tags/v')`，非 tag 事件本来就不该跑）。结论是用 `/actions/runs/{id}/jobs` 匿名逐步读出来的（steps 的 `conclusion` 可匿名取，日志体不行，`/logs` 实测 403），不是听汇报。**这条链当天红了四次、每次根因都在上一层**：`setup-android` 装已下架的 legacy `tools` → KSP marker"六个仓库都没有"（runner 打 aliyun 整片 502）→ base64 格式，四个都已定位并改掉；这一步之后 CI 侧没有已知的红灯了。**仍未被证明的两件**：`main` 上从没绿过（`main` 停在 `f6b9a53` 那次红的两条 job，要绿得先把 `publish` 推成 `main`，而那会连带 `docker-publish.yml` 真推 Hub——不可逆，等维护者点头）；`v*` 标签事件那条发布路径（`gh release`、版本闸门在 tag 上的取值、`1.1.0`/`latest` 标签）也没在 runner 上跑过。另外 `Verify release APK signature` 只断言"有签名且不含 `android debug`"，**没有把证书 SHA-256 与基线 `99071932…dec4e` 比对**，所以"CI 那份 APK 与本机那份是同一把 key"目前是靠本机 env 路径实测 + 非 debug 两条间接证据撑着，不是 CI 自己断言出来的。**这一句当天深夜已兑现成机器断言，见下面那条。**
- **签名指纹进了 CI 的判据，基线改成单一来源（2026-10-20 深夜，head `1cd4963` 之后）**：上面那条缺口（runner 那份 APK 只验了"有签名且非 debug"，没比对证书指纹）补上——新增 `android/release-cert.sha256`（内容就是基线那一串 64 位小写十六进制），`build-apk.yml` 的 `Verify release APK signature` 现读它，把 `apksigner verify --print-certs` 打出的**每一个 signer** 的 SHA-256 逐条比对，缺文件 / 不是 64 位小写十六进制 / `apksigner` 没打出任何 digest / 指纹不符一律红，红的时候打印基线与实际两份指纹（这俩都是公开信息，不是密钥材料）。多 signer 要逐条比而不是取第一条，因为 APK Signature Block 允许挂第二个签名而 `--print-certs` 会把它们全列出来——只取第一条会漏。取值锚定 `certificate SHA-256 digest:` 整串而不是宽抓 `SHA-256 digest`：实测 `apksigner verify --verbose --print-certs` 还会多打一行 `V2 Signer: public key SHA-256 digest: a23f8a7f…`，**那是公钥的指纹、和证书指纹是两个数**，宽正则会把 1 个 signer 抓成 2 条而判成不符（这台机器上 `Number of signers: 1`）。另外本机 `apksigner.bat` 的输出实测带 CRLF（`raw bytes tail` 打出来是 `\r\n`），runner 上是 LF，所以取值管道里加了 `tr -d '\r'`——少了这一步，同一份脚本在本机是绿的、在 runner 也绿，但谁拿它做本地复跑就会撞上"指纹不符"这种假红。**四种分支是从 YAML 里抽出这一步在 bash 真跑的**（拿本机那份 12,740,698 字节的 release APK）：正例 rc=0 并回显同一串、换成另一份 64 位十六进制 rc=1 且打出两份指纹、文件删掉 rc=1、文件写成大写且不足 64 位 rc=1。再给 `scripts/release-verify.sh` 加一条一致性闸：基线文件存在且格式合法 + `build-apk.yml` 真在读它 + `RELEASE-CHECKLIST.md` 里那一串与文件逐字相同，三条负例（清单里的指纹被改、workflow 不再引用该文件、文件写成大写）各自把脚本打成 1 个 FAIL，复原后 47 PASS / 0 FAIL。**这条新断言已在 runner 上绿过**（2026-10-20 深夜第五次 dispatch，`run 39381072797`，head `5293801`，第 10 步 `Verify release APK signature` success；结论用 `/actions/runs/{id}/jobs` 匿名读 steps 的 `conclusion`，日志体仍 403）——**CI 那份 APK 的证书 SHA-256 与 `android/release-cert.sha256` 的基线逐字相符，从此是 CI 自己验过的事实**，上面那条"只验非 debug、同一把 key 靠间接证据"的缺口就此合上；换 key 从此是有意的破坏性动作（先写 CHANGELOG 再改那一串）。
- **`docker-publish.yml` 不再在构建现场改写版本号，改成构建前核对**：旧写法在 `v*` 事件上跑 `set-version.sh "${GITHUB_REF_NAME#v}"`，于是"tag 说 1.2.3、pom 写 1.1.0"也能构建成功，推上去的是一个自称 1.2.3、里面跑着 1.1.0 的镜像——而 Docker Hub 推出去收不回来，被人拉走的那份 manifest 永远错着。现在新增"版本号一致性闸门"，在 buildx 与 login **之前**现读 `pom.xml` 与 `deploy/docker-compose.yml` 比对，不一致直接红并写出该补的命令。取值把 `<version>` 尖括号一起锚定：本机试过 pom 写成 `1.1.0-SNAPSHOT`，宽正则会把 `-SNAPSHOT` 读成 `1.1.0`，闸门就成了假绿（本仓全仓零 SNAPSHOT，所以判死）。闸门读出的那个值同时盖进镜像的 `org.opencontainers.image.version`——此前谁查这一版都会被骗：WSL 实测本机编出来的那份继承的是基底的标签 `{"org.opencontainers.image.title":"ubuntu","org.opencontainers.image.version":"26.04"}`，查出来的是 Ubuntu 的版本号。手动触发新增 `dry_run` 输入（默认勾上）：只构建并 load 进 runner，用来在不往 Hub 留任何东西的前提下验 `DOCKERHUB_*` 这对 secrets 与 metadata 算出的标签清单；`push=false` 必须配 `load=true`，否则 buildx 因"没有导出目标"直接失败
- **这条链第一次在 GitHub runner 上真跑过（2026-10-20，对 `publish` 分支点 dry-run dispatch）**：9 步全 success，含凭据校验、版本闸门、metadata 与多阶段构建，其中 `Login to Docker Hub` 是真登录——`DOCKERHUB_*` 这对 secrets 因此从"配好了"升级为"验过能用"。而跑完后 `hub.docker.com/v2/repositories/iamplusdev/push-box/tags` 仍是 **404**（对照组 `library/mysql` 返 200，排除网络假象），这才是"确实一个字节都没推上去"的证据。两个证据缺一个都不算验证过
- `scripts/release-verify.sh` 里"三个出制品 workflow 都该有版本注入"这条改成按 workflow 分别断言（apk/msi 注入、docker-publish 只核对），并新增反向判据：`docker-publish.yml` 里出现真正的注入命令就 FAIL。**判"有没有注入"必须锚定行首的命令**，不能整文件 `grep 'set-version.sh'`——第一版就是这么写的，结果闸门自己的报错文案里那句"先跑 set-version.sh 再重打 tag"被读成了"我在现场改写版本号"，一条正确的改动被判成 FAIL

### 文档

- **管理台 API 第一次有参考页：`site/admin-api.html`**（2026-10-20 新增）。此前官网三页只覆盖入站 `/message`、客户端 `/api/v1/**` 与协议，管理台那 16 个端点（登录 1 + 频道 5 + 设备 2 + 消息搜索 1 + 概览 1 + 定时任务 6）在 `docs/软件设计方案.md` 里只有决策描述，给用户一条 curl 都凑不出来。这一页的字段表、错误码与示例全部从代码读出来写：`AdminAuthController`、`AdminController`、`DeviceGuard`、`ChannelService`、`DeviceService`、`ScheduledTaskService`、`PbStore`。其中几条是任何既有文档都没说过的：管理台线是 **camelCase 请求 / snake_case 响应**（Jackson 没配命名策略，请求体字段名拼错会被静默丢掉而不是报错）；`hint` 永远是字符串、不会是 `null`；定时任务的 `channel_name` 与 `content_head`（80 字符）**只在列表里给**，`content`+`images` **只在详情里给**；`last_fire_at` 用 `0` 而不是 `null` 表示从没跑过；`enabled` 只有 `1` 算真；cron 是 6 段 Spring 方言；`msg_key = sched:<id>:<分钟桶>` 意味着一条任务每分钟最多产出一条消息，与 sweep 跑多快无关
- **顺手修掉的 6 处文档错误 + 自查中新发现的 2 处**：管理台消息搜索被写成 `/api/v1/admin/messages` + `/search` 那一段（真实只有 `/messages?q=`，照抄 404），出现在 `deploy/QUICK-WSL-TEST.md`；`docs/软件设计方案.md` 三处把 REST 补拉的游标写成 `since_seq`（那是 WS 连接 URL 上的参数，REST 叫 `after_seq`）、两处媒体/头像路径写的是不存在的形状（真实是 `/api/v1/media/{msg_id}/{idx}` 与 `/api/v1/avatars/{sha256(sender_id)}`）；`site/usage.html` 把入站写入写成挂在 `/api/v1` 下的那条（真实是 `POST /message`，前缀由 `pb.ingest.base-path` 配置），`CHANGELOG.md` v1.0.0 那条同源错误一起改；`site/api.html` 的 `/healthz` 口径整个是旧的（补上：白名单由 `PB_SECURITY_INTERNAL_CIDRS` 控制且默认只放环回、真做 DB `isValid(2s)` 与本地存储写探针、down 时 503，所以它是**就绪探针不是存活探针**）；`docs/审计报告.md` §2 把 `GET /api/v1/admin/messages` 归到了服务设备侧的 `MessageQueryController` 上，且它"所有管理台端点完全一致"的结论是拿 6 个抽查项外推的（当时定时任务那一组还没进视野），连同会静默腐烂的文件行号引用一起改成"方法 + 完整路径"记账
- **新增一条闸：文档里写到的端点路径必须存在于代码**（`scripts/release-verify.sh`）。代码侧从 `web/*.java` 的 `@RequestMapping`/`@Get…Mapping` 提路由（要处理三种坑：`path = "${pb.ingest.base-path:/message}"` 取默认值、光有注解不带路径的映射到类前缀、以及类前缀必须拼在方法路径前面——第一版没拼，判据把文档里每一条路径都报成不存在）；文档侧扫 `site/*.html` + README + CHANGELOG + RELEASE-CHECKLIST + `docs/*.md` + `deploy/*.md`。同位段先归一化（`{channelNo}`、纯数字、ULID 都归成 `{}`），否则真端点会被误报。**2026-10-20 实跑：代码侧 34 条、文档侧 36 条、全部命中，全脚本 51 PASS / 0 FAIL / 2 WARN**（文档侧那个数不当基线：傍晚补文档时它自己涨到 37，闸看的始终是"每一条能不能匹配上代码里的路由"）。反向验证往判据里喂一条从未存在的路径，确认可抓出。**这一轮第一次实跑就是红的，红在我自己新写的 RELEASE-CHECKLIST 两条勾选项上**：那两句把"这个路径从来不存在"的错误写法整条抄了出来，而闸是纯字符串匹配，分不出"照着用"和"提醒别这么写"——改的是文档措辞（错误写法不再整条照抄），不是给闸加白名单开口子。**闸只看路径**：请求体字段名、鉴权头、响应形状都不在它的能力范围内，那些仍然只能靠人对着代码读。**当天傍晚这条闸第二次红，第二次也红在我自己的句子上**：管理台那一页新写"末段拼错会 404"时，为了举例子把一个从未存在的路径整条照抄进正文——纯字符串匹配的闸分不出"照着用"和"提醒别这么写"，于是判它不存在。改的仍然是措辞（例子里不出现完整路径），**这条闸到今天为止两次红、两次都没需要放宽判据**
- **文档过程中查实并写进页面的三个边界**（此前只有代码知道）：① `POST /api/v1/admin/channels` 省略 `groupId` 会建出一个孤立 Space（管理台分支 `DeviceGuard.require()` 返回 `Authenticated(null, "admin", "admin")`，Space 名就叫 `admin`，真设备永远看不见它，且服务端不校验这个 ID 是否存在）——**这一条当天已在下一页修掉，`groupId` 现在必填且必须存在于 `groups` 表**；② **管理台面默认是公网可达的**——`deploy/Caddyfile` 只挡了 `/actuator/*`，`InternalAccessFilter` 的白名单只覆盖 `/healthz` 与 `/actuator/**`，管住 `/api/v1/admin` 的只有登录口令加一把 24 小时 JWT，且没有刷新与吊销；③ 登录失败锁定是**进程内 Caffeine**，重启即清零、多实例不共享
- **这一轮当时没有验到的两件事**：① 新页面只做过结构核对（DOM 快照确认导航、JS 生成的目录、各节与复制按钮都在，console 零报错，外加 `curl` 返 200），**浏览器截图这一步在本机取不到视口、没做成**，视觉排版未经目视确认——**这一条到当天傍晚仍然没补上**；② 页面上 16 个端点的契约当时是从代码读出来的，**本轮没有一条真打过运行中的服务端**——**这一条当天傍晚已经兑现，见下面"测试与验证"里那一整轮实测**，也正是那一轮把这一页里 5 处"代码级正确"的声称打成了错的

### 测试与验证

- **管理台 API 参考页从"代码级正确"升级成"运行时实测"（2026-10-20 傍晚，WSL 的 `pushbox-test` 栈：prod profile + MySQL 8.4 + RustFS + Caddy，打的时候 `uptime_ms` ≈ 2,713,682）**：16 个端点逐条真打过，把响应的键集合原样 dump 出来跟文档对表。被测镜像构建自 `3c3d764` 那棵树（镜像时间戳 2026-10-19 23:57，傍晚只是用同一份镜像重建容器，二进制没动）。写这条时它与当时的 HEAD 在 `server/src/main/java`、`web-admin/src` 上逐字零差异，差异只在 `application-prod.yml`（HEAD 新增 `server.port` 与 `pb.storage.type` 两处变量消费）——配置管道，不影响响应形状。**当晚 HEAD 又多出上面「修复」那一处（`AdminController` 的坏请求体收口 + 4 条单测），所以那句话现在只对该镜像与那处修复之外的文件成立；这套在跑的栈没有这个修复。****这一轮实测推翻并改掉了 5 处文档声称**：
  - **① "消息列表每条 14 个键"**：这组响应过的是全项目那份 `Include.NON_NULL` 的 mapper，**值为 `null` 的键整个不出现**。实测同一批两条消息一条 14 键一条 13 键，少的是没填的 `title`。顺此查出另外两处同类错写：活频道的 `archived_at`、未吊销设备的 `revoked_at` 在响应里**键根本不存在**（实测未吊销设备每项 6 个键），而文档写的是"未吊销为 `null`"。前端判据从 `=== null` 改成"键在不在"
  - **② 定时任务响应表把 `last_result`/`last_error`/`last_msg_id` 写成"都有，没跑过时是 `null`"**：实测从没触发过的任务详情里**三个键全缺席**；跑过之后也不是三个一起出现——成功轮 = `last_result:"ok"` + `last_msg_id`（`last_error` 缺席），失败轮 = `last_result:"failed"` + `last_error` 且**上一轮成功留下的 `last_msg_id` 被写 `null` 抹掉**，所以它是"最后一次尝试"而不是"最后一条送达"
  - **③ "路径写错会 404 成 JSON"**：实测**漏掉 `/api/v1/admin` 前缀回的是 HTTP 200 + SPA 那份 `<!doctype html>`**（脚本只看状态码就会当成"服务端返回空数据"，这是整轮里最阴的一条）；末段拼错是 404 且为 Spring 默认体不是信封；合法资源名不存在才是应用自己的 404 信封；方法用错是 405 非信封（且方法匹配发生在资源存在性之前，任务号随便写也先 405 不 404）；少一段走到设备签名那套鉴权，回 401 信封 `hint:"缺少签名头 X-PB-Device/Ts/Nonce/Sig"`
  - **④ "口令错到阈值就 429"**：实测阈值卡的是**请求次数**——连错 5 次第 5 次仍然 401（锁是在这一次里登记的），**第 6 次才 429**、`Retry-After: 300` 且 `hint` 里的秒数递减；**锁定期内输对口令照样 429**（锁定判定在比对口令之前）；窗口一过那条计数记录连同锁期一起过期回收（17:53 锁上 → 17:58 到期 → 17:59 再错三次全是 401），换算下来单一来源 IP 约 60 次/小时，这条锁定的作用是限速不是拦住
  - **⑤ 摄入侧与管理侧的阈值节奏不同**：同一套 `pb.ingest.credential-fail-lock=5` 下，`POST /message` 的**第 5 次**就直接 `403 channel_locked`（实测连打 6 次），而管理台要第 6 次才 429——两边的"经验"不能互相照抄，`site/api.html` 那条 403 记法因此是对的，没动
  - **实测坐实（此前只有代码依据）的其余口径**：`q` 上限按**码点**算（101 汉字 400 / 100 汉字 200，60 个 emoji = 120 个 UTF-16 单元仍然 200）、`q` 两侧空白会 trim、`%`/`_`/`\` 已转义（库里有 `a_b_c` 时 `q=a_c` 命中 0 条）、`since`/`limit` 非数字回 400 非信封、驼峰 query 参数被静默忽略、三档请求体上限的 `hint` 原话（`请求体过大` / `请求体过大（上限 65536 字节）` / `正文 40000 字节，超过该频道的 max_body_bytes 32768`）、`PUT` 是整体覆盖（`level` 回落到 `info`、`images` 清空、文本字段变 `""`，`last_*` 保留）、停用期间 `next_fire_at` 冻在已过期的时隙上一条不发、重新启用从当下重算且不补跑、目标频道被归档后下一轮扫表把任务 `enabled` 置 0 并写 `last_error:"频道已归档或不存在，任务已自动停用"`、`rotate-secret` 真能解掉凭据锁定（连错 5 次 → rotate → 新密钥当场 200、旧密钥 401）、归档只影响列表（详情照样 200、历史消息照样搜得到）、建频道与 rotate 的响应都是那 6 个键且**都不回显 `group_id`**、省略 `groupId` 实测 `group_id == "admin"`、缺 `Bearer` 前缀报的是 `hint:"缺少设备 token"` 而不是"token 无效"、`/healthz` 的 `{"ok":true,"db":"up","storage":"up","uptime_ms":…}`、`overview` 的 6 个 `stats` 键与 `trend` 只含有消息的那几天
  - **实测查出一个真实健壮性缺口，当晚按维护者选定的方案改掉了**：`POST /channels`、`POST /scheduled-tasks`、`PUT /scheduled-tasks/{taskId}`、`POST /scheduled-tasks/{taskId}/enabled` 这四个把请求体当 `byte[]` 收、再自己调 `Json.readBytes` 的口，收到语法坏掉 / 类型不匹配 / JSON 数组 / 只有空白时抛 `IllegalArgumentException`，而 `GlobalExceptionHandler` 只覆盖三支（`ApiException`、`HttpMessageNotReadableException`、`MissingServletRequestParameterException`），接不住 → **500 + Spring 默认体（还会回显 `path`）**；同一口收**完全空体**反倒正常（400 信封「请求体必须是合法 JSON」）；对照 `@RequestBody` 直绑 record 的 `/admin/login` 与 `/message` 一律 400。另一侧的松也实测到了：**合法 JSON 后面多带一个字符会被静默接受并真的建出一条任务**（Jackson 默认不查尾部残留）。修法与验证见下面「修复」那一节的同名条目；尾部多字符那条**本轮没动**，它不是异常，是 Jackson 的默认宽松。**
  - **本轮仍然没验的**：多实例下的锁定与扫表抢占、真机端上读到这些消息的完整链路、吊销一台设备之后 `revoked_at` 长成什么样、图片抓取（SSRF 闸与转存）在管理台侧的表现、浏览器视觉截图
  - **两个我自己先踩的坑，写下来免得下次再拿假数据改文档**：`yes x | head -c N | tr -d '\n'` 造出来的"请求体"只有 **N/2** 字节（换行被 `tr` 吃掉），据此一度得出"8192 与 65536 两道闸都没生效"的假结论；Git Bash 里用 `printf` 拼多字节串，`--data-urlencode` 发出去的字节与预期不符，一度把"100 个汉字被 400、60 个 emoji 被 400"读成"上限按字节算"。改成 Node 逐条构造请求体之后，码点口径与代码完全一致——**这两条都是测试脚本的错，不是服务端的错，也不是文档的错**
- 新增 `ScheduledTaskLogicTest` 18 个单测（cron 解析与下次时间计算、CAS 抢占失败不投递、msg_key 分钟桶、内部入口不带密钥、频道上限与字节判定、`level` 归一化），纯 Mockito 不碰容器
- 全量 `mvn -B -pl server -am test` 收集 261 条用例（server 223 条、core 38 条），**source 了 `deploy/env/local.env` 那次是 0 失败 0 跳过**；不带桶凭据时同一份代码只执行 149 条、跳过 112 条（这批用例带 `@EnabledIfEnvironmentVariable(PB_S3_ACCESS_KEY)`），而 `mvn test` 的退出码仍然是 0——这正是 `ci.yml` 要按 surefire XML 断言 `skipped == 0` 的原因。`web-admin` 的 `vue-tsc` 类型检查与 `vite build` 通过
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
- 唯一入站写入接口 `POST /message`（不在 `/api/v1` 下，前缀由 `pb.ingest.base-path` 配置）：频道凭证鉴权、幂等去重、正文截断标记、图片外链抓取转存后与文字同帧下发
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
