# PushBox 发布检查清单

本文档定义每次正式发布前必须完成的验证步骤。自动化能覆盖的部分写在 `scripts/release-verify.sh`（先跑它，再动手查剩下的）。

> 下面的勾选状态是 **v1.1.0 这一轮**（截至 2026-10-20）的实况：勾上的每一项都对应一条真跑过的命令，没勾的就是还没做或做不了。发下一版时先把复选框清干净。
>
> **未勾项分四类，阻塞原因不同，别混在一起当成"待办"**（数字是 `grep -c '^- \[ \]'` 现数出来的，会随版本变）：
> 1. **要远端动作才能判**（`main`、`v*` tag、CI 真跑、镜像标签、发布后监控）——本机一条都推不动，且第一次推 `main` 会连带 `docker-publish.yml` 真往 Docker Hub 推镜像，不可逆。想在没有 `main` 的情况下先验门禁：`ci.yml` 没有发布步，`build-apk.yml` / `build-msi.yml` 的 `gh release` 都带 `if: startsWith(github.ref,'refs/tags/v')`，所以**对 `publish` 分支手动 `workflow_dispatch` 这三个是零不可逆的**；`docker-publish.yml` 的 dispatch 现在**默认是 dry-run**（`dry_run` 输入框默认勾上 → 只构建并 load 进 runner，不推 Hub），点它可以在真发布前验出 `DOCKERHUB_USERNAME`/`DOCKERHUB_TOKEN` 能不能登录、metadata 算出的标签清单长什么样；把勾去掉才会真推。**这一条 2026-10-20 已经点过一次并全绿**，实测结论记在「Docker 镜像发布 → 标签管理」里。
> 2. **要真机 / 干净装机环境**（Android 兼容性 6 项、两端功能与性能各若干、Windows 系统兼容 4 项）——每条的判据已经写成"现读代码里的预期值 + 怎么演 + 失败长什么样"，见「Android 发布」与「Windows 发布」里各节的开头。**这里先纠正一句旧说法**：原来写的是"这台机器上既没有 Android 真机/模拟器，也没有可回滚的干净 Windows 测试环境"，2026-10-20 现查不成立——本机 `adb` 与 `emulator` 都在、已有 API 29/34 三个 AVD，宿主本身就是 Win11，逐用户装的东西也卸得掉。**真正缺的是另外三样**：API 26 那一档的镜像和建 AVD 的工具（`cmdline-tools` 没装）、第二台不同版本/不同缩放的 Windows、一台有指纹或面容硬件的手机。所以这一类的处置不再是"只能等人"，而是：能本机演的（Windows 装机类、API 34 那几项）随时可以排，剩下那三样该写进设备清单。
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

> **这一节连同下面的「功能验证」「性能检查」，以及 Windows 的「安装包验证」「系统兼容性」「功能验证」「日志检查」，共同点是：预期值现在就能从代码里读出来，结论只能从设备上拿。**（这里刻意不写"共多少条"——条数跟着代码和文档一起动，写死就变成下一条会腐烂的断言。）所以每条都写成三件事——怎么算过（现读现比的出处）、怎么演（哪台设备/哪条命令）、失败会长成什么样。勾它要的是设备上的证据，不是"读代码读懂了"，所以本轮一条都不勾。
>
> **发布前必做的是其中两条**，因为它们错了会真丢用户数据、而且丢了没法追回：本节的「老版本升级安装（不清数据）」和 Windows「功能验证」里的「拔网线判死 + `since_seq` 补拉」。其余按设备分组，可以发版后补。
>
> **本机能演到哪一步（2026-10-20 现查，别当成永远如此）**：Android 侧 `adb.exe`（`platform-tools/`，1.0.41）与 `emulator.exe` 都在，已装系统镜像只有 `android-29` 与 `android-34`（AVD：`Pixel_5` = 34），**没有 `cmdline-tools`，即 `sdkmanager`/`avdmanager` 都不存在**——所以 API 26 那一档在本机既造不出镜像也建不出 AVD，要么借一台 Android 8.0 实体机，要么先补 `cmdline-tools` 再下 `system-images;android-26;...`。Windows 侧工具链齐（`dotnet --version` = 8.0.426，`ISCC.exe` 就在脚本默认的那个路径），装机类验收在这台机器上就能办；但"在这台机器上装 `setup.exe`"会写 `HKCU` 的 `Run`/AUMID/`pushbox:` 协议键、把程序落到 `%LOCALAPPDATA%\Programs\PushBox`，属于改本机状态，动手前要先点头。

