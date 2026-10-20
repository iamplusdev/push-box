# PushBox 发布检查清单

本文档定义每次正式发布前必须完成的验证步骤。自动化能覆盖的部分写在 `scripts/release-verify.sh`（先跑它，再动手查剩下的）。

> 下面的勾选状态是 **v1.1.0 这一轮**（截至 2026-10-20）的实况：勾上的每一项都对应一条真跑过的命令，没勾的就是还没做或做不了。发下一版时先把复选框清干净。
>
> **未勾项分四类，阻塞原因不同，别混在一起当成"待办"**（数字是 `grep -c '^- \[ \]'` 现数出来的，会随版本变）：
> 1. **要远端动作才能判**（`main`、`v*` tag、CI 真跑、镜像标签、发布后监控）——本机一条都推不动，且第一次推 `main` 会连带 `docker-publish.yml` 真往 Docker Hub 推镜像，不可逆。想在没有 `main` 的情况下先验门禁：`ci.yml` 没有发布步，`build-apk.yml` / `build-msi.yml` 的 `gh release` 都带 `if: startsWith(github.ref,'refs/tags/v')`，所以**对 `publish` 分支手动 `workflow_dispatch` 这三个是零不可逆的**；`docker-publish.yml` 的 dispatch 现在**默认是 dry-run**（`dry_run` 输入框默认勾上 → 只构建并 load 进 runner，不推 Hub），点它可以在真发布前验出 `DOCKERHUB_USERNAME`/`DOCKERHUB_TOKEN` 能不能登录、metadata 算出的标签清单长什么样；把勾去掉才会真推。**这一条 2026-10-20 已经点过一次并全绿**，实测结论记在「Docker 镜像发布 → 标签管理」里。
> 2. **要真机 / 干净装机环境**（Android 兼容性 6 项、两端功能与性能各若干、Windows 系统兼容 4 项）——这台机器上既没有 Android 真机/模拟器，也没有可回滚的干净 Windows 测试环境。这类的正确处置是"指定人 + 指定设备清单"，不是留着空勾。
> 3. **本机工具缺**（数据备份 2 项、桶存在性相关的命令行操作）——2026-10-20 实测 WSL 里 `age`、`mc`、`mysqldump` **三个都不在**（`command -v` 三连 MISSING）。`deploy/backup/backup.sh` 开头就查 `age` 并退 1，所以"执行备份 + 验备份解得开"不装工具永远跑不动；桶也只能去控制台手工建。
> 4. **要另搭一套监控栈 / 还欠一页文档**（Prometheus 抓取、Grafana 仪表盘要先起 monitoring overlay 并配 `PB_SECURITY_INTERNAL_CIDRS`；管理 API 参考页只有设计文档里的表格，官网没有面向建站者的那一页）。
>
> 以前这里还有第五类"**真·未覆盖的功能缺口**"（`max_level` 对定时推送的截断），2026-10-20 已经用 `IngestServiceTest` ⑫ 关掉，见下方「功能验证（服务端）」。
>
> 一条经验：清单里凡是写死数字（版本号、commit sha、测试计数）的判据都会腐烂，且腐烂得毫无声响。这几轮已经抓到四组——站点徽章挂 `1.0.0`、Windows 关于页判据写 `1.0.0`、测试计数写 197/553、端到端探针的 `23/23`（这一组里 `site/overview.html` 与 `site/client-api.html` 是改完"四处"之后复查才抓出来的漏网，说明"改完了"必须由命令判、不能由印象判）。现在这类判据统一改成"现读现比"的命令，并由 `scripts/release-verify.sh` 兜底：版本徽章、Java 用例总数都已经有了机器比对，写进文档前先想一句"这条下一版还成立吗"。
>
> 还有一类不写在清单里也会腐烂：机器产物的**字节顺序**。`testvectors/vectors.json` 之前每跑一次 core 测试就换一套 key 顺序，内容一字未改、diff 却四十多行——那种噪声比错数字更坏，因为它教会下一个人在协议向量面前忽略 diff。现在 `CryptoVectorsTest` 显式按 key 排序（`ORDER_MAP_ENTRIES_BY_KEYS`），连跑两次 sha 一致。

---

## 前置条件

- [x] 三端本地全量复跑通过（2026-10-20 实跑，下面每条都写了真判据）：
  - 服务端 `mvn -B -pl server -am test`（凭据来自 `deploy/env/local.env`，只走环境变量）→ BUILD SUCCESS，surefire 汇总 **270 条 / 跳过 0**（server 232 + core 38，2026-10-20 夜里补完设备侧坏请求体那 5 条用例后的实跑；当天早先是 261 条那一版，中间还有一版 265 是只补了管理台那 4 条），与 `.github/workflows/ci.yml` 末尾的基线断言同口径。**要真的 source 它**：112 个用例带 `@EnabledIfEnvironmentVariable(PB_S3_ACCESS_KEY)`，没有桶凭据时它们整批 skip，而 `mvn test` 的退出码仍然是 0——270 条听着像全绿，缺凭据时照样有 112 条整批 skip（当天实测的是 261 总数那一版：只执行了 149 条）。另外 `mvn` 必须跑在 JDK 21 上（`server/pom.xml` 要 `release=21`），Windows 上 `JAVA_HOME` 若指向 JDK 8 会在 `pushbox-core` 直接炸 `class file version 61.0 ... only recognizes up to 52.0`
    - 本机复跑要先绕开一个环境坑：`127.0.0.1:3306` 现在是另一套 `--network host` 的 `mysql8`（mysql:8.0）容器在听，`pushbox` 用户对 `pushbox` 库没权限，Flyway 报 `Access denied for user 'pushbox'@'%' to database 'pushbox'`，106 条 DB 用例整批红——这是拓扑不是缺陷。本项目那套 `pushbox-test-mysql-1` 不发布 3306，Windows 也路由不到 docker 网桥（`172.18.0.3:3306` 不通）。可用的复现法：临时起一个一次性容器（`-p 13306:3306`，带上 `--ngram_token_size=2`，否则 FULLTEXT 的 ngram 索引按默认 3 建，和 CI 不是同一份 schema），用 `SPRING_DATASOURCE_URL` 环境变量指过去（**不动 `application-dev.yml`**），跑完 `docker rm -f`；桶沿用 `local.env` 那台存储机。本机要自己建桶就别再找 `minio/mc` 镜像（2026-10-20 实测 Docker Hub `minio/mc` 与 `minio/minio` 都 404、`dl.min.io` 上的 mc 二进制 410 Gone），用 `amazon/aws-cli` 容器（本机 WSL 实测能建通）或按那一节现装 AWS CLI v2（本机目前没有 `aws` 二进制），命令写在 `deploy/QUICK-START.md`「桶要建一次」
  - 管理台 `cd web-admin && npm ci && npm run build` → `vue-tsc -b` 无报错，`dist/index.html` 非空、25 个产物文件（CI 断言的正是这两条）
  - Windows `dotnet test PushBox.Crypto.Tests -c Release` → **556/556、退出码 0**（此前 `BubbleView` 的视觉树定位器过时挂着 8 条红；判分要把全量日志落盘再看退出码，`| tail` 会把失败压成"看着像绿"）
  - Android `./gradlew :app:assembleDebug` 与 `:app:assembleRelease` 均 BUILD SUCCESSFUL：debug 包 26.6 MB、release 包 12.7 MB，`apksigner verify --print-certs` 出 V2 签名（`CN=iamplus`，证书 SHA-256 `99071932aa9084636ba7f5769b818d43903f4cc124f464073e47c35fc38dec4e` —— 换 key 就比对这一串）。同一次构建顺带证明 `outputs/mapping/release/mapping.txt` 确实落在 CI artifact 归档的路径上，**它实测 205 MB**，比直觉里的"几 MB"大两个数量级，配额按这个体量算