- [ ] 在 API 26（Android 8.0）真机/模拟器上安装成功。**下限的出处**：`android/app/build.gradle.kts:71` 的 `minSdk`（现读那个数，别抄文档——它从 24 改成 26 的理由就写在同文件 69-70 行的注释里：会话通知依赖 `NotificationChannel`）。**怎么演**：`adb install -r <apk>` 后 `adb shell am start -W -n cn.iamplus.pushbox/.MainActivity`。**怎么算过**：装上、冷启不落崩溃、能走完注册收到消息。**失败长什么样**：`INSTALL_FAILED_OLDER_SDK`（安装期就挡）或启动即崩（运行期没兜住 minSdk 上缺失的 API，看 `adb logcat -b crash`）。**这台机器演不了**（见上面那段），演过的人要写明用的是实体机还是补装的镜像
- [ ] 在 API 34（Android 14）真机/模拟器上运行正常。`targetSdk` 在 `build.gradle.kts:72` 现读。**怎么演**：本机 `Pixel_5` 那个 AVD 就是这一档，直接 `adb install -r` 走一遍「注册 → 发消息 → 杀掉再进来消息还在」。**这一条要盖住 34 特有的两道**：`POST_NOTIFICATIONS` 运行时权限（下一条）和前台服务的权限收紧——服务的 `foregroundServiceType` 在 `AndroidManifest.xml:43-45` 现读（现在是 `remoteMessaging`），Android 14 上类型选错或对应运行时权限没配就是 `startForeground` 抛异常、通知栏服务起不来。**还要顺便看**保活引导那节 UI（`MainActivity.kt:2574-2590`）里让用户去系统设置改的东西，在这台 34 设备上改完是否真的让服务活下来了——UI 存在不等于系统放行
- [ ] 通知权限请求正常弹出（首次启动）。请求点在 `MainActivity.kt:401-407`：只在 `SDK_INT >= TIRAMISU` 且未授予时发一次，**所以 API 26-32 根本没有这个弹窗，这条只能在 API 33+ 的设备上演**（别拿 Android 8 的机器说"没弹"当失败）。**怎么算过**：进主界面弹系统框；点拒绝后消息**照样入库、只是不出通知**——代码依据是 `notify/Notifier.kt:86-92` 把 `manager.notify` 包在 `catch (_: SecurityException)` 里。**"永久拒绝后应用内没有恢复入口"这个缺口已在 2026-10-21 补掉**，分三层：① 回调不再空着，用 `shouldShowRequestPermissionRationale` 分开"这次拒了"（系统下次还会问，不该拦人）与"勾了不再询问"（`MainActivity.kt:393-400`），后者才弹一次 `NotifyRecoveryDialog`（440-442、450-467），「去设置」走 `ACTION_APP_NOTIFICATION_SETTINGS`（470-482，ROM 没人实现这个 action 就退到应用详情页）；② "只说一次"落在 DataStore 布尔键 `notify_recovery_shown`（`IdentityStore.kt:164-166,210`，读写在 `App.kt:111-113,255`）——**有意不放进 `SECRET_KEYS`，它不是身份材料**；③ 长期入口是「设置 → 通知」里的 `NotifyPermissionRow`（496 起，挂在 2616），未授予时一直挂着，从设置页回来由 ON_RESUME 重读、开了即消失。**2026-10-21 在 `emulator-5554`（API 34）上按这个序列真演过，五件事都有证据**：`pm clear` 后首启弹系统框；第一次点"不允许"→ `dumpsys package` 里 `flags=[USER_SET]`、我们的框**不弹**；继续点到 `flags` 出现 `USER_FIXED` → `NotifyRecoveryDialog` 弹了（标题"通知权限已被永久拒绝"），点「去设置」→ `topResumedActivity=com.android.settings/.Settings$AppNotificationSettingsActivity`；在系统页打开总开关回到应用 → 那一行消失；再 `force-stop` 冷启、重新拒到 `USER_FIXED` → 对话框**不再弹**（说明布尔键真从盘上读回来了，不只是进程内的），而「设置 → 通知」那一行出现、同一枚「去设置」落到同一个系统页。**还没验的两半，别照着上面这段打勾**：拒绝状态下"消息照样入库、只是静默"要在同一台设备上真收一条消息、看通知栏为空而列表里有；国产 ROM 上 `ACTION_APP_NOTIFICATION_SETTINGS` 无人实现时那条退路只能等真机
- [ ] 生物识别解锁在支持的设备上工作；不支持的设备退回 PIN 且不给空白按钮。**"不给空白按钮"在代码里的真实形态要先对齐**：无可用硬件时，设置页那个开关是**禁用态**（`MainActivity.kt:2611-2613` 用 `BiometricManager.canAuthenticate(...) == BIOMETRIC_SUCCESS` 门住 `Switch(enabled = canBiometric)`）并配文案 `app_lock_biometric_unavailable` = "未检测到可用的指纹 / 面容，请先在系统设置中录入。"（`strings.xml:124`）——所以判据是"开关灰掉 + 有解释"，不是"按钮在但点了没反应"。**支持的设备**：弹窗负按钮字面是「改用 PIN」（`app_lock_use_pin`，`MainActivity.kt:371`），即 PIN 永远是 prompt 内的兜底；生物识别默认关、要用户手动开（`App.kt:135`）。**怎么算过**：录了指纹的设备上锁屏后唤起→指纹过→进主界面；模拟一次失败（连续错生物识别）不应把应用退出，只是回 PIN。**注意**：`MainActivity` 必须是 `FragmentActivity` 才起得了 `BiometricPrompt`（`build.gradle.kts:186-189` 记着这条约束），换壳重构后要重演
- [ ] 应用锁 PIN：连续错 4 次的退避是 5/15/30/60 秒，倒计时结束后能再试，锁定期输入框不给提交。**先对齐现状**：`App.kt:303` `BACKOFF_SEC = listOf(5, 15, 30, 60)`，第 4 次及以后封顶 60（`App.kt:179` 的 `minOf`）；等待用 `SystemClock.elapsedRealtime()`（`App.kt:180`），**改系统时间绕不过去**，验收时别去拨时钟。提交键 `enabled = pin.length >= 4 && !busy && retryIn == 0`（`MainActivity.kt:333`），倒计时逐秒刷新在 `MainActivity.kt:272-278`；等待文案 `app_lock_retry_in` = "PIN 不正确，请 %1$d 秒后再试"（`strings.xml:117`）。**怎么演**：连错 1/2/3/4 次，每次记下提示的秒数与 `BACKOFF_SEC` 逐字比；锁定期里输满 4 位，确认提交键仍是灰的；倒计时走完，确认同一 PIN 能进。**还有一条要演出来才敢写结论**：失败计数与 `pinLockUntil` 只在内存里（`App.kt:216,219`，落盘的只有开关状态和 PIN verifier，走 `IdentityStore.setAppLock`），**从最近任务划掉进程再进来 = 退避清零**（PIN 本身仍要猜对）。这不是崩溃，但"划掉进程能重置退避节奏"这句要在验收记录里写成人能读懂的限制，别让下一个人以为它是持久化的
- [ ] **（发布前必做）**老版本升级安装（不清数据）：身份私钥/token/恢复码从裸明文就地补封一次，"我的"页仍能显示恢复码。**迁移动作的出处**：`data/IdentityStore.kt:59-72` 的 `upgradeStorage()`，由 `load()` 在 `IdentityStore.kt:32` 调一次；它把 `SECRET_KEYS`（`IdentityStore.kt:213` = `ENC_PRIV`/`SIGN_PRIV`/`TOKEN`/`RECOVERY_CODE`/`APP_LOCK_PIN`）里**没带 `v1:` 前缀**的值用 Keystore 的 AES-GCM 密钥就地 `seal` 一次，稳态是只读不重写。**身份在 DataStore `pb_identity` 里、不在 Room 的 `pushbox.db`**（`IdentityStore.kt:217`），所以那句 `fallbackToDestructiveMigration()` 毁不掉身份——这条结论值得单独复验一次，因为它是"升级不清数据就不会丢号"的根。**怎么演**：装一个旧版本走通注册、发消息、记下"我的"页的恢复码；再用**同一把签名**（`android/release-cert.sha256`，见上面「签名验证」）`adb install -r` 新包；不卸载、不清数据。**怎么算过（四条都要同时成立）**：① 打开后**不落到注册页**（`live == null → EnrollScreen` 那个分支在 `MainActivity.kt:407-409`，进了 Enroll 就是身份没了）；② "我的"页恢复码仍显示得出来；③ 老消息列表还在；④ 还能收到新消息（token 补封成功）。**这一半验不了，别硬勾**：release 包不可调试（`build.gradle.kts:100-111` 那个 buildType 没设 `isDebuggable`，manifest 也没有 `<profileable>`），所以 `run-as cn.iamplus.pushbox` 读不到 `pb_identity`，"值真的变成 `v1:` 前缀了"这一半只能靠间接证据；要看这一半，用 `./gradlew :app:assembleDebug` 的包在同一台设备上把升级流程再演一遍（debug 包默认可调试，能 `run-as`），**但 debug 包与 release 签名不同，不能用它代替 ①②③④**。**失败长什么样**：升级后落注册页 = 身份丢；恢复码空但能收消息 = `RECOVERY_CODE` 补封失败；本地消息为空 = 撞上了 DB 重建而不是身份问题