- [x] `bash scripts/release-verify.sh` 零 FAIL。**判据是"零 FAIL"，不是"零 WARN"**——WARN 只报数、不判失败，所以别把"有 WARN"当成没过，也别把 PASS 的条数抄进文档（加一条判据它就变）。2026-10-20 与镜像相关的那条 WARN 在两个平台上长得不同且互斥：Windows 没有 docker CLI，报"体积判不了"；WSL 里报"实测 654 MB 超过阈值"。它报的是"这个数该有人看一眼"，不是构建坏了。要在 WSL 里跑才拿得到体积
- [x] CHANGELOG.md 已更新并标注发布日期
- [x] 版本号一致（Android `versionName` / Windows `<Version>` 与 `MyAppVersion` / Docker 镜像 tag / 四个 pom / `web-admin/package.json`，一次由 `scripts/set-version.sh` 写全），且 `versionCode` = `major*10000 + minor*100 + patch` 且**严格大于上一版**（写小了装不上：`INSTALL_FAILED_VERSION_DOWNGRADED`）
- [x] CI 门禁已经真的跑过一遍（工作流被 git 跟踪、四个 job 全绿）——"文件在本机存在"不等于门禁在运行，这一条以前就是这样空了一整版。**2026-10-20 深夜这一条终于有了命令证据：`ci.yml` 对 `publish` 的 dispatch（`run 39377846888`，head `60c86ad`）四个 job 全 success**，其中第 6 步「等 RustFS 就绪并建桶」（改成 runner 自带 AWS CLI v2 的那一步）与第 8 步「断言 DB 闸门用例真的执行了」（`skipped == 0` 且用例数 ≥ 261）都是**第一次在 runner 上执行并通过**；`build-apk.yml` 同日 dispatch（`run 39376573834`，同一个 head）16 步里该 success 的全 success，`Verify release APK signature` 第一次真跑到。当天这条判据来回变了三次，都把过程留着别删：先对 `publish` 点过一次 `docker-publish.yml` 的 dry-run dispatch，`run 39362849035` 全 9 步 success（凭据校验 → 版本号闸门 → dry-run 闸门 → buildx → **真登录 Hub** → metadata → 多阶段构建 → 标签清单，约 3 分钟），而当时 `hub.docker.com/v2/repositories/iamplusdev/push-box/tags` 仍是 **404**（对照组 `library/mysql` 200）——两件事合起来才是"闸门与凭据有效、且确实一个字节都没推上去"的证据。随后按维护者的决定把 `publish` 推成 `main`，`ci.yml` **第一次在 runner 上真跑就红了两个 job**：`server` 的建桶步拉 `minio/mc:latest` 报 `repository does not exist`（exit 125，实测 mc/minio 两个镜像在 Docker Hub 都已 404、`dl.min.io` 上的 mc 二进制 410 Gone），`android` 的 `Set up Android SDK` 死在 `android-actions/setup-android@v3` 要装同样已下架的 legacy `tools` 包。两处改成"runner 预装 AWS CLI v2 建桶"与"runner 预装 SDK + 一步入镜检查"后先在本地实测过（逐条见 CHANGELOG 同日那三条），到 `60c86ad` 才拿到 runner 的绿。同日还点过三次 `build-apk.yml`：`run 39370419457`（head `685acb4`）SDK 入镜检查 success、失败挪到 Gradle 配置阶段的 KSP 插件解析；构建前新加的仓库探测步（`run 39373315480`，head `171b076`）打出 **runner 对 `maven.aliyun.com` 三个仓库整片 502**，而当时镜像写在 `settings.gradle.kts` 最前面，插件解析就被带进"没找到"分支（本机在墙内看不见这个 502，所以本地一直绿）；改成"默认只走官方源、开发机用 `android/local.properties` 里的 `pb.maven.cnMirror=true` 开镜像"后，`run 39375007556`（head `5f31903`）1–9 步全 success、只剩第 10 步红在 keystore 的 base64 格式，也就是下面"Android 发布"那一节。
  **这一条勾的是"这套 workflow 代码在 runner 上全绿过"，不是"main 上绿"**：`main` 仍停在 `f6b9a53`（那次红过），在它上面重跑要先把 `publish` 推成 `main`，而那会连带 `docker-publish.yml` 真推 Docker Hub（push 事件不受 dry_run 影响，不可逆）——所以推 `main` 这一步单独等维护者点头。另外**这一条不能拿 `docker-publish.yml` 的 dry-run 顶账**：它不跑测试、不签 APK、不出 MSI。复核口径用命令别抄条数（按 workflow 分别数，比在 `/actions/runs` 上 grep `name` 干净——那里面还混着 actor 与仓库名）：
  ```bash
  for f in ci.yml docker-publish.yml build-apk.yml build-msi.yml; do
    printf "%-22s " "$f"
    code=$(curl -sS -o /tmp/wf.json -w "%{http_code}" "https://api.github.com/repos/iamplusdev/push-box/actions/workflows/$f/runs?per_page=1")
    if [ "$code" = 200 ]; then grep -oE '"total_count": *[0-9]+' /tmp/wf.json; else echo "HTTP $code = 这个 workflow 从没被 GitHub 登记过，更没跑过"; fi
  done
  ```
  **两种"没跑过"长得不一样**：200 + `total_count: 0` 是"登记了但一次没跑"，404 是"文件在仓库里、GitHub 连 workflow 资源都没建"（`build-apk.yml`/`build-msi.yml` 在推 `main` 之前就是 404 这个形状，推过之后必然变成 200 + ≥1，所以这一句只教你认形状，别拿它当现状）。勾这一条的判据是**四行都出现 `total_count` ≥ 1 且最新一次 `conclusion=success`**——2026-10-20 深夜实测四个出制品/门禁 workflow 都满足（`ci.yml` 与 `docker-publish.yml` 与 `build-apk.yml` 最新一条 success，`build-msi.yml` 只有 push `main` 那一次 success；条数会涨，别抄），**唯独 `update-dockerhub-description.yml` 仍是 404，即从没被 GitHub 登记过，也**没法手动补跑**（没有 workflow 资源就没有「Run workflow」按钮），原因与它第一次跑绿的形状见下面「Hub 仓库说明」那一节**。上面那段 curl 现在会返回 **403**（同一个 IP 匿名请求 60 次/小时，实测限流），判据不变，换个有 token 的口子或隔一小时再读。