### 功能验证
- [ ] 首次启动注册页的服务端地址是空输入框（不预填任何内网地址），填 `http://IP:端口` 能过校验、填垃圾给明确报错。**先加一条前置条件，否则测的是别的东西**：初值确实是空串（`MainActivity.kt:435`），但 `MainActivity.kt:453-457` 会在 `server_history` 非空时回填最近一个地址（键在 `IdentityStore.kt:112-121`），所以**必须用全新安装或先清数据的实例演**——在用过一次的设备上看到"有默认值"不是回归。**校验口径现读**：`MainActivity.kt:3529` 那条正则要求带 `http(s)://` 前缀、中间无空格、允许 `:端口` 与路径，末尾 `/` 会被削掉（`MainActivity.kt:506`）。**怎么算过**：填本机 WSL 测试栈的 `http://<局域网 IP>:<端口>`（端口现查 `docker compose ps`，别抄文档里的数）能连通并注册成功；填 `192.168.1.10:8080`（漏前缀）、`ftp://x`、`http://a b`（带空格）三种垃圾，逐个报错文案 `enroll_server_invalid` = "地址要带 http:// 或 https:// 前缀，中间不要有空格"（`strings.xml:41`），留空则是 `enroll_server_required` = "请先填写服务器地址"（`strings.xml:40`）。**注意区分两类报错**：格式错是前端正则当场拦（不发请求），地址格式对但连不上/证书不受信是请求失败。填 `http://` 明文时会不会被应用自己拦掉，看 `AndroidManifest.xml:26` 引的那份 `network_security_config`（现读它放行什么），别照着"Android 默认禁明文"下结论；被拦下来的失败要报成人看得懂的话，不能只留一个 `Exception`
- [ ] 创建频道后能收到测试消息。**怎么演**：管理台建频道 → 客户端订阅（或填订阅码）→ 管理台发一条测试消息 → 客户端**在前台、退到后台、锁屏三种状态下各发一条**。**怎么算过**：三种都到，且只到一次（重复入库就是补拉与实时下发撞了去重，Android 侧对应 Windows 的 `ON CONFLICT(msg_id)` 那套）。**后台/锁屏这两档才是这条的真正内容**，前台到了不代表保活没问题；锁屏那一档要顺便看通知渠道设置页里的东西和 `MainActivity.kt:2574-2590` 的保活引导讲的是不是同一件事
- [ ] 长按会话行弹出菜单（置顶/免打扰/清空等）。**项的清单以代码为准**：入口 `MainActivity.kt:3340` 的 `combinedClickable(onLong = ...)`，菜单体在 `MainActivity.kt:817-839`，项 = 置顶/取消置顶、免打扰/取消、锁屏隐藏内容/取消、重命名、设头像、清空聊天记录（`MenuOption` `MainActivity.kt:842-849`，文案 `strings.xml:218-234`）。**怎么算过**：长按后菜单里这些项逐字对得上，逐个操作后**杀掉重进状态还在**（置顶/免打扰是持久化的，不是当次内存态）。**别把两套菜单混了**：消息行的长按菜单是另一套（复制文本/保存图片/查看原始 JSON/删除，`strings.xml:236-245`），用消息菜单演过不能勾这条。**清空有二次确认**（`MainActivity.kt:800-815`，`clear_dialog_body` 明说"服务端历史仍保留、只删本地"）——确认文案与真实行为要一致，这条演的时候要顺手核对清空后重新拉历史会不会又回来；如果回来了，那是文档描述与实现不符，不是操作失败
- [ ] 图片消息能正确加载和保存。**加载路径**：`ui/MediaImages.kt` 两级缓存（内存 `LruCache` 取可用内存的 1/8，`cacheDir/media-img` 磁盘上限 128MB），取图走签名 GET（`PushBoxApi.fetchMedia`）。**保存路径**：长按消息 → 保存图片（仅在该消息带媒体时出现，`MainActivity.kt:1155-1163`）→ 写 `DIRECTORY_DOWNLOADS/pushbox_<msgId>_<idx>.jpg` 并触发媒体扫描（`MainActivity.kt:1197-1206`）。**怎么算过**：单图、九宫格（超过 9 张按 `MediaGrid` 的截断规则）、缩略图点开原图、断网后已看过的图仍能从缓存显示（这条是缓存真起作用的判据）、保存后相册/文件管理器能看到那个文件名。**失败长什么样**：一直停在占位态且点重试不回来（`MediaImage` 的 `evict` + `attempt++` 没生效）、保存成功但相册找不到（文件名/扫描问题）、翻历史时同一张图反复重新下载（缓存被 trim 掉了）
- [ ] 深色模式切换正常。**现状**：默认跟随系统、另有显式覆盖（`MainActivity.kt:208-227` 的三档 `when`），入口在"我的 → 设置 → 外观"（跟随系统/浅色/深色，`MainActivity.kt:2633-2639`，持久化键 `theme_mode`）。**怎么算过**：三档各截一张主界面 + 一张设置页；"跟随系统"档要真的随系统翻转（翻系统深色再回来看，不用重启）；重启后仍在选中的档（证落盘）。**边界要说清**：配色是硬编码的浅/深两套（`ui/Theme.kt:54-78`，注释写明不走动态取色），所以这条验的是"两套都齐、没有漏色"，不是"跟着壁纸走色"——漏色最常见的地方是图片消息背景、气泡描边、禁用态按钮，逐个看过去
- [x] 「检查更新」只列 `pushbox-release-*` 资产的**代码层已核对**：`android/app/src/main/java/cn/iamplus/pushbox/net/UpdateApi.kt:70` 用 `a.path("name").asText("").startsWith("pushbox-release-")` 过滤，且不回落到 debug 包（同文件 63 行注释记着这条决定）。**没有验的是运行时**：GitHub Releases 的 `total_count` 实测为 0，`releases/latest` 现在根本没有可返回的东西，所以"只有 debug 包时提示『暂无可安装的正式版本』"这句在发布前无法验——它要等第一个打了 `v*` 的 Release

### 性能检查

> 三条都有不依赖 root、在 **release 包**上就能跑的命令行路，所以"演不了"不能当理由；但也**没有任何一条有代码层的对照物**——应用里不埋点（`app/src/main/java` 全仓没有一处 `Log.*`，没有 `reportFullyDrawn()`、没有帧率/内存上报），也没有自写的 baseline profile，所以数字全靠设备上的 `adb` 现采，阈值只是文档目标。**先说清一个绕不开的口子**：release 包不可调试（`build.gradle.kts:100-111` 那个 buildType 没设 `isDebuggable`，manifest 无 `<profileable>`），所以 Android Studio 的 CPU/内存剖析器和 `run-as` 都接不上它——要拿火焰图就得换 debug 包，但 debug 包没开 R8（`isMinifyEnabled=true` 只在 release），启动与内存都会偏乐观，采出来的数不能填进这三条。

- [ ] 冷启动时间 < 3 秒（中端设备）。**怎么采**：`adb shell am force-stop cn.iamplus.pushbox` 再 `adb shell am start -W -n cn.iamplus.pushbox/.MainActivity`，读 `TotalTime`（`ThisTime` 只算本次 activity 拉起，别拿它当冷启动）。启动 activity 与 `launchMode` 在 `AndroidManifest.xml:31-40` 现读。**口径**：取 5 次的中位数而不是最好的一次；"中端设备"要写明是哪台（芯片 + RAM + Android 版本），**模拟器/带 `google_apis` 的 AVD 采的数不能用来勾这条**。失败长什么样：`TotalTime` 超阈值，或 `am start -W` 迟迟不返回（首屏被 `load()` 里的 DataStore/Keystore 初始化卡住，那是注册页前的必经路径）
- [ ] 消息列表滚动流畅（60 FPS）。**怎么采**：`adb shell dumpsys gfxinfo cn.iamplus.pushbox reset` 清计数 → 手动上下滚 10 屏 → `adb shell dumpsys gfxinfo cn.iamplus.pushbox` 读 `Janky frames%` 和 `Info: Slow UI thread / Slow bitmap uploads` 那几行。**判据换算**：屏幕 60Hz 时一帧预算 16.6ms，`Janky frames%` 是比"多少 FPS"更能说明问题的数——把目标写成阈值（例如 <5%）比写 60 FPS 更可验，因为 `dumpsys gfxinfo` 不直接吐 FPS。失败长什么样：Slow UI thread 占比高（Compose 重组/主线程 IO），或 Slow bitmap uploads（图片解码没离开主线程，看 `ui/MediaImages.kt` 的两级缓存有没有真生效）
- [ ] 内存占用 < 200MB（100 条消息场景）。**怎么采**：`adb shell dumpsys meminfo cn.iamplus.pushbox` 读 `TOTAL PSS`（这是系统账上的占用，比应用自己报的数可信），滚过 100 条消息 + 若干图片之后采，别在刚起来时采。**要一并记的两项**：`Graphics` 行（图片缓存吃的是这里，`ui/MediaImages.kt` 磁盘缓存上限 128MB、内存缓存取可用内存的 1/8，都是现读的数）和 `Native Heap` 的 `HeapSize` 有没有单调涨（只涨不落是泄漏，不是"占得多"）。失败长什么样：超阈值、或反复进出同一个带图会话后 PSS 阶梯式上涨不回落。**这条的阈值本身没有代码依据**（全仓没有内存守卫），所以超了就是真超了，别指望有个开关兜底

---

## Windows 发布

> **动手装机之前先认清两件事。**第一，xunit 那一层已经把"点了才发现"的一大类拦住了：`bash` 里 `dotnet test PushBox.Windows/PushBox.Crypto.Tests -c Release`（口径见「服务端发布」那节的测试计数），`MainWindowTest` 在 STA 线程上从 XAML 构造整窗、`FirstLaunchWindowTest` 同理管引导两屏、`SystemIntegrationTest` 跑的是**真命名管道**（单实例递送那一段）、`AppLogTest`/`LogWiringTest` 管脱敏与接线。**所以下面这些条的失败形态里，"XAML 没挂上/初始面板态不对/管道递不上"理论上当场就红，不必等人点**——本轮下面每条只写"设备上还要看的那半"。第二，有两台现成的体检仪专门管 xunit 够不着的那段：`dotnet run --project PushBox.Windows -- --notify-smoke <输出文件>`（不需要设备身份、不连服务端，逐项写进文本文件，量的是注册表真写进去、托盘图标真出来、toast 真弹出）与 `dotnet run --project windows/PushBox.LiveCheck -- http://127.0.0.1:<端口>`（打真服务端的签名与补拉判据，条数以脚本末行为准）。**两者都不碰真拔线、真 DPI、真卸载**，那三段只能上手。
>
> 工具链现状（2026-10-20 现查）：`dotnet --version` = 8.0.426，Inno Setup 的 `ISCC.exe` 就在 `windows/installer/build-installer.sh` 默认写的那个路径上，所以**"构建安装包 + 本机逐用户装一次"在这台机器上是可办的**（`bash windows/installer/build-installer.sh` → `windows/installer/Output/`，脚本第 75 行会打出静默安装那条命令）。代价要说清：装的是**未签名**包，会弹 SmartScreen；写的是 `HKCU` 那几个键、落在 `%LOCALAPPDATA%\Programs\PushBox`；逐用户装、不要 UAC，卸载也清得干净，但**动之前要点头**——这台是开发机，不是可回滚的干净测试机。

### 安装包验证
- [ ] `setup.exe` 能通过 Windows SmartScreen（需代码签名证书；未签名时 CI 只告警不失败，发布说明里要写清）。2026-10-20 现状：`CODESIGN_CERT_BASE64` / `CODESIGN_PASSWORD` **两个都没配**（GitHub Secrets 里只有 4 个 `ANDROID_*` + 2 个 `DOCKERHUB_*`），所以 v1.1.0 的 MSI **必然未签名**——那就必须把"会弹 SmartScreen、点『更多信息→仍要运行』"写进官网安装页与 Release 说明。**这一句两处都补上了**：官网在 `site/install.html`「安装包」小节加了 `callout warn`，Release 说明在 `build-apk.yml` 与 `build-msi.yml` 的发布步骤里按 `${{ secrets.CODESIGN_CERT_BASE64 }}` 是否为空**条件写入**（配了证书这一段自动消失；两个 workflow 都带上这句，是因为它们在同一 tag 上抢着 `gh release create`，谁先 create 谁定正文，只写一处就会随机丢）。**这两处 CI 改动只在本地验过**：`bash -n` 过，再把 YAML 里那段 run 抽出来用一个假 `gh` 跑过两种状态，确认未签名时正文多出那一段、签了名时一字不差地回到原样——没打过 `v*` 标签，所以 runner 上没跑过。**这条仍然不能勾**：勾它要的是证书本身，不是文案
- [ ] 卸载只清"指向本程序目录"的注册表键与开机自启项；**用户数据（`%LOCALAPPDATA%\PushBox\`）默认保留**，重装即恢复——这是设计口径，不是漏清。**先对齐现状再演**：`windows/installer/PushBox.iss` **没有 `[Registry]`、没有 `[Dirs]`、没有 `[InstallDelete]`**（现查 `grep -n '^\[' windows/installer/PushBox.iss` 数一下段名就看到了），`[UninstallDelete]`（111-112 行）是空的，注释写明"运行期不往 `{app}` 写任何东西"——库、日志、缓存、设置全在 `%LOCALAPPDATA%\PushBox`。三个运行期注册表键都是**应用启动时自己写的**，不是安装器写的：`Run` 值名 `PushBox`（`Integration\AutoStart.cs:23,26,34`）、AUMID `HKCU\Software\Classes\AppUserModelId\PushBox.Windows`（`Integration\ToastRegistration.cs:30`）、`pushbox:` 协议（同文件 78-88 行）。清理在 `[Code] CurUninstallStepChanged` 的 `usPostUninstall`（`PushBox.iss:132-158`），守卫函数 `PointsIntoApp`（124-130 行，对 `{app}` 做大小写不敏感子串比）——**"只清指向本程序目录的键"这句话的机器实现就是它**；AUMID 那条是无条件删但下次启动会重写，所以不算残留。**怎么演**：静默装（`PushBox-Setup-*-win-x64.exe /VERYSILENT /SUPPRESSMSGBOXES /NORESTART`，这行 `build-installer.sh:75` 会打出来）→ **启一次让三个键真的写出来**（不启动就卸载等于什么都没测）→ 控制面板/`unins000.exe /VERYSILENT` 卸掉 → 三条判据：`reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v PushBox` 报"找不到"；`reg query "HKCU\Software\Classes\pushbox" /s` 同样找不到；`if exist "%LOCALAPPDATA%\PushBox\identity.json" echo 数据还在` 要打出那行。**失败长什么样（两种，严重性不同）**：`Run` 里留着指向已删目录的键 = 开机报错、残留；`%LOCALAPPDATA%\PushBox` 被删 = 用户身份和消息库没了，这条比残留严重，一旦演出来先别往下走
- [ ] 单实例机制正常。**这句原来的写法要跟代码对齐**：它写的是"第二次启动提示已运行"，但代码里**没有任何提示**——`App.xaml.cs:76-87` 拿到 `TryBecomePrimary()` 为 false 时，把 `pushbox:` URI 经命名管道递给正在跑的那个实例，然后 `Shutdown()` 静默退出（`InstanceHandoff.Send` 递不上也只是记一行日志，注释写明"对方可能正在收工"），唯一痕迹是日志里的"第二个实例退出 递送=…"（`AppLog.cs:131-132`）。所以判据不是"看到提示"，而是：**同一个 exe 连点两次，不出现第二个托盘图标、第二条长连接、第二个 SQLite 写者**，且从通知点进来的第二次启动把通知递给了老窗口而不是新起一扇。**还有一条容易演错的**：互斥体名不是固定串，是 exe 全路径小写、反斜杠换正斜杠后 SHA-256 的前 12 位（`InstanceHandoff.cs:147-152`），所以 `bin\Debug` 里那份和装机那份**天生不同名、各起一个实例是设计如此**——拿开发目录那份去测装机那份，看到两个窗口会误判成失败
- [x] 版本号三处同源。**判据里不写具体数字**——写死就会腐烂，这一条之前挂着的是 `1.0.0`，而 `scripts/set-version.sh` 根本不碰它。现在的口径：`windows/PushBox.Windows/PushBox.Windows.csproj` 的 `<Version>` 是**唯一来源**，`windows/installer/PushBox.iss` 用 `#ifndef MyAppVersion` 兜底、真值由 `build-installer.sh` 读 csproj 后 `/DMyAppVersion` 传入，`AppVersion={#MyAppVersion}` 再进注册表；`scripts/release-verify.sh` 已经是"去 csproj 现读一个值再比"，不是比字符串常量
- [ ] 装机后「关于」页显示的字符串与上面同源（这一半要真跑一次 `setup.exe` 再打开关于页，本轮没装过；上面那条勾只证配置层同源）。**代码侧链路先记着，省得演的时候找错地方**：`Views\SettingsWindow.xaml.cs:62` 把 `AboutVersion.Text` 设成 `"版本 " + UpdateClient.CurrentVersion()`，后者读 `AssemblyInformationalVersionAttribute` 并削掉 `+sha` 尾巴（`Update\UpdateClient.cs:67-79`），而这个 attribute 由 csproj 的 `<Version>` 盖章。**要真去比的是"削 `+sha`"这一步**：SourceLink/可重复构建打开时 informational version 常带 `+<commit>`，关于页上那串如果出现了 `+`，那不是同源破了，是削得不对——照"逐字等于 csproj 里现读的那个值"判，别自己放宽