- [x] 发布分支就是要打的那份代码：`git status` 干净，且本地 tip 与三端一致——判据用这条命令，别把 commit sha 抄进文档（抄了下一提交就腐烂）：`for r in github gitee gitea; do git ls-remote $r refs/heads/publish; done` 三行输出必须相同且等于 `git rev-parse publish`；2026-10-20 实测三端一致且无 tag。四个 CI job 的本地等价命令同日全绿，见上一条与下方三端复跑

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
- [x] TLS 口径确认过：`useSSL=false` 与 `http://` 意味链路明文可听，跨主机部署应改 `useSSL=true&requireSSL=true` 与 `https://`。2026-10-20 核对该口径**确实写在用户会读到的两处**：`deploy/env/prod.env.example` 的 DB 段与 S3 段各自解释了"什么时候能留明文、什么时候必须翻 true、为什么默认不翻"（默认不翻是因为内部 MySQL 没开 TLS 时驱动直接连不上，宁可用不安全也不能起不来）

### 安全审查
- [x] 无明文密钥提交到 Git（`git grep -I -n -E '(PASSWORD|SECRET|KEY)=[A-Za-z0-9+/]{16,}'` 只应命中占位符；真实值只在 gitignored 的 `.env` / `local.env`）
- [x] `.idea/` 仍被忽略：IDEA 会把运行配置里的 Environment variables 原样写进 `.idea/workspace.xml`，本机这份现在就带着 dev 的库口令与桶密钥。用 `set-user-env.ps1`（写用户环境变量）而不是往运行配置里填，就是为了让明文不进工程目录——填过的那次要靠 `.gitignore` 兜，所以 `scripts/release-verify.sh` 直接断言这三条忽略规则还在（`deploy/env/local.env`、`.env`、`.idea/`）
- [x] `deploy/Caddyfile` 保留 `@metrics path /actuator/*` → `respond 403`；服务端侧还有第二道门（`InternalAccessFilter` 只放行环回 + `PB_SECURITY_INTERNAL_CIDRS`），确认没有把其中一道当成全部依赖。**注意 Caddyfile 只挡 `/actuator/*`，`/healthz` 是经 Caddy 可达的**——真正把运维端点关在里面的是第二道门（`PB_SECURITY_INTERNAL_CIDRS` 留空）
- [x] 管理台登录限速：连续失败按来源 IP 锁定并返回 429（默认 5 次 / 5 分钟窗口）；Caddy 在前面时服务端看到的是代理地址，别指望它区分客户端
- [x] 日志抽样：2026-10-20 在**真跑起来的容器**上抽过（`docker logs <容器> 2>&1 | grep -icE 'recovery code|BEGIN PRIVATE|secret|access.?key|password'` → **0 命中**，命中的是 `PB_*_SECRET/PASSWORD/KEY` 这类键名赋值形态，也是 0）。"消息正文不进日志"这一半是静态判据而非抽样判据：`grep -rn -E 'log(ger)?\.(info|warn|debug|error)\([^)]*\b(getBody|body|content|text)\b' server/src/main/java` → 空。抽样只能证明"这段时间的日志里没有"，代码里不存在把正文联到 logger 的调用点才是那句 §14 口径的根据
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
- [x] 新版本含 Flyway 迁移时，先跑一次并观察 `flyway_schema_history` 只追加、`success=1`。**本轮确实有迁移**（相对 tag `v1.0.0` 的增量是 `V7__media_channel_index.sql` 与 `V8__scheduled_push_tasks.sql`，用 `git diff --name-only v1.0.0 <发布分支> -- server/src/main/resources/db/migration` 现查，别凭印象判断"这版没动库"）。2026-10-20 在全新卷上起过一次完整栈，查得 `1 init … 8 scheduled push tasks` **八行、`success` 全为 1**、1–6 未被改写。注意判据的边界：这台机器上**没有生产库可拷**，所以这条证的是"迁移链本身能干净前滚"，不是"你那份数据前滚得动"——真上线前必须在生产副本上再走一遍同一条命令
  ```bash
  docker exec <mysql 容器> sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" pushbox -N \
    -e "SELECT version,description,success FROM flyway_schema_history ORDER BY installed_rank"'
  ```

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
- [x] CI 上四个 keystore secrets 配置**且格式正确**。四个都配了，但 2026-10-20 第三次 dispatch（`run 39375007556`）第一次真去用它时红在解码：`Illegal base64 character 2d`，`2d` 就是 `-` ——**`ANDROID_KEYSTORE_BASE64` 里那一串不是标准 base64**（最像 Windows `certutil -encode` 的产物，带 `-----BEGIN/END-----` 包头与换行；也可能是 base64url 的 `-`/`_`）。判据与正确写法已写进 `build-apk.yml` 文件头，`app/build.gradle.kts` 现在会把"看到的形状"（长度 / 有无 PEM 包头 / 有无换行）报出来再失败，**不打印内容**（那一串本身就是密钥材料）。**这一条到第四次 dispatch（`run 39376573834`，head `60c86ad`）才算勾上**：重存 secret 后 `Build release APK` 与 `Verify release APK signature` 两步都在 runner 上 success，即四个 secrets 全被真用过。**但勾的是"secrets 能用"**——那次跑绿时 `Verify release APK signature` 只断言"存在签名且不含 `android debug`"，**没拿指纹比对**，而 runner 日志匿名取不到（`/logs` 实测 403 "Must have admin rights"），所以当时"两边同一把 key"只有两条间接证据：本机按 CI 形式喂 env 跑出的 APK 指纹与基线逐字相同（下一条），加上 runner 那份不是 debug 签名。**这个缺口 2026-10-20 深夜已补成机器断言**：该步现读 `android/release-cert.sha256`，把 `apksigner --print-certs` 出的每个 signer SHA-256 逐条比对，不符就红并打印两边指纹（基线与实际，都是公开信息，不是密钥）。四种分支是从 YAML 里抽出这一步在 bash 真跑的：正例 rc=0、换成另一串 64 位十六进制 rc=1、文件缺失 rc=1、文件写成大写/不足 64 位 rc=1。**这条断言也已经在 runner 上绿过（`run 39381072797`，head `5293801`，第 10 步 success）**，所以"CI 那份 APK 用的就是基线那把 key"现在是 CI 自己验过的事实，不再靠间接推断。此前那句"配好了"只算名字存在，从来不算能用——这个区别就是那次红出来的
- [x] 签名验证通过：`build-tools/37.0.0/apksigner.bat verify --print-certs app/build/outputs/apk/release/app-release.apk` → V2 签名，`CN=iamplus, OU=iamplus, O=iamplus, L=ZhengZhou, ST=HeNan, C=CN`（本机 `assembleRelease` 走的是 `android/local.properties` + `android/dev/pushbox-release.jks`；CI 走 secrets，两边必须是同一把 key）。**"CI 那条 env 路径"2026-10-20 第一次在本机跑通并证伪了这条风险**：临时把四个 `ANDROID_KEYSTORE_*` 环境变量按 CI 的形式喂进去（`base64 -w0 dev/pushbox-release.jks` + 另三项取自 `local.properties`）跑 `assembleRelease` → 12,740,698 字节的 APK，`apksigner verify --print-certs` 出的证书 SHA-256 与下一条钉的基线 `99071932…dec4e` 逐字相同，即 env 路径用的就是同一把 key。这条 env 路径会把 keystore 明文解到 `android/build/pb-release.keystore`（CI 上同一个路径随 runner 一起销毁，本机不是），所以本机这样跑完必须删掉——2026-10-20 这次已删，复跑后也要删。
- [x] **签名证书与上一版一致**（换 key 等于老用户必须卸载重装，历史消息全丢）：v1.1.0 是首个公开版本，没有上一版可比，所以这一轮把基线钉成指纹 —— 证书 SHA-256 `99071932aa9084636ba7f5769b818d43903f4cc124f464073e47c35fc38dec4e`。**这一串现在有三个落点，靠脚本绑成一处**：`android/release-cert.sha256` 是机器读的基线（`build-apk.yml` 的签名核对步现读现比），本节是给人看的口径，`scripts/release-verify.sh` 断言"文件是 64 位小写十六进制 + workflow 真在读它 + 清单里那一串与文件逐字相同"，任一处腐烂就 FAIL（三条负例——清单被改、workflow 不读、文件写成大写——都是真跑出来的 rc=1，不是看一眼写的）。以后真要换 key 是有意的破坏性动作：先写 CHANGELOG，再改 `android/release-cert.sha256`，别让 CI 跟着 secrets 一起悄悄换了口径
- [x] Keystore 与 `mapping.txt` 备份到安全位置（不在 Git 中；R8 崩溃栈全靠 mapping）——2026-10-20 维护者已手工备份。**这一条的勾是人的陈述，不是命令的证据**：脚本验不了备份介质存在或可读，接手时请真去解开一次。另外两条口径留着别忘：不换 key 意味着这把 key 要签后续所有版本，丢了就永久签不出升级包；`mapping.txt` 只进 workflow artifact 时，公开仓保留期上限 90 天，过期再也 retrace 不了，所以自存的那一份才是长期依据

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
- [x] 「检查更新」只列 `pushbox-release-*` 资产的**代码层已核对**：`android/app/src/main/java/cn/iamplus/pushbox/net/UpdateApi.kt:70` 用 `a.path("name").asText("").startsWith("pushbox-release-")` 过滤，且不回落到 debug 包（同文件 63 行注释记着这条决定）。**没有验的是运行时**：GitHub Releases 的 `total_count` 实测为 0，`releases/latest` 现在根本没有可返回的东西，所以"只有 debug 包时提示『暂无可安装的正式版本』"这句在发布前无法验——它要等第一个打了 `v*` 的 Release

### 性能检查
- [ ] 冷启动时间 < 3 秒（中端设备）
- [ ] 消息列表滚动流畅（60 FPS）
- [ ] 内存占用 < 200MB（100 条消息场景）

---

## Windows 发布

### 安装包验证
- [ ] `setup.exe` 能通过 Windows SmartScreen（需代码签名证书；未签名时 CI 只告警不失败，发布说明里要写清）。2026-10-20 现状：`CODESIGN_CERT_BASE64` / `CODESIGN_PASSWORD` **两个都没配**（GitHub Secrets 里只有 4 个 `ANDROID_*` + 2 个 `DOCKERHUB_*`），所以 v1.1.0 的 MSI **必然未签名**——那就必须把"会弹 SmartScreen、点『更多信息→仍要运行』"写进官网安装页与 Release 说明。**这一句两处都补上了**：官网在 `site/install.html`「安装包」小节加了 `callout warn`，Release 说明在 `build-apk.yml` 与 `build-msi.yml` 的发布步骤里按 `${{ secrets.CODESIGN_CERT_BASE64 }}` 是否为空**条件写入**（配了证书这一段自动消失；两个 workflow 都带上这句，是因为它们在同一 tag 上抢着 `gh release create`，谁先 create 谁定正文，只写一处就会随机丢）。**这两处 CI 改动只在本地验过**：`bash -n` 过，再把 YAML 里那段 run 抽出来用一个假 `gh` 跑过两种状态，确认未签名时正文多出那一段、签了名时一字不差地回到原样——没打过 `v*` 标签，所以 runner 上没跑过。**这条仍然不能勾**：勾它要的是证书本身，不是文案
- [ ] 卸载只清"指向本程序目录"的注册表键与开机自启项；**用户数据（`%LOCALAPPDATA%\PushBox\`）默认保留**，重装即恢复——这是设计口径，不是漏清
- [ ] 单实例机制正常（第二次启动提示已运行）
- [x] 版本号三处同源。**判据里不写具体数字**——写死就会腐烂，这一条之前挂着的是 `1.0.0`，而 `scripts/set-version.sh` 根本不碰它。现在的口径：`windows/PushBox.Windows/PushBox.Windows.csproj` 的 `<Version>` 是**唯一来源**，`windows/installer/PushBox.iss` 用 `#ifndef MyAppVersion` 兜底、真值由 `build-installer.sh` 读 csproj 后 `/DMyAppVersion` 传入，`AppVersion={#MyAppVersion}` 再进注册表；`scripts/release-verify.sh` 已经是"去 csproj 现读一个值再比"，不是比字符串常量
- [ ] 装机后「关于」页显示的字符串与上面同源（这一半要真跑一次 `setup.exe` 再打开关于页，本轮没装过；上面那条勾只证配置层同源）