### 系统兼容性
- [ ] Windows 10 (19041+) 安装成功。**两道门槛不是一个数，验收时别混**：程序集引用的是 `net8.0-windows10.0.19041.0`（`windows/PushBox.Windows/PushBox.Windows.csproj:5` 现读），而安装器的 `MinVersion` 只写到 `10.0`（`windows/installer/PushBox.iss:49`，另两条：`PrivilegesRequired=lowest` 第 53 行 = 逐用户装、不要 UAC；`ArchitecturesAllowed=x64compatible` 第 46 行 = 只有 x64）——**也就是说安装器不会替 19041 把关**，装到更老的 Win10 上是"装得上、跑起来才知道"。所以这条要在真 19041 或更近的一台上演，判据是冷启不崩 + 通知能出：`ToastRegistration` 走的是 WinRT 通知，拿不到时退回托盘气泡（`Integration\TrayIcon.cs:100-107`，日志会写"通知走退路 <异常类型>"，`AppLog.cs:178-179`），**看到退路不算崩、但也不等于过**，得记进验收结论。**另外**：self-contained 发布是"干净机器能跑"的前提（`build-installer.sh:44` 那条 `-r win-x64 --self-contained true`），装的那台不该被要求先装 .NET
- [ ] Windows 11 运行正常。本机就是 Win11（`10.0.28000`），**这条在本机就能演**，只是它盖不住上一那条——一台系统版本证不了 19041 那一档。判据按下面「功能验证」整节走一遍，重点是 Win11 上的通知中心与「设置 → 通知」里 PushBox 的开关状态能被 `settings.json` 里那四个勾选项对上
- [ ] 高 DPI 屏幕下 UI 不模糊。**这一条 2026-10-21 起仓库自己声明了分感知**：`windows/PushBox.Windows/app.manifest` 写了 `dpiAware=true/pm` 与 `dpiAwareness=PerMonitorV2, PerMonitor`，由 `PushBox.Windows.csproj` 的 `<ApplicationManifest>` 挂进来（旧说法"全仓没有 app.manifest、分感知全靠 WPF 运行时默认"已作废）。**两条坑给下一个改这里的人**：这份清单是**整份替换** apphost 内嵌的那一份、不是叠加，`asInvoker` 抄丢了就变成每次启动要 UAC；`<assemblyIdentity>` 不能手写，WPF 项目里 SDK 自己生成，写了直接 `NETSDK1098` 构建失败。**产物级证据**：Release 构建出的 `PushBox.Windows.exe` 内嵌清单里能扫到 `PerMonitorV2`，改动前的旧产物同一处 0 命中（新旧互为对照），`asInvoker` 仍在原位；`dotnet test PushBox.Crypto.Tests -c Release` 556/556、跳过 0。**"清单被 Windows 采纳了吗"这条 2026-10-21 已经不是靠猜**：把同一份 `app.manifest` 塞进一个临时 console 探针（只调 `GetThreadDpiAwarenessContext` + `AreDpiAwarenessContextsEqual`，不写注册表不碰 `%LOCALAPPDATA%`），本机 125% 缩放下报 `PER_MONITOR_AWARE_V2`、`GetDpiForSystem=120`；换成只有 `asInvoker` 的旧式清单同一段代码报 `UNAWARE`、`GetDpiForSystem=96`（被系统位图虚拟化了）——同一份代码唯一变量是清单，所以采纳与否看得干干净净。**顺带记两条**：这份清单不需要 `<compatibility>`/`supportedOS` 那一段，也不需要 `<assemblyIdentity>`（SDK 自己管，手写撞 `NETSDK1098`），删掉它们 `dpiAwareness` 照样生效；全仓没有任何代码读 `Environment.OSVersion`（`grep OSVersion|RuntimeInformation|GetVersionEx` 在 `PushBox.Windows/`、`PushBox.Crypto/` 零命中），所以少那一段不改任何东西。**但"不模糊"这三个字仍然没被证明**：本机只有一块屏、一个缩放比，而 PerMonitorV2 的意义正是跨不同缩放显示器时不重新位图拉伸——那一段只能上手，而且探针是 console，WPF 真窗口的排版重排还得眼睛看。**判据**：125%/150% 缩放的屏上把窗口拖到缩放不同的第二台显示器上，文字与图标应保持锐利；出现"整体被拉虚的一层"（DWM 位图拉伸）就是失败——**声明已经写死并被系统采纳了，再失败不能再推给"WPF 默认就这样"**。**托盘图标另算**：它在高 DPI 下发虚的成因是 `.ico` 缺小尺寸图层（这条已经写在 `docs\Windows开发环境安装教程.md:288`），和主窗口的分感知是两件事，别互相顶罪
- [ ] 托盘图标右键菜单正常。**"正常"的清单现读 `Integration\TrayIcon.cs:29-41`**：「打开 PushBox」「设置…」/分隔/可勾选的「新消息通知」「静默时段」「开机自启」「关闭窗口时最小化到托盘」/分隔/「退出」；左键 = 打开主窗口（同文件 82 行）；tooltip 由 `TooltipFor`（90-95 行）**按未读数和状态拼三种形态**：有未读且有状态 = `<名> · n 条未读 · <状态>`，只有未读 = `<名> · n 条未读`，只有状态 = `<名> · <状态>`，两者都没 = 光一个应用名。**判据要把这四种都走一遍**（未归零时看到的字符串必须落在其中一种，出现"· · "这类空段就是拼接坏了）。**怎么算过（三项都要）**：四个勾选项的勾选态与 `%LOCALAPPDATA%\PushBox\settings.json` 里的值逐项一致（这是机器可对的一条，别只靠眼）；「关闭窗口时最小化到托盘」勾上时点关闭只隐藏不退出、取消时点关闭真退出（`App.xaml.cs:265-270,305-316`）；未读数和状态文案随收消息/断网变化。「退出」要演一次真退出——它是唯一一个会结束进程、也就顺带解开互斥体的项
- [x] 主窗口冷启不崩（`XamlParseException` 类问题由 `ThemeTest` 在 CI 拦，见 `dotnet test`）：2026-10-20 在 Windows 上实跑 `dotnet test PushBox.Crypto.Tests -c Release` → **556/556、退出码 0**，其中含 `windows/PushBox.Crypto.Tests/ThemeTest.cs`。它拦的是真出事的那一类——注释里记着实测两次：顶栏 ⚙ 拿了 `Text.H2`、返回 ← 拿了 `Text.H3`，同一种写法换 `StaticResource` 就当场抛 `XamlParseException`。**边界要说清**：这证的是"资源字典与视觉树的拼装不炸"，不证 Windows 10/11 各版本上的真实冷启，那半仍在上面四条里

### 功能验证
- [ ] 首次启动引导流程完整（服务器地址 → 设备名 → 恢复码）。实现看 `Views\FirstLaunchWindow.xaml(.cs)` 与 `Views\OnboardingModel.cs`。**判据按"退出再进来还是注册好的"这条主线演**：填服务器地址（`http://IP:端口`，本机用 WSL 测试栈的地址，端口现查 `docker compose ps`）→ 起设备名 → **拿到恢复码并要求确认已抄存**；完成后关掉重开应直接进主界面、不再走引导，且 `%LOCALAPPDATA%\PushBox\identity.json` 出现。**要专门演的三种失败**：地址填错（连不上/拼错）时报错文案是否指得出下一步、不是一串异常类型；引导中途关窗口（半途退出）下次能否接着走而不是卡死或写出半成品身份；恢复码那一屏有没有"复制/只显示一次"的口径说明——这一屏是用户唯一的备份机会，看不懂等于没备份。**顺带核对日志**：引导走完后 `logs\pushbox-<当天>.log` 里应该有"启动 … 身份=已注册"，且**不该**出现那串恢复码明文（下面「日志检查」第二条是同一件事的机器判据）
- [ ] 三栏布局响应式调整（窗口宽度变化时）。**断点现读**：`Views\MainModel.cs:145` 的 `NarrowPaneBelow`，判定在 224 行 `< 那个值`；应用点在 `MainWindow.xaml.cs:517` `ApplyPaneWidth()`，`SizeChanged` 挂在 303 行。**默认三栏**是 60（菜单栏）/260（列表）/`*`（对话），见 `MainWindow.xaml:127-135`。**怎么演**：从宽拖到断点以下再拖回来，各跨两次；**窄档里再演一个分支**——有选中会话时收起的是列表列、没选中时收起的是对话列（`MainWindow.xaml.cs:541-554`），这两种状态要分别看到；返回按钮只在窄档出现（523 行）。**怎么算过**：跨断点不闪、不出现空白列、选中会话时能靠返回按钮回到列表（否则窄档就是死路一条）。**顺带**气泡宽度上限在两档不同（×0.72 与 ×0.78、绝对封顶 900，`MainModel.cs:150-155,230-232`），拖一遍刚好能在两种缩放里都看到气泡不顶边
- [ ] 消息气泡头像显示正确。头像不是绑定出来的，是 `Controls\AvatarView.cs:23` 那个 `AvatarBox` 自己异步盖上（`Views\Converters.cs:46-49` 记着这段历史）。**首屏/取不到图时的占位 = 显示名首字母 + 由同一张表定的底色**（`AvatarView.cs:41,91`，`Converters.cs:82`），设置头像落盘在 `%LOCALAPPDATA%\PushBox\avatars`（`MainModel.cs:348-367`）。**怎么算过**：自己发的与对方发的位置/配色可区分、同一发送者在列表行与气泡里是同一个头像、改完头像重启后不是首字占位（证真读到了本地文件）、断网重启后仍显示（缓存命中）。**这条最重要的失败形态有代码背书**：`AvatarView.cs:113` 明确写了"取不到就留着首字占位，头像不该让会话行塌一格"——所以看到某条消息带头像时整行高度错位/格子塌陷，就是这段兜底没兜住，属于失败而不是"图没了而已"
- [ ] 设置页危险操作按钮为红色（清空/归档/吊销）。三个都吃同一个红色 token（`BorderBrush`/`Foreground` = `Level.Error`）：清空 `Views\SettingsWindow.xaml:287-289`、归档 295-297、吊销这台 374-376；另两个也同色（取消接入 291-294、退出空间并清空数据 396-398），逐个点前先确认有没有二次确认（`SettingsWindow.xaml.cs:324-325,337,451-452,626` 都带"此操作不可撤销"）。**这条的判据要按现状写清，别写成"所有危险操作都是红的"**：主窗口会话右键里的「清空聊天记录」是普通 `MenuItem`、**没有红色样式**（`MainWindow.xaml:67`）——要么先补样式再按补完的口径验，要么验收记录里就写"设置页危险操作为红色，主窗口菜单项未着色"。**吊销那一台要顺便验服务端**：吊销后这台设备在服务端列表里状态可见地变了、且客户端不再收到该频道的新消息——只红不生效比不红更糟
- [ ] 自动更新检查在启动时静默执行。**代码现状**：只在"已注册"时触发（`App.xaml.cs:113-115`），受 `settings.json` 的 `auto_check_update` 管（`Settings\ClientSettings.cs:46,52`，默认开），一个进程只查一次（119 行）；请求 `https://api.github.com/repos/iamplusdev/push-box/releases/latest`（`Update\UpdateClient.cs:39-41`），挑资产的规则 = 文件名前缀 `PushBox-Setup-` + 含 `win-x64` + 以 `.exe` 结尾（47-48,152-155 行）。**"静默"是真的静默**：无新版、网络失败、GitHub 那边还没有 Release，这三种情况启动时**什么都不弹**，连日志都不落（失败只记 `Debug` 级，而 logger 的最低级别是 `Information`，`AppLog.cs:107,201-202`）。**所以这条在发布前只能验一半**：能验的是"什么都不弹、主界面照常起来"；"发现新版本"那个弹窗（`Views\UpdatePromptWindow.xaml.cs:31`）要等第一个打了 `v*` 的 Release 才有素材。**手动入口现在就能验**：设置 → 关于与更新 → 检查更新，在无 Release 时应显示"更新检查失败：还没有发布过任何版本"（`UpdateClient.cs:109-112`），这句是该场景唯一的用户可见反馈。**发版后要补的第三半**：确认 Release 资产名真的能被那三条匹配规则挑中——CI 产物的命名与匹配串同源，改了 `OutputBaseFilename` 忘了这里，更新检查会永久静默
- [ ] **（发布前必做）**拔网线 ≥ heartbeat+15s 后判死并带 `since_seq` 补拉，重连后消息按 seq 顺序显示。**先按代码把三个数对齐，别按文档里的印象掐时间**：心跳间隔以**服务端**为准——`server/src/main/resources/application.yml` 的 `ws.heartbeat-sec`（现读；服务端默认值在 `config/PbProperties.java:70`），它由 `hello` 帧下发、客户端在 `Sync\PushConnection.cs:230` 采纳，客户端自己那份只是缺省（同文件 46 行）；判死公式 = **心跳 + 15 秒**，两端同式（客户端 `PushConnection.cs:50` `DeadAfterFor`、看门狗 314-336 行按 1 秒粒度轮询、服务端 `PushboxWsHandler.java:213` 用同一个式子并以 `heartbeat_timeout` 关会话）。**所以拔线时长要明显大于"心跳+15"这个和**，拔 20 秒测不出任何东西；服务端那侧的判定还带一道扫描间隔（`heartbeat()` 上是 `@Scheduled(fixedDelay = 10_000)`，同文件 210 行），所以"判死"不是到点即断，留 10 秒余量再下结论。**`since_seq` 的口径**：连上时 URL 带 `?since_seq=`（`PushConnection.cs:89-97`），值 = `max(LastAppliedSeq, 本地群游标)`（`Sync\SyncEngine.cs:258-261,275`），且**每次重连前重新从盘上读一次**（203-204 行注释写明"否则断线期间的消息会被漏拉"）、断开时落盘（215 行）——这条就是"拔线期间别人发的消息一条不少"的实现依据。**怎么演**：起一个第二设备/管理台做发送方 → 拔网线（或禁用适配器）到超过阈值 → 记状态行 → 保持拔着让发送方发 5 条（含一条带图的）→ 恢复网络 → 等重连。**判据里"不发生"的部分比"发生"的部分更重要**：① 断线期间状态行变成"离线，正在重连（heartbeat_timeout）"（`Views\MainModel.cs:522-530` 拼文案，`MainWindow.xaml.cs:457-471` 刷新）；② 重连后 5 条**一条不少、按 seq 顺序插在正确位置**；③ **不弹一串通知**——补拉与重放走的是 `allowNotify:false`（`SyncEngine.cs:447-482,475`，注释直接写着"一次休眠回来弹 500 条 Toast 是灾难"）；④ **不出现重复入库**——去重是 `ON CONFLICT(msg_id) DO NOTHING`（`Data\DeviceStore.cs:160,365,762`）；⑤ 落后超过 2000 条时服务端改发 `resume_gap{missing_from}`，客户端转 REST 分页 `GET /api/v1/messages?after_seq=&limit=200` 补（`SyncEngine.cs:338-339,447-482`，翻页在 `has_more=false` 或游标不前进时停）——这一支平时那 5 条测不到，要单独造一次。**造的方法有个坑要先知道**：判定用的是 `PushboxWsHandler.java:40` 那个**硬编码** `GAP_THRESHOLD`（164 行 `latest - sinceSeq > GAP_THRESHOLD`），它紧邻的注释写着"联调用 `--pb.ws.gap-threshold=3` 调小"，但**全仓没有任何地方绑过这个键**（现查 `grep -rn "gap-threshold" server/src core/src` 只命中那句注释本身）——也就是说那条捷径现在是假的，只能真灌 2000+ 条，或者先把这个常量改成从 `PbProperties` 读（那是一处独立的整改，不该混在验收里顺手做）。**顺带记着这两处的历史坑**（162 与 169-170 行的注释）：`resume_gap` 与 `resume_ok` 的判定改过两轮，验收这一支时要同时确认"补拉完之后顶栏回到在线、并且列表里没有缺口"，只看到"补了几页"不算过。**失败长什么样**：拔线超过阈值但状态行还是"在线" = 看门狗没生效，比丢消息更危险（用户以为一切正常）；重连后消息缺号或顺序错 = 游标没重新读盘；弹了 Toast 或同一条出现两次 = 去重/抑制被改坏了。**这条一旦回归就是静默丢消息，用户不会知道自己少了什么**，所以它和 Android 那条升级不丢身份并列成发版前必做