### 系统兼容性
- [ ] Windows 10 (19041+) 安装成功
- [ ] Windows 11 运行正常
- [ ] 高 DPI 屏幕下 UI 不模糊
- [ ] 托盘图标右键菜单正常
- [x] 主窗口冷启不崩（`XamlParseException` 类问题由 `ThemeTest` 在 CI 拦，见 `dotnet test`）：2026-10-20 在 Windows 上实跑 `dotnet test PushBox.Crypto.Tests -c Release` → **556/556、退出码 0**，其中含 `windows/PushBox.Crypto.Tests/ThemeTest.cs`。它拦的是真出事的那一类——注释里记着实测两次：顶栏 ⚙ 拿了 `Text.H2`、返回 ← 拿了 `Text.H3`，同一种写法换 `StaticResource` 就当场抛 `XamlParseException`。**边界要说清**：这证的是"资源字典与视觉树的拼装不炸"，不证 Windows 10/11 各版本上的真实冷启，那半仍在上面四条里

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

- [x] 以非 root 用户运行（`USER pushbox`）：Dockerfile 第 141 行有 `USER pushbox`，且在真跑起来的容器上复核过进程身份——`docker exec <容器> id` → `uid=10001(pushbox) gid=999(pushbox)`，宿主侧 `docker top <容器> -o user` 同样显示 `10001`（只看 Dockerfile 不算：真起过的进程才是证据）
- [x] 健康检查配置正确（TCP 探活，非 curl——JRE 镜像里没有 curl，且 `/healthz` 会真连库）
- [x] 入口脚本不打印任何密钥片段（只报键名）

### 标签管理

这四条原本都是"凭印象打勾"的形状，现在写成一条命令就能自证。前三条的根据是 2026-10-20 读过 `.github/workflows/docker-publish.yml` 的 `docker/metadata-action` 配置：`type=ref,event=branch` + `type=sha,prefix=sha-,format=short` + `type=semver,pattern={{version}}` + `type=semver,pattern={{major}}.{{minor}}` + `latest` 只在**正式（不含 `-`）标签**时生效。也就是打 `v1.1.0` 应得到 `1.1.0`、`1.1`、`latest`，push main 应得到 `main`、`sha-<7位>`。**这三条要等 `v1.1.0` 真的存在才勾得上**：tag 还没打，打 tag 与推 tag 都算不可逆的远端动作、要单独确认，Hub 上那个仓库目前仍是 404（见第四条），所以现在只能是空的。

顺带一句别误读：同一天那次 dry-run 全绿只证明**凭据能用、构建链在 GitHub runner 上编得过**（见「前置条件」里 CI 那条），它证明不了这三条——dispatch 是分支事件，metadata 在这种事件上按配置根本不会产出 `1.1.0`/`latest`，所以别拿"那次跑出来的标签清单"当版本标签的验收证据。

**版本号只有一个来源，就是仓库里已提交的那份**（`pom.xml`；`scripts/set-version.sh` 会把同一个 X.Y.Z 一次写全三端构建配置、两份 compose 与官网页面）。workflow 不再在构建现场改写它；取而代之的是构建前的一道闸门：**在 `v*` 事件上**，tag 说的版本、pom 写的版本、compose 钉的标签三者必须相等，否则在 buildx 与 login 之前就红（分支事件只要求 pom 读得出 X.Y.Z，那个值拿去当镜像标签）。发布顺序因此是**先 `bash scripts/set-version.sh X.Y.Z` 并提交，再在那个提交上打 `vX.Y.Z`**——反过来的话 tag 触发的那次构建会被闸门拦下，报错信息里直接写了该跑哪条命令补。这条判据本机验过：`tag v1.1.0` + pom 1.1.0 通过；`tag v9.9.9` + pom 1.1.0 退 1；pom 写成 `1.1.0-SNAPSHOT` 也退 1（取值把 `<version>` 尖括号一起锚定，否则 `-SNAPSHOT` 会被读成 `1.1.0`，闸门就成了假绿）。

版本标签只在 `v*` 事件上打，push main 只出 `main` 与 `sha-*`，这是**故意的**：如果每次合并都覆盖一次 `1.1.0`，钉着它的人今天拉到的和上周就不是同一个镜像，"回滚到 1.1.0"这件事随之消失。**这一口径 2026-10-20 由维护者拍板，不是遗漏**——备选过的"main 也推 `X.Y.Z`"与"main 推 `1.1.0-main.<run号>` 预发布标签"都已否；要跑当天 main 的镜像就认 `sha-<7位>` 那个标签。

镜像本身也带版本：metadata 步骤用 `labels:` 把闸门从 pom 现读的值盖到 `org.opencontainers.image.version` 上，发布后不用靠标签名猜自己跑的是哪一版。这一步不是锦上添花——2026-10-20 在 WSL 实测本机 `docker compose build` 出来的那份镜像，`docker image inspect pushbox/server:1.1.0 --format '{{json .Config.Labels}}'` 读到的是**基底继承来的** `{"org.opencontainers.image.title":"ubuntu","org.opencontainers.image.version":"26.04",...}`，也就是说谁照这行查版本，查出的是 Ubuntu 的版本号。CI 那份由闸门把值盖成 `1.1.0`；**本机现场编出来的仍然会是 `26.04`**（labels 由 workflow 注入，`deploy/Dockerfile` 里没有版本 `ARG`），别把这条判据拿去核本地构建。
```bash
docker image inspect docker.io/iamplusdev/push-box:<tag> --format '{{index .Config.Labels "org.opencontainers.image.version"}}'   # 期望 = pom 里那个 X.Y.Z
```

**发布后核对（本机直连不了 Hub，在 WSL 里 `curl` 可以）：**
```bash
curl -sS https://hub.docker.com/v2/repositories/iamplusdev/push-box/tags?page_size=50 | grep -oE '"name":"[^"]+'
```
- [ ] `latest` 标签指向最新稳定版（上一条命令里 `latest` 应等于本次 semver 那份 manifest）
- [ ] 语义化版本标签正确（如 `1.1.0`、`1.1` 两个都要在）
- [ ] SHA 短哈希标签生成（如 `sha-a1b2c3d`）
- [x] `deploy/docker-compose.yml` 的 `image:` 与本次 tag 一致：**这条 2026-10-20 起有机器兜底了**——上面那道闸门在 tag 事件上现读 pom 与 compose 比对，不一致直接红，不再靠人记得去 `grep`。本机核对仍是 `grep -n 'image: docker.io' deploy/docker-compose.yml` → `iamplusdev/push-box:1.1.0`，等于 `pom.xml` 的 `<version>1.1.0</version>`。**但注意这条勾只证"写对了"，不证"拉得到"**：2026-10-20 实测该 Hub 仓库 `.../v2/repositories/iamplusdev/push-box/tags` 返回 **404（对照 `library/mysql` 返 200，所以不是网络假象）= 从未创建**。回落已经实测过：`docker compose -f deploy/docker-compose.yml --dry-run up -d` 的计划是 `Pulling → manifest unknown → Building → naming to docker.io/iamplusdev/push-box:1.1.0`，compose 会自动改成本地构建，新用户不加 `--build` 也起得来（代价是首次要现场编、且用不上 CI 的 `latest/sha-*` 标签）