### 日志检查

> 这一节三条都能在**本机装一次机**就验完，不需要第二台设备，是 26 条里性价比最高的一节。日志由 Serilog 写、路径在代码里定（没有配置文件开关），脱敏是分层的、不是"记得别打日志"。

- [ ] 日志文件落在 `%LOCALAPPDATA%\PushBox\logs\`。**代码依据**：目录 = `Data\IdentityStore.cs:55-56` 那个 `LocalApplicationData\PushBox` 再拼 `\logs`（拼点在 `Logging\AppLog.cs:92-93`）；文件形态 = `AppLog.cs:40` 的 pattern `"pushbox-.log"` 由 Serilog 的按天滚动把日期插在扩展名**前面**，落成 `pushbox-YYYYMMDD.log`（36-39 行的注释记着早先误写成 `pushbox-{Date}.log` 的那个坑）；保留 7 份、单份 4MB、超限再滚（`AppLog.cs:100-121`）。**怎么算过**：`dir "%LOCALAPPDATA%\PushBox\logs"` 里名字对得上今天、首行是"启动 <版本> 身份=…"；`AppLog.Start()` 在 `App.xaml.cs:63`，**在它之前发生的任何事都不会落盘**——所以"崩在启动极早期却一行日志没有"是设计如此，遇到时要按这条判，不要当成写盘坏了。**顺带**：日志创建失败不会带死应用（`AppLog.cs:100-121` 出错返回 null logger），想验这条可以把 logs 目录设成只读
- [ ] 日志中无明文 recovery code 或 secret key（入口只收类型化字段、落盘前五层网）。**为什么"五层"不是话术，逐层有出处**：① 入口层——`AppLog` 的公开方法只收类型化字段（code/count/cursor/异常类型名），**根本没有一个收 body 的重载**（`AppLog.cs:10-18,123-202`），想泄露得先改签名；② 键名黑名单——含 `recovery_code`/`secret`/`token`/`session_key`/`private_key`/`seed`/`salt` 等（`Logging\PrivacyGate.cs:49-59`），命中替换成 `<已脱敏>`；③ 凭据形状——连续 ≥40 个 base64 字符或 ≥32 个十六进制字符一律 `<已脱敏:凭据形状>`（`PrivacyGate.cs:31,34,68-72`），这层管的是**没有键名的裸串**（恢复码正是这种）；④ 超长值 >120 字符 → `<已脱敏:超长值>`（28,82-87）；⑤ 属性级与文本级各还一道（`PrivacyEnricher.cs:24,61`、`PrivacyFormatter.cs:26,42,84-86`）。**怎么算过（命令化的判据，别只翻一眼）**：先把日志自己灌满一遍——走完注册、收发消息、改头像、吊销一次、手动检查更新、再故意制造一次异常；然后 `findstr /i "recovery secret token private_key" "%LOCALAPPDATA%\PushBox\logs\pushbox-<当天>.log"` 只看命中行，要求**每一处命中都在 `<已脱敏` 标记里**；再拿注册时抄下的那串恢复码做逐字搜索，要求搜不到。**失败长什么样**：搜到恢复码明文，或命中行是 `"token":"eyJ…"` 这种原样 JSON——那说明有代码绕过了 `AppLog` 直接写了字符串（②③两层是兜住这种情况的最后一道，它漏了意味着超长或形状不匹配，要顺着那行的 logger 调用点找）
- [ ] 异常堆栈包含足够调试信息。**当前口径是有取舍的，验收前先接受这个取舍**：`PrivacyGate.cs:106-124` 走 `InnerException` 链，写**异常类型全名 + 每一帧栈**，但**故意不写 `Message`**（`Message` 里常带 URL、token 片段或用户内容）；崩溃钩子三处齐全（dispatcher / AppDomain / TaskScheduler，`App.xaml.cs:178-184`），入口 `AppLog.Unhandled`（`AppLog.cs:208-209`）；行号能不能落下来取决于安装包里带没带 `.pdb`（`installer\PushBox.iss:95-96` 带）。**怎么算过**：制造一次可复现的异常（比如把 `%LOCALAPPDATA%\PushBox\pushbox.db` 改成只读再启动、或让服务端返回一个畸形响应），日志里要有异常类型全名 + 至少一帧 `PushBox.Windows.*` 的栈帧 + 可定位的方法名，同时**不应**有异常 Message 原文；拿那帧去源码里能对上位置，才算"够调试"。**要说清的边界**：本仓没有崩溃上报（不上传任何遥测，`AppLog.cs:26-28` 写着这个决定），所以"用户崩了我们知不知道"的答案是**不知道，除非他自己把 logs 目录发过来**——发版说明里要写明日志目录，否则第一版的真实故障率永远是黑的

---

## Docker 镜像发布

### 镜像质量

- [x] 以非 root 用户运行（`USER pushbox`）：Dockerfile 第 141 行有 `USER pushbox`，且在真跑起来的容器上复核过进程身份——`docker exec <容器> id` → `uid=10001(pushbox) gid=999(pushbox)`，宿主侧 `docker top <容器> -o user` 同样显示 `10001`（只看 Dockerfile 不算：真起过的进程才是证据）
- [x] 健康检查配置正确（TCP 探活，非 curl——JRE 镜像里没有 curl，且 `/healthz` 会真连库）
- [x] 入口脚本不打印任何密钥片段（只报键名）

### 标签管理

这四条原本都是"凭印象打勾"的形状，现在写成一条命令就能自证。前三条的根据是 2026-10-20 读过 `.github/workflows/docker-publish.yml` 的 `docker/metadata-action` 配置：`type=ref,event=branch` + `type=sha,prefix=sha-,format=short` + `type=semver,pattern={{version}}` + `type=semver,pattern={{major}}.{{minor}}` + `latest` 只在**正式（不含 `-`）标签**时生效。也就是打 `v1.1.0` 应得到 `1.1.0`、`1.1`、`latest`，push main 应得到 `main`、`sha-<7位>`。**这三条要等 `v1.1.0` 真的存在才勾得上**：tag 还没打，打 tag 与推 tag 都算不可逆的远端动作、要单独确认。仓库本身**已经存在了**（2026-10-20 那次 `publish`→`main` 的推送里 `docker-publish.yml` 真推上去的，`.../push-box/tags` 实测 `count=2`：`main` 与 `sha-f6b9a53`，`full_size` 223450397 字节 ≈213 MB 压缩后）——这一句把本节原来"仓库仍是 404 = 从未创建"的说法作废，那是**更早一次**实测，被同一天的推送覆盖了，见第四条。所以现在缺的只有版本号那一份标签，不是整个仓库。

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
- [x] `deploy/docker-compose.yml` 的 `image:` 与本次 tag 一致：**这条 2026-10-20 起有机器兜底了**——上面那道闸门在 tag 事件上现读 pom 与 compose 比对，不一致直接红，不再靠人记得去 `grep`。本机核对仍是 `grep -n 'image: docker.io' deploy/docker-compose.yml` → `iamplusdev/push-box:1.1.0`，等于 `pom.xml` 的 `<version>1.1.0</version>`。**但注意这条勾只证"写对了"，不证"拉得到"**：2026-10-20 实测该 Hub 仓库 `.../v2/repositories/iamplusdev/push-box/tags` 返回 **404（对照 `library/mysql` 返 200，所以不是网络假象）= 从未创建**。回落已经实测过：`docker compose -f deploy/docker-compose.yml --dry-run up -d` 的计划是 `Pulling → manifest unknown → Building → naming to docker.io/iamplusdev/push-box:1.1.0`，compose 会自动改成本地构建，新用户不加 `--build` 也起得来（代价是首次要现场编、且用不上 CI 的 `latest/sha-*` 标签）。**这句里的 404 已经过期，别照着它下结论**：同一天把 `publish` 推成 `main` 之后，`docker-publish.yml` 真推了一次，仓库随推送被创建，`.../push-box/tags` 复测 `count=2`（`main`、`sha-f6b9a53`）。**2026-10-20 当晚想再复验一次没复验成**：`hub.docker.com` 从本机 curl、WSL curl 与浏览器三条路都是连接超时（**是连不通，不是 404**，代理客户端当时没在跑），所以"仓库存在、两个标签"记的是推送当天那一次实测。

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

**它第一次能跑绿长什么样**：`main` 现在已经存在，从下一次"改动含 `README.md` 或这个文件本身"的 **`main` 常规推送**起，这条会被自然触发并顺带登记下来（此后才谈得上手动 dispatch）。它依赖的 Hub 仓库**也已经存在了**（同一天那次 `publish`→`main` 推送里由 `docker-publish.yml` 真推创建，见上面「标签管理」），所以触发时不会再撞上"仓库不存在"——`dockerhub-description` 这个 action 只 PUT 描述、不建仓库，这一点上现在没有阻塞。**结论：今天没有任何动作能把这条关掉**——手动跑没有入口（workflow 资源不存在），自动跑要等一次 `main` 推送，而 `push` 到 `main` 会连带 `docker-publish.yml` 真推镜像（不受 dry_run 影响，不可逆），不在"零不可逆"这一批里。

诚实边界：Hub 仓库存在、`main` 与 `sha-f6b9a53` 两个标签，是 2026-10-20 那次 main 推送**之后当场实测**的（`.../push-box/tags` `count=2`）；当晚想复核时 `hub.docker.com` 从本机 curl、WSL curl 与浏览器三条路全是连接超时（**连不通，不是 404**，Windows 侧代理客户端没在跑），所以这一条是"当天实测、当晚未复核"。要复核先把代理客户端起来，再跑 `curl -sS -o /dev/null -w '%{http_code}\n' https://hub.docker.com/v2/repositories/iamplusdev/push-box/`。

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