### Hub 仓库说明（`update-dockerhub-description.yml`）

这是五个工作流里**唯一从没跑过、也唯一带 `paths:` 过滤**的那个，形状与另外四个不一样，别拿上面那套"点一次 dispatch 就绿"的经验套它。判据用这条命令现查（按文件名查 workflow 资源，不需要 token）：

```bash
for f in ci.yml update-dockerhub-description.yml; do printf "%-32s " "$f"; curl -sS -o /tmp/w.json -w "%{http_code}\n" "https://api.github.com/repos/iamplusdev/push-box/actions/workflows/$f"; done
```

2026-10-20 深夜实测：`ci.yml` **200**，`update-dockerhub-description.yml` **404**。404 不是"没权限看"，是 **GitHub 连这个 workflow 的资源都没建**——它从来没被任何一次事件评估认领过。三个后果是连着的：Actions 侧栏的工作流列表里没有它（实测侧栏只有 4 条）、网页上没有「Run workflow」按钮（直接访问那个 `.yml` 地址返回 "Workflow not found"）、REST 的 dispatch 也要先有 workflow 资源才点得动。**所以"给它手动补跑一次"这条路本身不存在**，不是没 token 的问题。

唯一那次 `main` 推送（`f6b9a53`，2026-10-20 03:24）为什么跳过了它，四条证据：

- `/repos/.../events` 里那一秒是一条 `CreateEvent{ref:"main", ref_type:"branch"}`，`main` 在那之前不存在——也就是那次推送是**新建分支**，事件里的 `before` 是全零 SHA，GitHub 没有可比的基线，算不出"这次改了哪些文件"，带 `paths:` 的过滤器就判不匹配。
- 同一次推送把另外 4 个工作流全触发了（`/actions/runs` 里 event=push、branch=main、同一秒、同一个 `f6b9a53`），它们只有 `branches: [main]`、没有 `paths:`。**同一事件、同一分支、同一 commit，唯一变量就是 `paths:`** —— 这是这轮排查里最干净的一组对照。
- 不是"README 没变"：`commits?sha=main&path=README.md` 数出来 README 在那 17 个提交里被改过 3 次（`4c1717d`、`45f1656`、`9d5d70d`），文件也确实在推上去的那棵树里。
- 也不是"文件坏了"：`main` 与 `publish` 两边都有这个文件，Python `yaml` 解析通过、`on:` 里 `push` 与 `workflow_dispatch` 都在，行尾是 CRLF——另外 4 个同为 CRLF 的工作流照跑，所以行尾不是变量。

上面第一条那个"新建分支时 GitHub 算不出改动文件集"是**推断**，不是文档原文——GitHub 的 events/过滤器文档没写新建分支（`before` 全零）时 `paths:` 取什么值。推断的底气在于这组对照没有第二个变量：同一事件、同一分支、同一 commit，README 确实改过三次，跑掉的四个都没有 `paths:`，没跑的那个是唯一的 `paths:`。想把它证成事实，得另建一条带 `paths:` 的临时分支推送做实验，那要在公开仓库上开一个用完即删的分支，本轮没做。

**它第一次能跑绿长什么样**：`main` 现在已经存在，从下一次"改动含 `README.md` 或这个文件本身"的 **`main` 常规推送**起，这条会被自然触发并顺带登记下来（此后才谈得上手动 dispatch）。而 Hub 侧还有个前置：`iamplusdev/push-box` 这个仓库至今**没被创建过**（上面「标签管理」第四条那次实测是 404），`dockerhub-description` 这个 action 只 PUT 描述、不建仓库，所以在那之前就算触发也只会红在 404。把仓库建出来这件事由同一次 `main` 推送里的 `docker-publish.yml` 真推镜像来完成——**也就是说这条欠账并不"零不可逆"，它的绿和推 `main` 是绑在一起的**。

诚实边界：Hub 那个 404 是 2026-10-20 早间在 WSL 里实测的；当晚想复验时 Windows 侧代理客户端没在跑，`hub.docker.com` 从本机与 WSL 都是**连不上**（`000`，不是 404），所以"仓库仍不存在"记的是早间那次实测，不是当晚现查。要复验就先把代理客户端起来再跑 `curl -sS -o /dev/null -w '%{http_code}\n' https://hub.docker.com/v2/repositories/iamplusdev/push-box/`。

### 部署验证
- [x] `docker compose up -d` 能成功启动所有服务。**主文件里不该再有任何"只有监控才需要"的必填项**：compose 的 `${VAR:?}` 插值作用于整本文件、与 profile 是否激活无关，所以 `GRAFANA_ADMIN_PASSWORD` 一旦留在主文件，"只起服务端"的部署会在解析阶段就报 required variable missing（prometheus/grafana 现在在 `docker-compose.monitoring.yml`）
- [x] 改了 `PB_SERVER_PORT` 的部署要确认进程真的听在这个端口：compose 的端口映射、`healthcheck`、Caddy 上游三处都读它，第四处是 `application-prod.yml` 的 `server.port`。缺第四处时容器会一直是"起了但映射到没人听的口"
- [ ] Prometheus 能抓取指标（`http://localhost:9090/targets`）：`docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up -d`，并把这条网络分到的子网写进 `PB_SECURITY_INTERNAL_CIDRS`；不写的话进程好好的、targets 全 `403`
- [ ] Grafana 仪表盘显示正常（`http://localhost:3000`，`GRAFANA_ADMIN_PASSWORD` 已改掉默认值）

### 功能验证（服务端）
- [x] 定时推送（v1.1.0 新增）：管理台建一条 `0 * * * * *` 的任务 → 列表里 `last_result=ok`、`last_fire_at` 逐分钟推进 → 库里 `messages.client_msg_id` 是 `sched:<task_id>:<分钟序号>` 且**每分钟恰好一行**（去重靠它 + `uk_msg_idem`）→ 停用后不再增长 → 删除后任务数归零
- [x] 五个 cron 段的表达式要被 `invalid_param` 拒掉（Spring 的 `CronExpression` 要 6 段），`level` 传不认识的值不能悄悄变成别的级别
- [x] 定时任务打到 `max_level` 低于任务级别的频道时的行为已复核并已有测试——**这条原来的写法是错的**：它写的是"不能发出去"，而代码的实际口径是"**降级后照发**"。根据是 `core/src/main/java/dev/pushbox/core/model/Level.java:23` 的 `truncateTo()`（`this.ordinal() <= max.ordinal() ? this : max`，只向下、不升级），投递路径在 `IngestService` 里把 `request.level()` 截断到 `channel.max_level` 之后仍继续入库并计数；`ingestInternal()`（定时推送走的那条）与公开 `/message` 走的是同一个受理方法，所以两端共用这一把尺子。创建任务时只按**正文上限**拦（`ScheduledTaskLogicTest` ⑪b「存一个永远发不出去的任务没有意义」），**不按 level 拦**——因为 level 超上限根本不会"发不出去"。
  2026-10-20 补齐：缺口不在我上一轮以为的 `ScheduledTaskLogicTest`——那个类里 `IngestService` 是 mock，`captor.getValue()` 只能看到"请求发出去了"，永远看不见落库的 level，写进去只会得到一条假的绿。真正的截断断言在 `server/src/test/java/dev/pushbox/server/service/IngestServiceTest.java:259` 的 ⑫ `internalPathIsTruncatedLikeThePublicOne`：直接调 `ingestInternal()`，同时断言 `resp.level()` 与库里 `messages.level` 都从 `error` 变成频道的 `warning`（此前 ① 只覆盖了公开 `/message` 那条路）。实跑：该类 **12/12**，全量 **261 条 / 跳过 0**（一次性 `mysql:8.4 --ngram_token_size=2` 容器发布到 13306，靠 `SPRING_DATASOURCE_URL` 指过去，**没动 `application-dev.yml`**，跑完 `docker rm -f`）
- [x] 多实例对同一张 `scheduled_tasks` 轮询不会重复投递：`next_fire_at` 的 CAS 认领（`UPDATE ... WHERE task_id=? AND next_fire_at=<旧值> AND enabled=1`）是唯一保证——2026-10-20 用两个服务端实例连同一个库跑过，95 秒内正好 2 行

---

## 文档同步

- [x] `README.md` / `deploy/QUICK-START.md` 的安装步骤与实际流程一致（尤其 env 变量清单与密钥生成命令）
- [x] `CHANGELOG.md` 包含本次发布的所有重要变更
- [x] `docs/PushBox-软件设计方案.md` 的决策与实现一致（安全口径、限速阈值、判死阈值两端差异）
- [x] 官网 `site/` 每个页面的版本徽章、`pushbox-server-*.jar` 文件名与测试计数已跟本次版本对齐（页面数量不写死：`release-verify.sh` 的导航闸按 `site/*.html` 实际有几个来判，加页面不用回来改这条）。根因也堵了：`scripts/set-version.sh` 现在会一起改 `site/*.html`，`scripts/release-verify.sh` 多了一条"站点徽章必须只有一种取值且等于 pom"的断言（v1.1.0 这轮就是它抓出来的：构建配置全对，站点还挂着 1.0.0）
- [x] 文档里的测试计数改成实测值：**Java 270（core 38 + server 232，跳过 0）**、**C# 556**。以前 README / 开发指南 / 测试指南 / `site/` 四处都写 197 与 553，是两版之前的数字。这一类漂移现在也有闸了：`scripts/release-verify.sh` 以 `ci.yml` 的 `if tests < N` 为唯一真值，把 README、两份指南、本清单、RELEASE-SUMMARY、CHANGELOG 与 `site/` 里所有 `core a + server b` 写法求和后比对，取值不唯一或与基线不等就 FAIL（2026-10-20 实跑 10 处、全部 261）。`docs/AUDIT-REPORT.md` 故意排除——它把"旧声称值"和"复验值"并排留着当证据，同一份文档里有两个数，任何单值判据在那里只会误报
- [x] 管理台接口有官网页了：`site/admin-api.html`（2026-10-20 新增）。覆盖 16 个端点（登录 1 + 频道 5 + 设备 2 + 消息搜索 1 + 概览 1 + 定时任务 6），字段表、错误码与 curl 全部**从代码读出来**写的，不是照 `docs/PushBox-软件设计方案.md` 抄的。这一页补上时发现原文档有三类错，都一起修了：`/api/v1/admin/messages` 后面多一段 `search` 的写法从来不存在（真实是 `GET /api/v1/admin/messages?q=`）；REST 补拉的游标被写成 `since_seq`（那是 WS 连接 URL 上的参数，REST 叫 `after_seq`）；入站写入路径被错写成挂在 `/api/v1` 下的形式（真实是 `/message`，前缀由 `pb.ingest.base-path` 配置）。**这三条"错误写法"在本清单里都不能整条照抄**——下一闸的判据是纯字符串匹配，分不出"照着用"和"提醒别这么写"，写全了就是自己把自己判红；这轮就是 212/213 两条初稿照抄了死路径、第一次实跑报 1 FAIL 之后改成现在这个说法的。**当天傍晚这一页打过活服务端了，5 条"从代码读出来"的说法被实测推翻，见下一条**
- [x] `site/admin-api.html` 的契约是**对着运行中的服务端测出来的**，不只是代码级正确（2026-10-20 傍晚：WSL 上把旧栈整体拆掉，用 `deploy/docker-compose-wsl.yml` + `wsl-test.env` 重起一套 `pushbox-test`（mysql 8.4 `--ngram_token_size=2` + rustfs + prod 服务端 + caddy，宿主端口 7404），跑的是 `3c3d764` 那棵树构建的镜像（镜像时间戳 2026-10-19 23:57，傍晚只是用同一份镜像重建容器，二进制没动；它与 HEAD 的 `server/src/main/java`、`web-admin/src` 逐字零差异，只差 `application-prod.yml` 里两处新加的变量消费）；随后 16 个端点逐个 curl，请求/响应都从代码读出来再打。**被实测推翻的 5 条**：① "消息列表每条固定 14 个键"——Jackson 那个共享 mapper 是 `Include.NON_NULL`，值为 null 的键**整个不出现**，所以同一条消息带 title 时 14 键、不带时 13 键，这条规则管着页面上所有响应；② 定时任务的 `last_result`/`last_error`/`last_msg_id` 写成"始终给"——真值是**跑过之后才有**，且成功轮没有 `last_error`、失败轮 `last_msg_id` 被抹掉；③ 路径写错只说了 404——实测**把 `/api/v1` 前缀整个漏掉**那种错返 **HTTP 200 + 一段 SPA 的 HTML**（控制台兜底路由把不认识的请求交给 `index.html`），脚本只看状态码就会一路走下去、取字段全 `undefined`，看着像"服务端返回空数据"；④ 登录锁定写成"5 次失败后锁定"——实测第 5 次仍是 401、**第 6 次才 429**（`Retry-After: 300` 递减），与入站 `/message` 那条的第 5 次就 `403 channel_locked` 不是同一个节律，这个差别以前两份文档都没写过；⑤ token 头的四种坏法以前只写"401"，实测错误提示分两族：缺头/空 Bearer/没有 Bearer 前缀报"缺少设备 token"，乱码/改签名/两段式报"管理 token 无效或已过期"。**另外实测确认写进页面的**：8KB 请求体上限与三种越界提示、`q` 按码点计数（100 个汉字过、101 个不过；60 个 emoji 过；两端空白被 trim；`a_c` 不会命中 `abc`，LIKE 通配符被转义）、`limit` 被夹到 1..200、PUT 是整体覆盖而非补丁（`level` 回落 `info`、`images` 变空数组、正文清空，但 `last_*` 保留）、停用再启用会按 cron 从当下重算 `next_fire_at`、归档频道后任务自动停用并写 `last_error="频道已归档或不存在，任务已自动停用"`、`rotate-secret` 当场解除入站锁定、DELETE 频道后入站返 403 `channel_locked` 而详情仍 200 且历史消息仍可搜。**这一轮暴露的那条，当晚已经改掉了**：4 个用 `@RequestBody byte[]` + `Json.readBytes` 的管理台端点，请求体坏时抛的是 `IllegalArgumentException`，而 `GlobalExceptionHandler` 只有 3 个 handler、不接它，于是返 **HTTP 500 + Spring 默认错误体**（空体反而是 400；尾部多一段垃圾 Jackson 默认不报错，所以那份请求**真的执行了**）。改法是把解析收口成一个共用工具 `RequestBodyJson`（`read` 与 `readTree`），管理台这 4 个端点的失败一律转成 `ApiException.invalidParam("请求体必须是合法 JSON")`（措辞与 `handleBadJson` 一致），**没有**动 `GlobalExceptionHandler`、也**没有**把绑定改成 record：`byte[]` 绑定要先拿到原始字节才能量 8KB/64KB 上限，换成 record 就没地方查了。越界提示原样保留。验证两头：单测 `AdminControllerBodyTest` 4 条（4 个端点 × 4 种坏体 = 16 组全 400 `invalid_param`、启用开关的空体、越界仍是"请求体过大"、正常体照样落库），实跑 4/4 绿；活服务端上把 `mvn package` 出的新 jar `docker cp` 覆盖进 `pushbox-test-pushbox-server-1`（先 `sha256sum` 对上才继续，第一次 cp 其实没落盘，这步不能省），重启后 4 个端点 × 5 种输入的矩阵**全部 400**，与单测同一句话。验证完已把容器 `--force-recreate` 还原成镜像自带的那份 jar（`9e7e4ca0…`，即 `3c3d764` 的产物，**不含这个修复**）——也就是说这套 WSL 栈现在跑的还是旧行为，别拿它的 500 当作"没修成功"的证据，也别拿它的 200 当作契约。**同类问题当晚已按同一口径一起收口**：设备侧也是 `byte[]` + `Json.readBytes` 的写端点（判据=现查 `grep -rn 'RequestBody byte\[\]' server/src/main/java/dev/pushbox/server/web/*.java`）现在与管理台共用 `RequestBodyJson`。**这份名单是八个端点、七处调用**：`ChannelController` 六处（含走 `readTree` 的那个 PATCH 式 `PUT`），`DeviceController.validateAndParse` 一处覆盖 `register` 与 `join` 两条。本条早先写的"9 处，含 `MessageQueryController` 1 处"是错的——那处自己写了 `catch (Exception)` → 400，从来不是 500 源。改之前查过客户端：Android 的 `PushBoxApi.exchange` 非 2xx 直接抛 `ApiError(code, 原始文本)`、不解析错误体，全项目按 HTTP 码分支只有头像那处 `404`；Windows 的 `ApiError` 用 `JsonDocument` 宽容选读，拿不到就当空串；**两端都没有 4xx/5xx 分档、没有对 401/409/429 的特判、没有按码清身份、没有幂等键**（Windows 唯一待发队列是已读上报，不分码一律回队），所以改它只换上屏文案，不动重试与身份逻辑。验证分两档，别写成"全都实测过"：`register`/`join` 是开放接口，在同一套栈上换 jar 前后各打坏 JSON / 非 JSON / 空体，实测 500 → 400，9011 字节仍回"请求体过大"；频道那六个要先过 `DeviceGuard` 的 Ed25519 签名，curl 到不了解析那一跳，**只有 `DeviceControllerBodyTest` 那 5 条单测背书**。**仍未实测的**：多实例（CAS 抢占只看过代码）、真设备侧读取、设备吊销后的 `revoked_at` 会不会出现、图片抓取与 SSRF 防线、浏览器里的视觉排版（本机拿不到视口，截图这一步没做成）。**副作用已清**：探针建的任务全部删除、探针频道全部归档，包括那条尾部垃圾探针意外建出来的。**本条不能整条照抄的写法**：漏前缀那件事只写"把 `/api/v1` 前缀整个漏掉"，不把漏掉之后那个错误路径的字面量写出来，否则下一条闸的纯字符串匹配会自己把自己判红（上一轮就是这么红的，改的是文档措辞不是给闸开口子）
- [x] **文档里的端点路径必须存在于代码**——上面那三类错从此有闸：`scripts/release-verify.sh` 从 `web/*.java` 的 `@RequestMapping`/`@Get…Mapping` 注解（含 `path = "${pb.ingest.base-path:/message}"` 取默认值、含光有注解没有路径的那种）与 `registry.addHandler(handler, "/ws")` 提出真实路由，再扫 `site/*.html` + README + CHANGELOG + 本清单 + `docs/*.md` + `deploy/*.md` 里的 `/api/v1/…`、`/message`、`/healthz`、`/ws`，逐条匹配。`{channelNo}`、`12345678`、ULID 这类同位段先归一化，否则真实端点会被误报成不存在。2026-10-20 实跑：代码侧 34 条路由、文档侧 37 条路径、全部命中（文档侧那个数只是当轮读数，写文档时从 36 涨到 37，随措辞涨落，不是基线；钉住的是代码侧那 34 条）；反向验证是往判据里喂一条"从来不存在的路径"，确实被抓出（脚本 `release-verify.sh:553` 那步，路径字面量只存在于脚本里，文档侧不出现，否则自己把自己判红）。**闸的覆盖面仅限"路径存不存在"**：请求体字段名、鉴权头、响应形状它都不看——那些仍要靠人对着代码读
- [x] `docs/AUDIT-REPORT.md` 第 2 节"API 端点"重写：原来那份用 6 个抽查项写下"所有管理台端点完全一致"（实际 16 个），还把 `GET /api/v1/admin/messages` 归到了服务设备侧的 `MessageQueryController` 上，且引用的是文件行号——行号会腐烂且腐烂得没有声响。现在按"方法 + 完整路径"记账，交给上面那条闸跑
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

**最后更新**：2026-10-20（v1.1.0；本轮关掉的都是真跑过命令的，改写法的原本判据本身是错的或会腐烂的。剩下没勾的按顶部四类分组各有一个明确阻塞原因——**条数不在这里写死**，要几个数就现数：`grep -c '^- \[ \]' RELEASE-CHECKLIST.md`）  
**负责人**：开发团队轮值
