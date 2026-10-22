# PushBox 更新日志

本文档记录每个版本的核心改动、新增功能与修复的缺陷。详细技术决策见 [软件设计方案](docs/软件设计方案.md)。

首个正式版本是 v1.0.0。发布前的 28 轮内部流水（原编号 v1.1–v1.28）已归档到
[开发轮次记录](docs/开发轮次记录.md)，那里按时间保留每一轮的缺陷、判据与验证结论。

---

## v1.1.0（2026-10-22）—— 本机起步那条路第一次真跑通（第二十八轮）

> 技术决策见 [软件设计方案](docs/软件设计方案.md) §11.1.2 / §11.2.1；这一轮的起点是"发布前还有哪些可优化项"那张清单里的第 1、2、3、5 项加计数闸。
>
> **v1.1.0 这一版装的是第二十六、二十七、二十八这三轮**（`v1.0.0` 之后没打过标签，所以这三轮一起进本次发布，标题里那三个日期各是那一轮收口的日子）。再往下那条 `## v1.1.0（2026-10-19）—— 管理台定时推送` **不是本版本**，它是 `v1.0.0` 之前 28 轮内部流水留下的旧编号（文件顶上那句"原编号 v1.1–v1.28 已归档"说的就是这套），两边撞上纯属同号——按标题里的日期认，别看编号。

### 修掉一条让"本机起步"整条路径跑不起来的缺陷

- **`PB_STORAGE_TYPE=local` 时进程根本起不来**，报 `NoSuchBeanDefinitionException: No qualifying bean of type 'S3Client'`（装配链 `adminCli → MediaPurgeService → MediaObjects`）。`S3Config` 原来在 local 下 `return null`，而 Spring 对 `@Bean` 返回 null 注册的是 `NullBean`——它对必须注入的构造参数等于"没有这个 bean"。**README、`部署快速参考`、compose、entrypoint 四条线都写着 local 是本机默认形态，而它只在单测里通过过**：那些用例全是 `new MediaObjects(null, props)` 手工构造，绕开了容器。
- **修法是让这个 bean 在 local 下不存在**，不是给参数加 `@Nullable` 把现场抹平：`S3Config#s3Client` 上 `@ConditionalOnProperty(prefix="pb.storage", name="type", havingValue="s3", matchIfMissing=true)`（默认值与 `PbProperties.Storage#type` 对齐），`MediaObjects` 的参数留 `@Nullable`、`storageType` 先 `trim`，并把"`s3 `"这类两边都不沾的取值）报成"只认 s3|local"而不是一个堆栈。
- **新增 `StorageWiringTest` 3 条**：跑真 `AnnotationConfigApplicationContext`（`register(S3Config.class, MediaObjects.class)`，`pb.storage.type` 同时塞进 `MapPropertySource` 与绑定后的 `PbProperties`——只给一边测的就不是生产那条路径）。local 那一支断言 `getBeanNamesForType(S3Client.class)` **为空**而不是"有个 bean 但值是 null"，`s3` 与 `S3` 各断建出一个。这类"只有容器才照得出来"的缺陷，从此有一条不用人肉起服务端的证明。

### 服务端：把"配了但读不到"也变成一件要出声的事

- **`DevCredentialsGuard` 加反向 WARN**（①）。必填项按存储类型分档之后，"忘了把 `PB_STORAGE_TYPE` 改成 `s3`"不再让启动失败，只会让整套部署静默落在本地目录上——所以 local 模式却配着 `PB_S3_ENDPOINT/ACCESS_KEY/SECRET_KEY` 时点名这几项"配了但一个字都不读"，并给出落盘目录与"要验桶就带 `PB_STORAGE_TYPE=s3` 重启"的下一步。**只报键名与目录，值不进日志**（同 §13 的脱敏口径）。`DevCredentialsGuardTest` 从 8 条扩到 10 条：⑨ 钉住这条 WARN（含"不许出现哨兵值本身"），⑩ 钉住真跑 s3 时这句不许响。
- 踩到的坑记一笔：`log.warn("…{}…{}", …)` 与 `String.formatted()` 是两套占位符，写成 `"""…{}…""".formatted(a, b)` 会把 `{}` 原样打出来——是 ⑨ 那条断言把它抓出来的。

### 部署：容器里的报错要说清"下一步敲什么"

- **entrypoint 的每一条失败都自带落点**（②）：缺 `PB_DB_URL`/`PB_DB_PASSWORD` 时点名写进哪个文件（`deploy/.env`）、JDBC 该长成什么样、拓扑 B 那台的服务名变体，以及指回 `deploy/部署快速参考.md` 的哪一节；S3 三项缺失时补一句"不想外接存储就设 `PB_STORAGE_TYPE=local`"。这些文案原先只说"未设置"，读者得自己去翻变量清单。
- **`PB_STORAGE_TYPE=local` 时启动阶段做一次写入探针**（③）：往 `${PB_STORAGE_LOCAL_PATH:-/app/data/media}` 写 `.entrypoint-write-probe` 再删掉，写不进就当场退出。判据是"目录存在但属 root"这一类**不会让 `/healthz` 报错**的配法——`HealthzController.storageConfigValid` 只校验配置项非空，靠它兜等于让每条带图消息静默停在 `failed`。探针那句 `if ! : > "$F" 2>/dev/null` 也修了：重定向按从左到右生效，得写成 `( : > "$F" ) 2>/dev/null` 才真把 `Permission denied` 咽下去。
- **local 模式下目录归镜像里预建并 chown**：`useradd` 那步从只建 `/app/logs` 改成 `mkdir -p /app/logs /app/data/media`，`PB_STORAGE_LOCAL_PATH` 的默认值也从 `./data/media` 纠正为容器里真实的那个 `/app/data/media`（entrypoint 原样打印前者，等于给出一条挂不到的路径）。
- **`deploy/docker-compose.yml` 的本地对象目录换成 named volume `server_media`**（③）：绑定挂载 `./data/media` 会把宿主目录属主带进容器，而 Docker 替缺目录建出来的那层属 root，容器里的 `pushbox(10001)` 写不动——同一条判据现在由上面那个探针在启动时判死。备份口径跟着改成 `docker volume inspect pushbox_server_media -f '{{.Mountpoint}}'`；`docker-compose-wsl.yml` **刻意保留绑定挂载**（`deploy-wsl.sh`/`.ps1` 按目录整体重置测试栈），注释里写明了这条差别。

### 计数闸：两处"看着有闸、其实没人看"的盲区（②）

- **C# 侧原来完全没有基线断言**：`ci.yml` 的 windows job 只跑 `dotnet test`，退出码只回答"有没有红"，用例被静默裁掉（删 `[Fact]`、改分类过滤、整类没被 discover）照样退 0。现在改判 trx（`--logger "trx;…"` + PowerShell 数 `UnitTestResult`，不解析控制台文本——那玩意的语言跟 runner 走），并加文档侧第二条计数闸，唯一真值取 `ci.yml` 里的 `$CS_BASELINE = 554`。**加它当天就抓到一处现值腐烂**：`发布准备报告` 的状态表还挂着 556/556，而其余六处同一天已改成实测的 554。
- **Java 那条只认 `core a + server b` 这种能求和的形状**，`site/index.html` 首屏那行裸的 `303 / 554` 从来不在比对范围内。新闸判"关键词同一行里的三位数对"，Java 与 C# 两个基线之外的数一律 FAIL，带日期的行整行跳过（历史证据不参与现值判定，同 `docs/审计报告.md` 的排除理由）。**负测做过**：把 `site/index.html` 改成 `303 / 555` 当场 `FAIL: 文档里出现了基线之外的 C#/Java 用例数：555`；另加一条防空转断言，没有一处文档真写着基线值时判 FAIL 而不是"全都对"。

### 文档与站点

- `deploy/部署快速参考.md`：named volume 与写入探针两条口径、拓扑 C 那一行改口；`deploy/env/prod.env.example`、`deploy/backup/backup.sh` 跟着改卷名与备份路径；`site/install.html` 拓扑表补同一句。
- `docs/软件设计方案.md` §11.2.1 加一段"用同一套跑法复验抓到的接线缺陷"（NullBean 的成因、为什么单测照不出来、判据落在 `StorageWiringTest`），§11.1.2 补"反方向也一样要出声"那句判据与 `DevCredentialsGuardTest` 的真实条数（8 → 10）。
- 测试计数按实测归一：`core` 43、`server` 263，两档合计 **306**（本轮 `+3` 全是 `StorageWiringTest`），不带凭据时 **192 执行 + 114 跳过**。`README`、两份指南、本清单、`发布准备报告`、三张 site 页面与 `ci.yml` 的基线一起抬到 306；`CHANGELOG` 里第二十七轮那两句历史数字改成不带求和形状的写法（数字留着当证据，但不让它和现值撞成两个取值，否则这条闸永远红）。

### 官网：大段说明砍掉 36%，长段落改成编号步骤卡

- **8 个页面全部按"这是什么 → 怎么敲 → 敲完看到什么算对"重写**，纯文本 72 774 → 46 243 字（−36%）。站点是给使用者看的，所以仓库内部口径整类删掉：零构建说明、"内容取自哪些文档"、CI 与签名 Secrets、单测条数、探针的代价、构建踩过的坑、"这里为什么不写死"——**官网已不再携带测试计数**，因此 Java 与 C# 两条计数闸的扫描路径都摘掉 `site/`，Java 那条的文档数下限从 ≥6 改成 ≥4（摘掉后实测 5 个文档带计数，余量 1：一处改成叙述式仍绿，两处红），`发布检查清单` 的闸门描述跟着改口。
- **排版**：`.steps` 编号步骤卡（CSS `counter` 出序号，每步一句说明 + 一条命令）替掉原来的长段落；`h1/h2/h3` 与正文宽度收到 74ch；`callout` 与 `p.sub` 补齐两级层次。全站实测 **>160 字的正文段落 0 个、>120 字的表格单元格 0 个**（剩下的长文本全是步骤卡里"标题 + 命令"两段拼出来的 `li`，正是这次要的形态）。
- **代码样式**：`site/assets/site.js` 加了一个约 40 行的零依赖高亮器（bash/powershell/python 共用一套、sql、json 各一套），保持"双击 `site/index.html` 就能看、无构建"这个前提。**唯一的不变量是重拼后 `textContent` 必须逐字符等于原文**——复制按钮读的就是它；用 62 段代码跑同一套走位验证，0 段丢字。两个坑：标志位不能整表共用 `gi`（`\b[A-Z][A-Z0-9_]{2,}\b` 加了 `i` 会把 `mvn`、`deploy` 全染成变量色），Spring 那类长参数要连着取值一起吃掉（`--pb.s3.delete-grace=2s` 是一条参数，不是四段）。
- **curl 生成器**（`site/api.html`）改完才发现问题：它用 `textContent` 重刷 `genFile`/`genOut`，等于把首屏那次高亮冲掉——现在 `render()` 之后补一次 `paint()`，实测输入标题后两段分别落 16/9 个 span 且文字完整。顺手把它那句兜底地址从 `127.0.0.1:8080` 改成 7404（表单默认值本来就是 7404，只有清空输入框才走得到兜底）。
- `site/install.html` 不再打印 dev 配置自带的占位管理口令——**公开文档里出现一个能直接登录的口令，等于教人把它带进生产**；改成"口令就是 `PB_ADMIN_SECRET` 的值，本地用自带的那份，对外必须换"。
- **Docker 部署这一节只写从 Docker Hub 拉镜像**：删掉 `docker compose up -d --build` 那行与"改了服务端代码就本地构建"的说法，`git clone` 那一步改口成"仓库里只要 compose 与 env 模板，机器上不用装 JDK 和 Maven"。自己改代码怎么构建仍然只在内部文档（`deploy/部署快速参考.md`）里讲——**站点是给部署的人看的，不是给改代码的人看的**。
- **站点接上真实图标**（用的全是 `logo/web/` 里已有的导出稿，没有新生成任何图）：`favicon.ico` + `favicon-32x32.png` + `apple-touch-icon.png` + `icon-192.png` + `icon-512.png` 五本拷进 `site/assets/img/`，8 个页面的 `<head>` 各挂三条 `link`，侧栏与首页顶栏那个手写的字母 "P" 换成真 logo（`.mark` 从此是 30px 圆角图块），首页标题做成一份 logotype——图标以 `.92em` 跟在 `PushBox` 那行字左边、随 `clamp(52px, 9vw, 96px)` 一起缩放。**先按"hero 上方单独放一张 84px 图"做过一版，被判定"太突兀"后改成行内 logotype**：浮在正文上方的小图块没有归属，嵌进标题行才读得出它是标识（880px 视口实测图标 73×73、与 79.2px 字号同一行且不折行）。**这条改动同时把 memory 里挂着的"favicon 未接"结掉了**。浏览器实测五本全 200、`naturalWidth` 非零。
- **公开渠道只留 GitHub 与 Gitee**：Gitea 那台是私有备份，不该出现在公开站点上。8 个页面的侧栏/页脚与 `README.md` 的仓库行全部删掉那个地址。**`AGENTS.md` 里那一条留着没动**——它是"推三个 remote"这条工作流的依据，删了就等于把备份地址也一起弄丢了；但它跟着仓库公开，要不要挪进 gitignored 的本地文件得先问过再改。
- **首页"源码仓库"改成静态两张卡**（GitHub 主仓 / Gitee 国内镜像，各带一条 `git clone` 与一个 ↗ 链接）。原先这两张卡由 `site/repos.json` 在运行时 fetch 出来，而 `file://` 双击打开正是站点写明的用法——浏览器不允许 fetch 同目录文件，于是首页那一节在最常用的打开方式下只剩一句报错提示。现在地址、命令、角色都写在 HTML 里，`site/repos.json` 与 `site/assets/site.js` 里那段卡片渲染代码一并删除。**卡片文案是按远端实测写的，不是照着旧文案搬**：`git ls-remote` 与 `api.github.com` 都查过——GitHub 有 `main`+`publish` 两条分支、标签与 Release 还是空的，Gitee 目前只有 `publish`。所以旧那句"Release 与安装包下载都在这里"（GitHub）与"按版本手动同步的镜像"（Gitee）都不能原样留着：前者改写成"安装包与镜像标签由 CI 在打 `v*` 标签时产出"这件由 `.github/workflows/build-apk.yml`、`build-msi.yml`、`docker-publish.yml` 三个触发器证实的事，后者改成如实的"同步是手动的，所以它可能落后主仓；提 Issue 请回 GitHub"，都不做带时间口径的断言。

### 官网发布链：`site-deploy.yml` 把 site/ 发到只装站点的 gh-pages 分支

- **形态**：`site/` 没有构建步骤，所以"发布"只是把这一棵目录树搬到一条只装它的分支根上——用 `git commit-tree` 直接以 `site/` 的树做根树生成提交，再推 `gh-pages`。分支根上因此只有 `.nojekyll` + 8 本 HTML + `assets/`，没有源码、没有 README、没有构建产物；第一条提交是孤儿，之后每条挂前一条（不一直造孤儿是因为孤儿会让上一次发布变成不可达对象，GC 一收走就连"哪一次换了哪棵树"的账都没处查）。**只依赖 `actions/checkout`，没引第三方 action**：这条链要往仓库写数据，第三方 action 是白多出来的供应链入口，而且为一份静态 HTML 在 runner 上装 Node 也没必要。
- **"有内容更新时才发布"落成两层**：① `push` 触发带 `paths: ['site/**', 本文件]`，改代码与内部文档不会惊动官网；② 比 `site/` 的树哈希与 `gh-pages` 现有根树，相同就退出。**第 ② 层不能省**：`workflow_dispatch` 完全不吃 `paths:` 过滤，只有第 ① 层时手动每点一次就多一个空 commit 与一次 Pages 构建。并发用 `concurrency` 排队而不是取消（两条部署同时推 `gh-pages` 会互相覆盖），推时用 `--force-with-lease=<分支>:<这次 fetch 到的值>`，有人在窗口里手动动过那条分支就推不动、而不是被悄悄盖掉。
- **刻意不做的事**：不开 GitHub Pages（改仓库设置要人点头；没开时只推分支、站点地址仍打不开，工作流最后一步在 404 分支里把开通那条 `POST` 原样打出来）、不挂 `v*` 标签（官网内容与版本号无关）、不删历史。非 `main` 分支手动触发且真要推上去时直接拒——Pages 只有一个地址、没有预览地址可回退，确认要发得显式勾 `force_branch`；`dry_run` 不吃这条限制，因为它只算树、比对、生成提交，一个字节都不推。
- **`site/.nojekyll` 是这条链的前提之一**：Pages 以分支为源时，根目录没有它就会拿 Jekyll 过一遍这份静态站；站点里全是原样该发出去的 HTML/CSS/JS/PNG，不该有第二套构建参与。发布前还有一步"引用必须全是相对路径"的检查——项目站地址带仓库名（`https://<owner>.github.io/push-box/`），`href="/x"` 这种根绝对路径在 `file://` 与本机静态服务器上都是好的，一上 Pages 就指到域名根，本机看不见这类坏链。
- **验证**：拿一条本地裸仓库当 GitHub 的替身，把四步 plumbing 原样跑过——推上去的根树与 `site/` 树逐字节相同、第一条提交无父、第二次同内容运行判"跳过"、`site/` 多一个文件判"要发"、lease 期望值错则推被挡下、期望值对则历史线性（第二条父=第一条）。`scripts/release-verify.sh` 新增一条 `site-deploy.yml 护栏齐全`（paths 在 / 内容比较在 / 不挂 tag / 只用官方 action / 检查 `.nojekyll`），五种会让它红的改法逐条做过负测；**第一版负测里"补上 tags 触发"那条 sed 根本没匹配上，闸门报绿其实是测了个空气**，加了"变异必须真生效"的自检才露出来。实跑 `bash scripts/release-verify.sh`：**53 通过 / 0 失败**（比上一轮多的那一条就是它）。~~这条链至今没在 runner 上跑过~~ **当天就跑了**：`ab5c9fb7` 推成 `main` 之后 run `39720441735` 四步全绿，`gh-pages` 由工作流建出来（`ff5c8c4`，根树 `f5451108…` 与那次推送的 `HEAD:site` 逐字节相同、16 个文件），Pages 自己的构建 run `39720519087` success，`https://iamplusdev.github.io/push-box/` 实测 **200**，`assets/site.css`、`assets/site.js`、`assets/img/icon-512.png`、`install.html`、`admin-api.html` 五个地址逐个 200，服务出来的 HTML 里 `grep -E '(href|src)="/'` 零命中——那条"引用必须全是相对路径"的事前检查因此被线上反证了一次。**本机验证与 runner 实跑唯一的差别是 Pages 源**：源没配置时这条链照样把分支推上去、站点地址打不开，本轮是维护者在网页侧把源设成 `gh-pages` / `/` 之后才 200 的（工作流本身不碰这个设置）。

### 发布链首跑：六个 workflow 五个绿，红的那条把 `local` 默认与 S3 门控的组合照了出来

- **首跑结论（`ab5c9fb7` 推成 `main`，2026-10-22）**：`docker-publish.yml` 真推 `main`/`sha-*`、`build-apk.yml`、`build-msi.yml`、`update-dockerhub-description.yml`（历史首跑）、`site-deploy.yml`（首推 `gh-pages`，Pages 构建绿，站点实测 200）五个 success；`ci.yml` 的 `server` job 红在 `mvn -B -pl server -am test`，1m14s 就退，另外三个 job 都绿。
- **根因**：`MediaSsePipelineTest` 那 7 条 `@Autowired S3Client`，而这一轮把 dev 的默认存储类型改成了 `local`（"本机起步只要一个口令"那次），`local` 下 `S3Config` 根本不建这个 bean——于是 `UnsatisfiedDependencyException`，7 条一起 ERROR。这条组合只有 CI 会撞到：本机没有 `PB_S3_ACCESS_KEY` 时这 7 条整类跳过，CI 里凭据齐了门放开、跑法却还是 `local`。**同一天新加的 `DevCredentialsGuard` 反向 WARN 其实一直在日志里说这件事**（"配了 `PB_S3_*` 但走 local，一个字都不读"），但它只 WARN、不判红，所以拦不住。
- **修法改测试自己，不改 CI 的环境**：`@SpringBootTest(properties = …)` 里补 `"pb.storage.type=s3"`——这个类要的是真桶，前提就该由它自己声明，而不是指望 runner 恰好把默认值设成 s3。CI 的 env 一个字没动，任何带 `PB_S3_*` 但没有 `PB_STORAGE_TYPE` 的开发机也从此跑出同一份判据。
- **复现路径**（runner 日志与 surefire artifact 匿名都是 401，读不到）：WSL 里起 CI 同款 `mysql:8.4 --ngram_token_size=2`（实测 `@@version=8.4.11`、`@@ngram_token_size=2`）+ `rustfs` + aws-cli 建桶 `pushbox`，端口发到宿主，Windows 侧用本机那份热 `~/.m2` 跑 `mvn -B -pl server -am test`。**先在 WSL 里跑失败过一次**：容器内现下依赖实测 2.6 kB/s，等不起，所以换成宿主跑 mvn。复现结果与 CI 同形（`Tests run: 263, Errors: 7`），修完这 7 条变 7/7 绿（单类 20.8s）。
- **修完全量复跑是 `core 43 + server 263 = 306、跳过 0`，但仍有 3 条红，全在这台机器的出网**：`error=unreachable host=picsum.photos`（`MediaSsePipelineTest` 两条）与 `http_404 host=httpbin.org`（`MediaFetchServiceTest.generatesThumbnail`），断言都是 `expected: "ready" but was: "failed"`——抓取侧到不了那两台公共图床，不是装配问题。**这类"用例依赖公网图床"的脆弱面本条只登记、没改**：CI runner 那边一直够得着，所以历史上绿过；真要钉死得把那几条换成本机起的测试图片服务，那是另一件事。
- **`v1.1.0` 标签在这条转绿之前不打**：Docker Hub 的版本标签按本仓口径不可变（`1.1.0`/`1.1`/`latest` 一旦推错只能换个号重来），而 `v*` 事件同时开 GitHub Release。

### 验证

- `mvn -B -o -pl core,server test`（2026-10-22）：**306 条，0 失败 0 错误，114 条按 `PB_S3_ACCESS_KEY` 门控跳过**（不带凭据即 192 执行）。本轮新增/改动的 `StorageWiringTest` 3/3、`DevCredentialsGuardTest` 10/10、`MediaObjectsConfigTest` 4/4。
- **端到端探针在 local 存储模式下 23/23 全绿**：`bash deploy/run-dev.sh --server.port=8081` + `PB_BASE=http://127.0.0.1:8081 node deploy/probe/p0-e2e.mjs`，启动行是 `对象存储后端=本地文件系统 path=./data/media encrypt=none`、`/healthz` 回 `db/storage up`。这是这条路径第一次跑通整条 P0 链——同一份 jar 在修复前起不来（就是上面那条缺陷）。用完只杀自己叫起的 PID。
- entrypoint 渲染成脚本后跑了 5 个场景（缺库变量、local 齐全、local 目录不可写、local 却配着 S3 凭据、sse-c 主钥长度不对），文案与退出码都按预期；`docker compose config` 在 WSL 里把主文件与 wsl 两本都渲染过，named volume 解析成 `pushbox_server_media`。
- `dotnet test PushBox.Crypto.Tests -c Release`：**554/554**，trx 解析出的 `UnitTestResult` 计数同一值（新闸的判据就是它）。
- `bash scripts/release-verify.sh`：**52 通过 / 0 失败**，两条 WARN（本机没有 docker CLI 判不了镜像体积、工作区还有未提交文件）。新增两条 PASS：Java 计数与 `ci.yml` 基线一致（306，9 处 / 7 个文档——第二十七轮那两句历史数字改成不带求和形状的写法之后，CHANGELOG 自己就不在这份名单里了，这正是那条约定预期的效果）、C# 计数一致（现值 306 554，8 个文档）。
- **环境状态记一笔**：本轮复验时本机 `7404` 上没有监听（那台手动起的服务端不在跑），探针全程打在自起的 8081 上。

---

## v1.1.0（2026-10-22）—— 频道密钥本机留存 + 服务端启动与单镜像部署（第二十七轮）

> 技术决策见 [软件设计方案](docs/软件设计方案.md) D73 / D74；D17 随之改为"两端都不做筛选"。

### Android 客户端

- **撤掉对话页的"全部 / 未读 / 已读"筛选**（与 PC 第二十五轮同一条理由：消息越少筛选越没意义，一条提示条 + 三个菜单项换来的只是"我怎么只看得到一半"的困惑）。删掉 `ConversationFilter`、筛选提示条与 `showFilter` 弹窗，`messagesFlow` 去掉 `readState` 参数与那条 SQL 判据；撤完之后顶栏只剩搜索一个动作，于是**连 `⋮` 一起删掉**，改成一枚 `Icons.Filled.Search` 的 `IconButton`——"只有一个动作的溢出菜单"就是白多点一下。
- **`⋮` 频道动作从 10 项 `DropdownMenu` 换成分组 `AlertDialog`**：接入与命名 / 密钥 / 治理 / 改号与清理四组，每组一个小标题（`DialogGroup`），危险项靠组名而不是只靠 `error` 色。`MenuOption` 加 `danger` 参数（放在 `onClick` **之后**，避免打断既有位置参数调用）。组内不适用的项**整条不出现**而不是灰态——一行灰掉的按钮暗示的是"权限不够"，而真实情况常常是"这一步在这台机器上根本没有对应数据"。
- **新增「复制密钥」，读的是本机留存**（D73）。服务端只存 `secret_hash`，任何列表接口都给不出明文，所以这一项没有"读一次"的实现路径；能做的是在明文经过屏幕的那四个时刻各写一次：新建（响应）、重置（响应）、手动加入（用户粘进去的那串）、订阅（同前）。存法沿用设备凭据那套：`IdentityStore` 一个 `号码\t密文` 行清单（键 `channel_secrets`），值经 `SecretVault`（AndroidKeyStore 不可导出钥 / AES-GCM / `v1:<iv>:<ct>`）封装，`App.channelSecrets` 冷启动整体解进 `StateFlow`，`ChannelListRow` 按频道号取。**三条一起定的**：解不出来时那一组显示"本机没有这份密钥"而不是灰按钮；**换发新频道号要把留存搬到新号**（先清旧号再写新号，否则一次改号就把唯一一份密钥无声丢掉）；清空写空串而不是删键，让"忘了"与"从没存过"在读取端归一。
- 文案：删 `conv_filter_title` / `filter_all` / `filter_unread` / `filter_read` / `filter_hint_*` / `filter_empty`，新增 `channel_grp_access` / `channel_grp_secret` / `channel_grp_govern` / `channel_grp_danger` / `channel_copy_secret` / `channel_secret_missing` / `confirm_close`。

### 服务端：本机启动不再逼你配一套桶

- **`application-dev.yml` 的存储默认改成 `local`**：`pb.storage.type: ${PB_STORAGE_TYPE:local}`、`local-path: ${PB_STORAGE_LOCAL_PATH:./data/media}`，同时给四个 `PB_S3_*` 占位符加空默认值（原来解析不了会原样留下字面量）。于是**起一个能收消息的本机服务端只需要 `PB_DB_PASSWORD` 一个环境变量**，图片落 `./data/media`。
- **`DevCredentialsGuard` 的必填清单跟着这次选择走**：`PB_DB_PASSWORD` 恒要，`PB_S3_ENDPOINT` / `PB_S3_ACCESS_KEY` / `PB_S3_SECRET_KEY` 只在 `pb.storage.type` 不是 `local` 时才要。报错文案补一句"只想要一个能收消息的本机服务端：`PB_STORAGE_TYPE` 保持默认的 local，就只需要 `PB_DB_PASSWORD` 一个键"。
- **`DevCredentialsGuardTest` 从 6 条扩到 8 条**：新增"local 模式只缺口令"与"local 模式下的报错**不许出现** `PB_S3_ENDPOINT`"（多列一项用不到的键，等于让人去凑一套根本用不上的桶配置）。原有 ①–⑥ 不回归，因为它们的上下文里存储类型仍是默认判据 `s3`。

### 部署：一个镜像含服务端与管理台

- **`SpaWebConfig` 的兜底回退加了保留名单**（D74）：`api` / `actuator` / `ws` / `healthz` / `admin` 加**可配的入站路径**（`pb.ingest.base-path`），命中即返回 null → 保持 404。这条修的是 2026-10-20 实测到的那个坑：漏掉 `/api/v1` 前缀的 `GET /admin/channels` 回的是 **HTTP 200 + 一段 HTML**，只看状态码的脚本一路走下去、取字段全 `undefined`，症状长得像"服务端返回空数据"而不是"URL 写错了"。**判据是第一段整段相等，不是 `startsWith`**——管理台的搜索页路由是 `/messages`，按前缀匹配会被入站的 `/message` 吃掉，页面刷新一下就 404（这条错向比原来那条更难发现）。回退目标改成构造期注入，因为本地/CI 的 jar 里通常没有 `static/index.html`，不注入就没法测这条分支。
- **新增 `SpaWebConfigTest` 7 条**：前端深链与缺失资源回退、后端路径保持 404、`/messages` 与 `wso` 这类撞前缀的名字不被误杀、入站路径可配（改名后旧路径回退给前端）、base-path 配成空/带斜杠/撞名单时名单不重复不炸、真存在的文件优先、没有 SPA 的 jar 返回 null 而不是抛异常。
- **`deploy/Dockerfile` 的 entrypoint：S3 三项检查改成 `PB_STORAGE_TYPE=local` 时跳过**——否则"零依赖单机部署"这条文档路径在容器里被硬拦。`EXPOSE 8080` 纠正为 `EXPOSE 7404`（真实端口一直是 `${PB_SERVER_PORT:7404}`，那行只是给人看的，但看的人会被它骗）。补一句本地对象落在 `/app/data/media`、不挂卷 `docker rm` 之后全没。
- **`deploy/docker-compose-wsl.yml` 的三个 `PB_S3_*` 从 `${VAR:?}` 降为 `${VAR:-}`**（同一条理由；空值不会静默，`S3Config` 在 s3 模式下逐个点名并拒绝启动，只是从 compose 解析挪到了进程启动）。
- **`deploy/部署快速参考.md` 补两节**：「📦 单镜像部署（服务端 + 管理台同一个容器）」给出 `docker run` 跑法与三条不能省的（挂卷、端口以 `PB_SERVER_PORT` 为准、`/app/data` 归 uid 10001）；「加密口径」用一张表说清 `PB_S3_OBJECT_ENCRYPT` 管的是**对象字节**、与用不用对象存储服务无关，`local` 模式同样吃这条判据。
- **顺手修掉一处会让文档路径跑不起来的缺陷**：`方式二`（本地存储）那份 `.env` 原来写 `PB_S3_OBJECT_ENCRYPT=none` 却没有 `PB_ALLOW_PLAINTEXT_MEDIA=1`，照抄会启动即被 `ProdPlaintextMediaGuard` 拒绝。

### 文档与官网

- **D17 改成"两端都不做"**；§7.4 对话页那张 ASCII 图与"筛选"那条整段重写（顶栏只剩放大镜），§7.4 ③ 频道页补分组弹窗与"复制密钥读本机留存"三条，§9.3 状态表删"筛选态"一行，§11.1.2 改成"dev 只要一个键"，§11.2 加"一个镜像两种界面"、拓扑表补 C、必填项清单跟着 compose 的真实判据重写、`.dockerignore` 的描述改成它实际的黑名单形状。新增决策 **D73**（客户端密钥留存）与 **D74**（单镜像下的兜底路由判据）。
- **`用户使用手册`**：新增 §2.5「管理我的频道」（分组表 + 复制密钥的两条前置认知），搜索一节改点放大镜，Windows 那节残留的"§3.4 筛选消息"删掉（PC 第二十五轮就撤了，文档一直没跟上），后续小节顺延重编号。**另加 §4「Web 管理台」**（五页能做什么/看不到什么一张表，随后 常见问题→§5、故障排查→§6）——单镜像把这五页送到了每个装服务端的人手上，可三端文档里以前只有 `site/admin-api.html` 那份接口参考，没有一句"打开哪个地址、要什么口令、哪两件事界面上没有"。§4 里三条判据都是现读的：管理台在**根路径 `/`**；没配 `PB_ADMIN_SECRET` 时是**一律 403**（`AdminAuthController` 的默认状态，不是故障）；建频道与 `rotate-secret` **只有接口、没有界面按钮**（`Channels.vue` 现查确实没有这两个动作），所以那一节明确把用户指回客户端的「频道」页。
- **`README`**：管理台地址从 `https://<host>/admin` 纠正为**根路径 `/`**——vite 没配 `base`、路由是根下的 `/dashboard`、`/channels`、`/messages`，`/admin` 从来不是真实地址（现在按保留名单直接 404）。
- **`site/usage.html`**：删筛选那条、搜索改成放大镜、"「我的」→「接入」"改成底栏「频道」（这条在第二十六轮就搬了，页面没跟）、新增分组弹窗与本机留存密钥那段。**`site/admin-api.html`**：那条"前缀漏掉 → 200 + HTML"改成"以前 200，现在 404"，并保留"验 `Content-Type` / 验 `ok` 键"这条纪律（现网旧版本仍回 200）。**`site/install.html`**：dev 凭据改成"只必填 `PB_DB_PASSWORD`"、对象存储一节标注"要用桶时才需要"、生产拓扑表补 C 与单镜像/根路径说明。

### 本机起步的三处"文档说的和跑的不一样"

这轮把 §11.1 那套联调配置逐行对着真实文件与真实进程读了一遍，三处错向都不致命、但都会让人白花半小时：

- **`deploy/env/local.env.example` 的每一项补上 `export`，并把"dev 根本不读哪些键"标出来**。前半条：模板被 `source` 进当前 shell，少了 `export` 只得到一组 bash 变量，而 JVM 与 `@EnabledIfEnvironmentVariable` 读的是进程环境——照模板抄的第一次启动会报"没配 `PB_DB_PASSWORD`"，114 条集成测试整批静默跳过，两个症状都长得像别处的 bug（真实的 `local.env` 一直带 `export`，缺的只是模板）。后半条更阴：**`application-dev.yml` 只有 `PB_DB_PASSWORD` / `PB_STORAGE_TYPE` / `PB_STORAGE_LOCAL_PATH` / `PB_S3_*` / `PB_ADMIN_SECRET` 是占位符**，库地址与库用户写死成 `127.0.0.1:3306/pushbox` + `pushbox`，`object-encrypt` 写死 `none`，`admin.jwt-secret` 写死一份仅本地的值，端口来自 base yml。所以往 `local.env` 里加 `PB_SERVER_PORT` 换端口、或改 `PB_S3_OBJECT_ENCRYPT` 试加密，**改完一点变化都没有**，而人会继续怀疑代码。模板现在把带 ◆ 的（dev 真读）与"dev 不读"分开标，`开发指南` 同一段补了这句判据。
- **默认端口全仓库统一成 7404**。文档与探针里成片写着"默认 8080"（§11.1 的 yaml 片段、curl 示例、`site/install|overview|index|api|usage`、两个探针的 `PB_BASE` 默认值、开发指南的"用户手动起 8080"），而 `application.yml` 与 prod 的 `${PB_SERVER_PORT:…}` 默认都是 7404、dev profile 根本不设端口、真机验收用的是 `adb reverse tcp:7404`。现查本机那个跑着的实例也在 `0.0.0.0:7404`（`/healthz` 回 `db/storage up`）。**改的是文档与默认值，不是运行时**：没有往 `application-dev.yml` 加 `server.port`——那会让下一次手动启动从 7404 搬到 8080，把已经配好地址的手机与 PC 一起甩掉。探针头部补一句"那台端口上是别人的实例就另起 8081"，因为默认值现在真会指到一个活着的库。
- **`run-dev.sh` 的文件头**还写着"只连远端 MySQL/RustFS"（2026-10-13 库就搬到本机了，昨天存储也改成本地文件），改成按现状描述。

- **§11.2 的 `Caddyfile` 一节整段重写**：以前的写法是"Caddy 上自签 TLS + 显式设 `read_timeout 300s`、`idle_timeout 300s`"，而真实的 `deploy/Caddyfile` 只监听 `:80`、没有 `tls` 那一行，那两个子指令在 `caddy:2`（v2.11.7）的 `reverse_proxy` 与 `transport http` 两处都不合法（v2.16 才提升到顶层）——照旧文档抄的结果是配置加载失败、容器重启循环。同一段里 Prometheus/Caddy 的上游端口从 8080 改成实际的 7404，"上 HTTPS 的配套动作"补上那条容易踩空的实情：`network_security_config.xml` 只信任 `src="system"`，**用户级安装的根 CA 它不认**，`res/xml/network_security_config.xml` 的注释同步改掉"生产时整份替换"这句与 §11.1 明文允许项自相矛盾的话。
- **测试条数按现跑实测**：`core` 43、`server` 258 两档合计 **301**（本轮 `+9`：`SpaWebConfigTest` 7 条、`DevCredentialsGuardTest` 2 条），不带凭据时 **187 执行 + 114 跳过**。`开发指南` / `测试指南` / `README` 三处原来写 292/249 的同一行、以及 `测试指南` 报告模板里的勾选项一起改。
- **本机起步那一步，`README` 与两张 site 页面都指错了模板**：写的是 `cp deploy/env/prod.env.example deploy/env/local.env`。那一份的口径是容器（键集、必填项、默认值都按 compose 写），拿它当本机模板填出来的是一个"名字对了、内容不对"的 `local.env`；本机模板一直是 `deploy/env/local.env.example`。同一句的注释也从"填 `PB_DB_PASSWORD` / `PB_S3_*`"改成"起步只必填 `PB_DB_PASSWORD`"，前置条件里"S3 兼容桶（本项目用 RustFS）"整项删掉——dev 默认 `local`，桶是"要验桶形态才装"的那一个。
- **"管理台能不能建频道/重置密钥"四份文档四种说法，现读代码归成一份口径**：`Channels.vue` 全文只有 `刷新` 与 `删除`（归档）两个动作，`ChannelDetail.vue` 只有一个 `返回`、整页只读——也就是**建频道与 `rotate-secret` 确实只有接口、界面上没有按钮**（`site/admin-api.html` 那份端点总表标的"控制台：否"一直是对的）。对不上的三处：`README` 的已知限制把这节标题写成"Web 管理台支持频道创建与密钥重置"（读起来像界面能点），`发布检查清单` 有一条**怎么演**写成"管理台建频道 → … → 管理台发一条测试消息"（两个动作都没有按钮，照着演会卡在第一步），§11.1 的第三方自测引导写成"在 App 的新建频道或 **Web 管理台创建**时一次性回显"。三处统一改成：日常走 Android/PC 的「频道」页，脚本才用那两个端点；即时消息走 `POST /message`，管理台能触发推送的只有定时任务。

### 验证

- `mvn -B -o -pl core,server test`（2026-10-22 复跑两次，改 `application-dev.yml` 注释前后各一次）：**301 条（core 43、server 258 两档），0 失败 0 错误，114 条按 `PB_S3_ACCESS_KEY` 门控跳过**（不带凭据即 187 执行 + 114 跳过，与既往口径一致）。其中本轮新增/改动的 `DevCredentialsGuardTest` 8/8、`SpaWebConfigTest` 7/7。跑法必须让 `core` 与 `server` 在同一个 reactor（`-pl core,server` 或 `-pl server -am`），否则用的是本地仓库里的旧 core jar。
- 现查本机在跑的那个服务端：`0.0.0.0:7404` LISTENING，`/healthz` 回 `{"ok":true,"db":"up","storage":"up"}`，`/` 回 404 JSON（API-only 构建，jar 里没有 `static/index.html`，正是 `SpaWebConfigTest` 第 ⑦ 条钉住的那个分支）。这一条就是"默认端口按 7404 写、不要动 dev 的 `server.port`"的直接依据。
- `./gradlew :app:assembleRelease` BUILD SUCCESSFUL，release APK 已装到真机（`4TF6HQ8TCE6XHURO`）。
- `dotnet test windows/PushBox.sln -c Release`（2026-10-22 复跑）：**554 通过 / 0 失败 / 0 跳过**，退出码 0。比上一版少 2 条是撤掉「全部/未读/已读」筛选时连带删掉的 `MainModelTest` 两条筛选用例，不是测试被裁剪。
- `bash scripts/release-verify.sh`：**第一轮跑出 2 条 FAIL**，两条都是这轮改文档改出来的，且都只有机器能抓：① 用例计数闸发现仓库里同时存在两个"现值"（292 与 301）——`ci.yml` 的基线还钉在 292，`site/install.html` 与 `site/overview.html` 那两行没跟着本轮改，`site/index.html` 首屏更是停在更早的 288。现按实测把基线抬到 301，`CHANGELOG` 与 `发布检查清单` 里那句**历史**数字改成不带"core 加 server"形状的写法（旧值留着当证据，但不让它和现值撞成两个取值，否则这条闸永远红）。② 地址泄漏闸抓到 `docs/用户使用手册.md` 里一个写死内网 IP 的示例，改成 `<服务端所在机器的内网 IP>` 占位符。改完复跑 **51 通过 / 0 失败**，只剩两条 WARN：本机没有 docker CLI（判不了镜像体积）、以及跑的时候工作区还有未提交文件。
- **仍未完成的验证**：本轮两处 Android 改动（对话页放大镜、频道分组弹窗与复制密钥）的**真机截图验收**——设备当前停在数字密码锁屏界面，需要解锁后才能拍。`EnrollScreen` 那一屏同样还没拍过（要退出登录，属破坏性操作）。
- **没有重跑的**：端到端探针（默认 `PB_BASE` 这轮从 8080 改成 7404，会写到本机那个活实例上，要验得先另起 8081）。

---

## v1.1.0（2026-10-21）—— Android 端 UI 对齐 PC（第二十六轮）

> 技术决策见 [软件设计方案](docs/软件设计方案.md) D72；规范落在 §7.4 / §7.5 / §9.1 / §9.2。

### Android 客户端

- **两根条不再用 Material3 组件**：`TopAppBar`（64dp）/ `NavigationBar`（80dp）换成手写的 `PushBoxTopBar`（内容 48dp）/ `PushBoxBottomBar`（内容 52dp）。顶栏底 `#EDEDED`、底栏底 `#F7F7F7`，各带一条 0.5dp `#DEDEDE` 发丝线。栏底色铺在 `statusBarsPadding()` / `navigationBarsPadding()` **之前**，所以系统条看起来是同色延伸，而不是栏上面一条黑缝。
- **状态栏字符色跟随深浅色**：`isAppearanceLightStatusBars` 原本写死 `true`，深色模式下变成"深底深字"完全看不见；现在与 `isAppearanceLightNavigationBars` 一起进 `LaunchedEffect(dark)`。
- **配色 token 落进 `ui/Theme.kt` 的 `PushBoxColors`**：会话列表整张 `#FFFFFF`、行分割线 `#DEDEDE`（左缩进 66dp = 16 + 40 头像 + 10 间距，起于文字而非头像底下）、气泡 `#FFFFFF` + 正文 `#161415`、页面底 `#F5F5F5`。`bubbleIn` 更名 `bubble`（单向接收，"入/出"两档已经没有读者）。同时把 Material3 的 `ColorScheme` **整套**换成中性阶梯（`surfaceContainer*` / `primaryContainer` / `secondaryContainer` / `surfaceTint`），否则 `Card`、填充式 `TextField`、提示条仍走基线的紫。
- **对话页顶栏标题居中**，且标题带 30dp 会话头像 + 单行省略；右侧只剩搜索一个动作，所以直接放一枚放大镜图标（`⋮` 与"全部/未读/已读"筛选在第二十七轮整条撤掉，见下一节）。
- **头像形状与定色同步 PC**：圆角按 `size × 6 / 44`（微信式方圆形，非正圆），首字字号 `size × 16 / 44`，六色调色板与 `AvatarVisual.Palette` 逐值对齐（D33 的 31 多项式保证同一个发送者两端同色）。
- **首启接入页照 PC 重排**：新增 HTTPS 开关，`baseUrl` 从第三个 `state` 改成 `remember(host, port, https)` 派生（三处状态各改各的，出现过"开关关了 URL 还写着 `https://`"）；"最近使用"一排胶囊可点，点完由 `applyUrl()` 把 scheme/host/port 三段拆回来；「加入已有空间」默认收起；报错从贴在表单尾巴上的一行红字改成带 3dp 红色左轨的卡片（`height(IntrinsicSize.Min)`，否则 `fillMaxHeight()` 在无定高约束的父容器里塌成 0，轨直接消失）。
- **底栏拆成 消息 / 频道 / 设置，`MineTab` 删除**：接入是日常操作，收进「我的 → 接入」等于每次多一层（D17 的收纳只对低频设置成立）。设置页不再夹频道 CRUD。
- **频道行从 12 枚平铺按钮改成"两行信息 + 一枚 ⋮"**：动作收进 `⋮`，危险项着 `error` 色。提醒强度上限（`max_level`）并进「频道治理」弹窗，`PushBoxApi.setChannelMaxLevel()` 随之删除——它本来就是 `ChannelPatch` 的一个字段，PC 侧也一直在治理卡里。（这一枚 `⋮` 原本开的是 10 项 `DropdownMenu`，第二十七轮换成分组弹窗并加上「复制密钥」，见下一节。）
- **长内容的口径是"截断 + 可复制"而不是换行堆高**：频道号等宽 + `maxLines=1`，`InfoRow` / `CopyableField` 的值 `maxLines=2 + Ellipsis`（Compose 的 `Box` 不裁剪子内容，不设上限的长 URL 会横向溢出压到隔壁，症状长得像渲染 bug）。
- 文案与资源同步：`tab_me` / `mine_tab_access` / `mine_tab_settings` 删除，新增 `tab_settings`、`channels_refresh`、`channel_actions`、`channel_copy_no`、`channel_flag_muted`、`enroll_have_code_*`；`channel_not_subscribed` 缩短为"未接入"。

### 文档

- §7.4 重写：三 tab、两根条的高度与色、会话列表分割线、对话页居中标题、新增「③ 频道页 / ④ 设置页」两块（原来只有一张「我的页」清单）
- §7.5 补首启接入页排版与三条理由；§9.1 增补 Android 那 7 个 token 与"两端不强行同值"的说明；§9.2 补栏高、头像公式与调色板
- 全文清掉旧的 Android 导航路径（9 处「我的 → …」→「设置 → …」/「频道 → ⋮ → …」）
- [用户使用手册](docs/用户使用手册.md) §1.2 / §2 同步：三 tab、频道页操作路径、HTTPS 开关与"最近使用"、搜索改成实际入口（会话内 `⋮`）

### 验证

- `:app:compileDebugKotlin` 与 `:app:assembleRelease` 均通过；release APK（`dev/pushbox-release.jks` 签名，与机上 1.1.0 同签名）已 `adb install -r` 就地覆盖，数据未清
- **真机截图核对已完成**（2026-10-22 凌晨解锁后补跑）：消息列表 / 对话 / 频道 / 设置四页 × 浅色 + 深色两档，共 8 帧。栏高与 `#EDEDED`/`#F7F7F7` 双色、状态栏字符极性、分割线 66dp 缩进、居中标题、方圆形头像与调色板均按 §9.1/§9.2 落地
- **截图抓出一处漏色缺陷**（编译、代码审查与 Compose 预览都看不到，只有真机看得出来）：`SettingsSection` 的 `Card` 与填充式 `TextField` 在频道/设置两页渲染成**淡紫色**。根因是 `lightColorScheme()` / `darkColorScheme()` 只覆盖传进去的那几项 token，`surfaceContainer*`、`secondaryContainer`、`primaryContainer`、`surfaceTint` 全部沿用 Material3 基线的紫（`#E6E0E9` / `#E8DEF8` / `#B69DF8`），而 `Card` 的默认容器正是 `surfaceContainerHigh`。修法是把这一整套中性阶梯一起写进两套 scheme；重新构建装机后浅色/深色两档复验，卡片与输入框均为中性灰绿。**口径**：只要项目自己声明了"微信式中性"，`ColorScheme` 就必须整套覆盖，不能只挑用得到的几项——详见 D72 ⑦
- **首启接入页（`EnrollScreen`）仍未截图**：它只在无身份时出现，要拍到必须先退出/清空设备身份，那是破坏性操作，需先确认
- **顺带修掉一处已存在的编译断裂**（不是本轮引入）：`PushBoxApi.kt` 里 `formatErrorMessage` 是 companion 方法却引用了实例的 `code`，`ApiError` 构造改判 `formatErrorMessage(code, body)`。这一条卡住了此前所有 Android 命令行构建

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
- **入站 JSON 不再放过"尾部残留 token"**（2026-10-21，落点在全项目唯一的 `core/src/main/java/dev/pushbox/core/protocol/Json.java`）：Jackson 默认看完第一个完整对象就返回，后面的字节不检查，于是 `{"name":"x"}y` 这种请求体是"解析成功 + 请求真执行 + 200"，多出来的部分被安静丢掉。当天在旧版服务端上实测过：这样真的建出了一条频道；而 `{"name":"a"}{"name":"b"}` 两个对象拼一块时只有第一个生效，提交者以为两段都进去了。开了 `FAIL_ON_TRAILING_TOKENS` 之后同一份请求体经 `RequestBodyJson` 收成 400 信封（`invalid_param` + "请求体必须是合法 JSON"），尾部只留空白或换行仍然放过——那不是 token。**代价要说清**：这把尺子是 HTTP 的 `byte[]` 端点、WS 入站帧与管理台 JWT payload 解析共用的，所以这是协议级收紧，不是某个端点的小补丁；`FAIL_ON_UNKNOWN_PROPERTIES=false` 那份向前兼容没动。HTTP 那一侧有两组用例（管理台 + 设备侧）和活服务端实测兜着；WS 那一侧当时只有 mock 级单元用例（6 条，不经过真实 socket），只证明了"这种输入会在 `Json.read` 抛"；2026-10-21 补的 `PushboxWsSocketTest` 让握手与密文帧真过了 TCP，但它发的是合法帧，`Json.read` 抛错之后 handler 怎么处置仍没有端到端覆盖
- **`POST /api/v1/admin/channels` 的 `groupId` 改成必填且要真实存在**（2026-10-21，`AdminController.requireKnownGroup`）：此前是 `request.groupId() != null ? … : auth.groupId()`，而管理台分支的 `auth.groupId()` 是 `DeviceGuard` 造出来的字面量 `"admin"`（管理 JWT 没有设备行），`groups` 表里根本没这行；设备侧的频道列表按 `PbStore.listChannels(groupId)` 过滤，所以这样建出来的频道**入库成功、管理台看得见、任何真设备永远查不到**，而且入站照常发消息、没有任何一处报错。现在省略或全空白 → 400 `缺少 groupId：频道要建在设备所在的那个 Space 下，否则设备查不到`；给了一个 `groups` 里查不到的 id（含字面量 `admin`）→ 400 `groupId 不存在：这个 Space 里还没有设备注册过`；顺带把首尾空白 trim 掉再落库。校验用的是 `PbStore.findGroup()`——这个方法一直在仓库里，只是**从没有人调用过**。**为什么不在 `ChannelService.create` 收口**：设备侧那一条传的是真设备行的 `auth.groupId()`，本来就必然存在，在服务层加校验会让每次建频道多查一次库、还会把 `ChannelServiceTest` 那批 mock 出来的 `store` 一起打进 null 分支。**要留个底的两件事**：① 已经建在 `admin` Space 里的老频道不会被这次改动搬走，仍然只有管理台看得见，要给用户收到只能在真实 Space 下重建再归档旧的（本机联调库现查是 6 条，全部已是归档态、活跃 0 条）；② **管理台界面本来就没有"新建频道"入口**（`web-admin/src` 里 `createChannel` 零命中，`api/channels.ts` 只有列表/详情/删除三个函数），所以这条只影响照 `site/admin-api.html` 用 curl 的建站者，不影响任何点击路径

### Web 管理台

- 新增"定时推送"页（`web-admin/src/views/ScheduledTasks.vue`）：选频道、填标题/正文/强度/图片外链与 cron，列表直接显示上次触发结论（`ok`/`failed` + 错误摘要 + 上次消息 ID）与下次触发时间，支持启停与删除。列表只回 `content_head`，编辑时才取全文与图片
- **定时推送页的分类筛选与新建时选分类**：列表多一列「分类」，页头多一个筛选下拉；下拉的选项**从当前列表里现取**（不额外查字典表），里面还多一个"未分类"项——因为 `el-select` 拿到空串会显示成 placeholder，所以用 `__unset__` 当哨兵值，判据仍是"这一行的 `category` 是空串"。表单里的分类框是 `filterable allow-create`：既能在已有标签里选，也能当场敲一个新的，与服务端"自由标签、不校验取值"是同一套语义。筛选走前端 `computed`，与这个页面既有的关键字搜索一致（列表本来就是一次全量拉回来的）。**保存前还硬判一次 32 字**：分类用的是 `allow-create` 的下拉，它的输入框没有 `maxlength` 可挡，不拦就会被服务端静默截到 32 字——截出来的那个词和用户想写的已经不是同一个分类了
- 六个管理台端点：`GET`/`POST /api/v1/admin/scheduled-tasks`、`GET`/`PUT /{id}`、`POST /{id}/enabled`、`DELETE /{id}`。任务请求体上限单独放宽到 64KB——正文本身允许 32K 字符，沿用 8KB 的通用上限会把合法任务挡在门外

### 修复
- **锁屏通知隐私泄露**（2026-10-21，Android release/R8）：`Notifier.updateSummaryNotification()` 造的分组摘要通知缺 `setVisibility(PRIVATE)` + `setPublicVersion(lockScreenPlaceholder())`，安全锁屏 + 系统「隐藏敏感内容」下会直接列出消息正文（单条会话通知已正确走 `VISIBILITY_PRIVATE + publicVersion`，但摘要 builder 漏了这两行）。修法落在 [android/app/src/main/java/cn/iamplus/pushbox/notify/Notifier.kt:111-127](file:///D:/Projects/Android/PushBox/android/app/src/main/java/cn/iamplus/pushbox/notify/Notifier.kt#L111)，给摘要 builder 补 `.apply { setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(lockScreenPlaceholder()) }`；私密会话用 `VISIBILITY_SECRET`。真机复验（设备 ID `4TF6HQ8TCE6XHURO`，系统设置里把锁屏通知改为「隐藏敏感内容」）：连发 4 条唯一文本消息（seq 3450–3453），关屏后点亮屏幕确认单条与摘要都只显示"你有一条新消息"占位，无正文泄露；解锁后会话内 4 条消息完整入库、无重复。这是发布前必须修的隐私缺陷，此前只在模拟器上发现、未修即列进清单。
- **App 锁深色模式漏色**（2026-10-21，Android release/R8）：深色模式下 App 锁屏仍是浅底黑字（[MainActivity.kt:281](file:///D:/Projects/Android/PushBox/android/app/src/main/java/cn/iamplus/pushbox/MainActivity.kt#L281) 的 `Column` 背景没跟随主题，标题颜色硬编码默认值）。修法：`background(MaterialTheme.colorScheme.background)` + 标题改用 `onBackground`；重建 release（12,776,366B）安装后复截深/浅两档锁屏，底色与标题都跟随主题。这条是在逐页截图核对时发现的，属于深色三档验收的一部分。
- **WS 补拉阈值改为配置项并补 6 条 handler 用例**（2026-10-21）：`GAP_THRESHOLD` 原来硬编码 2000，注释里写的 `--pb.ws.gap-threshold` 从没绑过；现在挂在 `PbProperties.Ws`（默认 2000、下限 1）。新增 `PushboxWsHandlerTest` 6 条：hello 帧、签名不符拒绝、kx + `resume_ok`、补拉逐批取空、gap-threshold 生效、心跳判死/发 ping。全量 **288 条**（server 245 / core 43）0 失败 0 跳过；不带凭据时 174 执行 + 114 跳过
- **WS 真 socket 集成测试 4 条**（2026-10-21）：`PushboxWsSocketTest` 起一个真的 Tomcat（`WsSci` + `StandardWebSocketHandlerAdapter`），用 JDK 的 `java.net.http.WebSocket` 客户端走 TCP 上的 hello→auth→kx→密文帧，客户端侧用 core 的 `FrameChannel` 解帧。覆盖 ① 重放两帧 `notify=false` 后 `resume_ok`；② 心跳 ping/pong 与 `rotate-after-frames=2` 触发的明文 kx（epoch 递增、新密钥序号从 0 重启）；③ 落后超阈值只回 `resume_gap`；④ 坏签名先回 `auth_fail=bad_signature` 再以 1008 关闭。**踩到的一个坑**：`Tomcat.addContext` 不装 DefaultServlet，没有 Wrapper 映射时请求在过滤器之前就 404，`WsFilter` 根本没机会升级握手——用例里显式装了 DefaultServlet。全量 **292 条**（服务端 249、加密库 43，本轮之后已被 301 取代），0 失败 0 跳过；不带凭据时 178 执行 + 114 跳过
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
- **WSL 镜像构建卡死的根因**：容器里 `repo.maven.apache.org` 解析到 AAAA 记录，而 Docker bridge 网络没有可用的 IPv6 出口，Maven 下载一直挂着（同一 URL 在宿主机走 IPv4 秒回）。构建改用 `docker buildx build --network=host`；排查口径与另外两个坑（Caddy 按 `PB_CADDY_HOST` 匹配 Host，用 `127.0.0.1` 访问得到 200 空响应体；这台机器上的 Windows `curl` 会把内联中文参数按 GBK 发出）记进 [WSL测试速查.md](deploy/WSL测试速查.md) 的 Q1.7/Q1.8

### 生产部署资产（2026-10-20 生产 compose 全链路实跑发现并修复）

实跑方式是：用一个隔离的 compose project 起生产 `docker-compose.yml`（端口 17404/18080），复用测试栈里已有的 MySQL 与 RustFS 作外部网络，逐项验证密钥闸门、内网白名单、Caddy、管理台登录、WebSocket、定时推送、备份与恢复。下面每一条都是实跑撞出来的，不是纸面推演。

- **监控栈拆成独立文件** `deploy/docker-compose.monitoring.yml`：Prometheus/Grafana 原本靠 `profiles:` 与主栈同居，但 compose 的 `${VAR:?}` 插值是全文件生效、跟 `profiles` 无关——不开监控的部署也会被 `GF_SECURITY_ADMIN_PASSWORD is missing` 挡在 `up` 之前。主文件里现在不留任何"只有监控才需要"的必填项，`release-verify.sh` 加了断言钉住这条
- **`PB_SERVER_PORT` 补上真正的消费点**：此前只有 compose 端口映射、容器 healthcheck 和 Caddy 反代认这个变量，服务端进程依旧听 7404——改端口的人看到"起来了、健康检查也过"，实际所有流量打在一个没人监听的口上。现在 `application-prod.yml` 的 `server.port` 从 `${PB_SERVER_PORT:7404}` 取值
- **`PB_STORAGE_TYPE` / `PB_STORAGE_LOCAL_PATH` 从死变量变成透传变量**：prod profile 不加载 `application-dev.yml`，而 `application-prod.yml` 里没人读这两个键，于是"单机无对象存储"的生产配置填了 `local` 也照样去连 S3。补齐 `pb.storage.type` / `local-path` 映射，并把变量清单写进 `prod.env.example`
- **内网白名单默认值改成 fail-closed**：`PB_SECURITY_INTERNAL_CIDRS` 原来是 `${VAR:-172.16.0.0/12}`——compose 的 `:-` 在变量为空字符串时也会替换，等于每个忘记配置的部署都默认把整段私有网段放行到 `/healthz` 与 `/actuator/*`（消息正文、频道枚举都在这两个端点上）。默认值改为空，只留 loopback；要放行监控必须显式写桥接子网。注意闸门拒绝时返回 **404 而非 403**（不暴露端点存在性），Caddy 的 `@metrics → 403` 只覆盖 `/actuator/*`，不覆盖 `/healthz`
- **S3 三项必填改为可选**：`PB_S3_ENDPOINT/ACCESS_KEY/SECRET_KEY` 从 `${VAR:?}` 降成 `${VAR:-}`，否则 `local` 模式根本起不来；空值的后果改由 `S3Config` 在 `s3` 模式下拒绝启动兜住，本地 storage 路径与明文媒体各自有守卫（`ProdPlaintextMediaGuard`、Dockerfile 入口脚本里的 JWT 长度检查）
- **RustFS 端口与建桶口径纠正**：镜像监听 9000（S3）/9001（Console），文档和 `wsl-test.env` 里写的 7101 是另一台存储机上那个实例的端口，照抄过来得到的是 connection refused。更坑的是 `/healthz` 的 `storage` 只校验配置项非空，既不探端点也不查桶，所以"端点写对、桶没建"这种配置健康检查全绿、每条带图消息停在 `failed`。部署快速参考 新增"桶要建一次"章节（`mc alias set` + `mc mb --ignore-existing`），CI 与 compose 注释都记录了 9000/7101 的区别
- **备份脚本两处硬化**：`backup.sh` 开头加依赖预检（`mysqldump`/`gzip`/`age` 缺任意一个立刻退出，不再"跑了个空备份还报成功"）；mysqldump 加 `--no-tablespaces`——8.4 默认导出表空间需要 PROCESS 权限，普通业务账号会在 stderr 打印 `Access denied` 然后**以 0 退出**，产出一个缺 DDL 的归档

### CI 门禁

- `.github/` 解除 gitignore，新增 `ci.yml`（PR 与 `main` push 触发）：`server` job 起 `mysql:8.4 --ngram_token_size=2` 与 RustFS 服务容器，预建 `pushbox` 桶，跑真实 `mvn -B -pl server -am test` 后按 surefire XML 汇总设闸（`skipped != 0` 或 `tests < 261` 直接判失败；261 是当时实测的用例总数，基线只许涨，当晚补完管理台坏请求体的 4 条用例后抬到 265，夜里补完设备侧那 5 条后抬到 270）——此前"DB 闸门用例按约定跳过"意味着本地一句 `mvn test` 就能把大半测试静默跳掉还报绿。另含 `web-admin` 构建、Windows 加密库 `dotnet test`、Android `assembleDebug` 三个 job
- **`ci.yml` 第一次在 GitHub runner 上跑就红了两个 job（2026-10-20，`publish` 推成 `main` 那一次），两个根因都在外部依赖上**：
  - `server` job 的「等 RustFS 就绪并建桶」死在拉镜像：`minio/mc:latest` 报 `pull access denied ... repository does not exist`（exit 125）。查实的现状是 **`minio/mc` 与 `minio/minio` 都已从 Docker Hub 下架**（`hub.docker.com/v2/repositories/minio/{mc,minio}` 实测 404），连 `dl.min.io/client/mc/release/linux-amd64/mc` 也返回 **410 Gone**，所以"从宿主或另起一个 mc 容器"这句运维口径整体作废。改成 runner 自带的 AWS CLI v2（Ubuntu 24.04 镜像 Readme 实测预装 2.37.13）：`aws --endpoint-url http://127.0.0.1:9000 s3api create-bucket --bucket pushbox` 再 `head-bucket` 复核。**换之前先在本地对真 `rustfs/rustfs:latest` 容器跑过**：`create-bucket` 退 0、同一命令重复执行幂等、`head-bucket` 退 0；再把提取自 YAML 的这段步骤套上 `aws` shim 复跑一遍，确认等待循环与"runner 上没有 aws 就明确报错"的分支都按预期走。顺带把 `deploy/部署快速参考.md` 的建桶章节补上 aws 写法（用户侧也取不到 mc 了），并把 `docker-compose-wsl.yml` 那条 rustfs 的建桶注释改成实测可用的 `--network container:<rustfs>`（那份 compose 不给 rustfs 发布端口，`--network host` 打不到 9000——这个写法同样在本地起了隔离网络实测过）。
  - `android` job 的「Set up Android SDK」死在 `android-actions/setup-android@v3`：它要装已经下架的 legacy `tools` 包，`Failed to find package 'tools'` + sdkmanager 退出 1（`/usr/local/lib/android/sdk/cmdline-tools/16.0/bin/sdkmanager`）。删掉这一步，直接用 runner 预装的 SDK（同一份镜像 Readme：platforms 含 `android-36`、build-tools 含 36.0.0/36.1.0、platform-tools 37.0.1，且 `ANDROID_HOME`/`ANDROID_SDK_ROOT` 已导出，真缺组件时 AGP 会自己走 sdkmanager 补），换成一步"入镜检查"：从 `android/app/build.gradle.kts` 现读 `compileSdk`，断言 `platforms/android-<CS>` 存在且 `build-tools` 非空，红的时候直接说出缺哪一项。`build-apk.yml` 同口径改掉（它后面那步 `apksigner` 就是从 `build-tools` 取的）。四种分支（正例 / 缺 platform / 空 build-tools / 无 `ANDROID_HOME`）是把脚本从 YAML 里抽出来在 bash 里跑的，不是看一眼写的。顺便把这条链上一个悬着的风险查实：Gradle 9.6.1 支持在 Java 25 上运行（官方兼容性表：Java 25 自 9.1.0 起支持），所以 android job 用 JDK 25 不是问题。
  - **这两处修完当时还不等于门禁绿**：`ci.yml` 那条 261/跳过 0 的断言排在失败的建桶步之后，至此**一次都没执行过**；勾清单里"CI 门禁真跑过一遍"要靠下一次 `workflow_dispatch`（对 `publish` 点即可，`ci.yml` 没有发布步，零不可逆），别拿本地等价命令顶账。**这一句到 2026-10-20 深夜已经兑现**，逐条见下面那条"门禁第一次在 runner 上全绿"。
- **SDK 入镜检查在 runner 上绿了，同一条链往后暴露出第三处红，且本地复现不出来（2026-10-20，对 `publish` 手动 dispatch `build-apk.yml`，run `39370419457`，head `685acb4`）**：「核对 runner 预装的 Android SDK」这一步 success（上一版红的 `setup-android` 确实被换掉了），失败挪到后面的 `./gradlew :app:assembleRelease`，配置阶段报 `UnknownPluginException: Plugin [id: 'com.google.devtools.ksp', version: '2.3.12', apply: false] was not found`，六个仓库全列为"已搜索"。**已排掉的方向都实测过**：marker 的 pom 在 `repo1.maven.org` / `repo.maven.apache.org` / `plugins.gradle.org/m2`（跟 303）/ aliyun `public` / aliyun `gradle-plugin` 上全是 200，只有 aliyun `google` 与 `dl.google.com` 是 404——**这正好是 Gradle 期望的搜索顺序，第一个命中就该是 aliyun `public`**；`symbol-processing-gradle-plugin-2.3.12.{pom,module,jar}` 也在；central 的 `maven-metadata.xml` 里 `2.3.12` 的 `lastUpdated` 是 20260909，不是当天刚发、镜像没跟上的新版本；报错只点名 ksp，说明 AGP 9.2.1 与 kotlin-compose 2.2.10 在同一台 runner 上解析成功了，即 runner 到这些仓库是通的。**所以"仓库真没有"和"网络不通"两条都不成立，缺的是 runner 当时的视角**。为此在 `build-apk.yml` 构建步之前加了一步只打印不判失败的探测（同一个 commit 现读 `ksp` 版本，用 runner 自己的网络把 marker 在六个仓库上的 HTTP 状态码打一遍）；脚本是从 YAML 里抽出来在 bash 跑过的，本机输出即上面那六个状态码。**根因就是这一步定下来的，不是猜的**：第二次 dispatch（head `171b076`，run `39373315480`）探测步自己打出 `502 / 502 / 502 / 404 / 200 / 200`——**GitHub runner 打 `maven.aliyun.com` 的三个仓库整片返 502**（境外数据中心 IP 被拒），而 `settings.gradle.kts` 把镜像写在 `pluginManagement` 与 `dependencyResolutionManagement` 的**最前面**，插件解析被这个 502 带进"哪个仓库都没有"的分支；中央仓库和插件门户其实是 200，本机在墙内看不见那个 502，所以本地一直绿。**修法（维护者定的口径）：默认只走官方源，镜像改由开发机自己开**——`android/local.properties`（已 gitignore）加 `pb.maven.cnMirror=true` 才把三个 aliyun 仓库插回最前面，开关逻辑在两个块里各读一次文件（`pluginManagement` 是单独先编译的一段，看不见脚本其余部分的声明，这是试出来的：第一版抽了个顶层 `fun`，`help` 直接报 `Unresolved reference`）。判据用冷 `GRADLE_USER_HOME` 现跑：关掉开关 `--info help` → **`aliyun` 命中 0 次、BUILD SUCCESSFUL（2m53s，KSP marker 从官方源解析通过）**；打开开关换一个全新冷缓存目录 → **`aliyun` 命中 456 次（google 219 / public 232 / gradle-plugin 5）、BUILD SUCCESSFUL（47s）**，两条分支都实测过，不是看一眼写的。探测步留在 `build-apk.yml` 里当这一次修复的保险，**跑绿之后就删掉**——它每次都会打三行已经没有意义的 502。同步把 `docs/开发指南.md` 新增"Gradle 依赖仓库"一节写清这个开关和别再写死镜像的理由。
- `scripts/release-verify.sh` 新增"部署变量必须有人消费"检查块：逐个断言 compose 透传的 `PB_*` 在 `application-prod.yml` 有对应占位符、监控变量不在主文件、工作流入库且 `ci.yml` 不参与版本号注入。**判据是零 FAIL**，WARN 只负责报数不判失败（具体条数会随本机环境变，不写进日志）
- **签名 secrets 第一次真被使用，同时暴露它的格式判据（2026-10-20，第三次 dispatch `build-apk.yml`，head `5f31903`，`run 39375007556`）**：仓库口径改掉之后，runner 的配置阶段一路走过 SDK 入镜检查与插件/依赖解析——**这就是"镜像 502 那条已经解决"的 runner 侧证据**：整个 job 只有第 10 步 `Build release APK` 红，1–9 全 success。红在 `android/app/build.gradle.kts` 的 keystore 解码：`Illegal base64 character 2d`。`2d` 是 `-`，也就是 **`ANDROID_KEYSTORE_BASE64` 里那一串不是标准 base64**——最像 Windows `certutil -encode` 的产物（带 `-----BEGIN/END-----` 包头和换行），也可能是 base64url 字母表。原来那句 `IllegalArgumentException` 说不出区别，而这一条红掉等于发不出包，所以改成先按标准字母表解，解不开就报**形状**（长度、有没有 PEM 包头、有没有 `-`/`_`、有没有换行、有没有 `+`/`/`）再失败；**只报形状不报内容**——那一串本身就是密钥材料，打进日志等于泄露。三条分支都在本机拿真 keystore 跑过（`--no-daemon :app:assembleRelease`，用仓库里 `local.properties` 的三个口令 + `base64 -w0 dev/pushbox-release.jks`）：正例 **BUILD SUCCESSFUL 1m17s，产出 12,740,698 字节的 release APK**，`apksigner verify --print-certs` 出的证书 SHA-256 与 发布检查清单 钉的基线 `99071932…dec4e` 逐字相同——**这是"CI 那条 env 驱动签名路径能用同一把 key"的第一次实测**；PEM 包头那条报"长度 3688，含 PEM 包头/包尾，含 base64url 的 - 或 _，含换行或空格"，base64url 那条报"长度 3640，含 - 或 _，不含 + 或 /"，两条都 rc=1；再把三份日志拿去比对 keystore 的 base64 前 80 字符，**三份都没命中**（判据是命令，不是"我看了一眼没打"）。所以剩下的事只有一件：把 GitHub secrets 里那一项换成 `base64 -w0 android/dev/pushbox-release.jks` 的一整行输出。探测仓库那一步已经完成使命（502 就是它打出来的），随本次一起删掉，留着就是每次三行没有意义的 502。
- **门禁第一次在 GitHub runner 上全绿（2026-10-20 深夜，head `60c86ad`，两次对 `publish` 的 `workflow_dispatch`）**：`ci.yml` `run 39377846888` 四个 job 全 success——服务端 job 里第 6 步「等 RustFS 就绪并建桶」（换成 runner 自带 AWS CLI v2 的那一步）与第 8 步「断言 DB 闸门用例真的执行了」（`skipped == 0` 且用例数 ≥ 261）**都是这条断言第一次在 runner 上真执行并通过**，另三个 job（管理台 `npm ci && npm run build` + 产物非空、Android `assembleDebug`、Windows `dotnet test`）全绿；`build-apk.yml` `run 39376573834` 同码全绿，`Build release APK` 与 `Verify release APK signature` 两步第一次真跑到，即**四个 keystore secrets 全被用过且解得开、签得上、验得过**（`Publish to GitHub Release` 与 `Calculate mapping.txt checksum` 是 skipped，因为它们 `if: startsWith(github.ref,'refs/tags/v')`，非 tag 事件本来就不该跑）。结论是用 `/actions/runs/{id}/jobs` 匿名逐步读出来的（steps 的 `conclusion` 可匿名取，日志体不行，`/logs` 实测 403），不是听汇报。**这条链当天红了四次、每次根因都在上一层**：`setup-android` 装已下架的 legacy `tools` → KSP marker"六个仓库都没有"（runner 打 aliyun 整片 502）→ base64 格式，四个都已定位并改掉；这一步之后 CI 侧没有已知的红灯了。**仍未被证明的两件**：`main` 上从没绿过（`main` 停在 `f6b9a53` 那次红的两条 job，要绿得先把 `publish` 推成 `main`，而那会连带 `docker-publish.yml` 真推 Hub——不可逆，等维护者点头）；`v*` 标签事件那条发布路径（`gh release`、版本闸门在 tag 上的取值、`1.1.0`/`latest` 标签）也没在 runner 上跑过。另外 `Verify release APK signature` 只断言"有签名且不含 `android debug`"，**没有把证书 SHA-256 与基线 `99071932…dec4e` 比对**，所以"CI 那份 APK 与本机那份是同一把 key"目前是靠本机 env 路径实测 + 非 debug 两条间接证据撑着，不是 CI 自己断言出来的。**这一句当天深夜已兑现成机器断言，见下面那条。**
- **签名指纹进了 CI 的判据，基线改成单一来源（2026-10-20 深夜，head `1cd4963` 之后）**：上面那条缺口（runner 那份 APK 只验了"有签名且非 debug"，没比对证书指纹）补上——新增 `android/release-cert.sha256`（内容就是基线那一串 64 位小写十六进制），`build-apk.yml` 的 `Verify release APK signature` 现读它，把 `apksigner verify --print-certs` 打出的**每一个 signer** 的 SHA-256 逐条比对，缺文件 / 不是 64 位小写十六进制 / `apksigner` 没打出任何 digest / 指纹不符一律红，红的时候打印基线与实际两份指纹（这俩都是公开信息，不是密钥材料）。多 signer 要逐条比而不是取第一条，因为 APK Signature Block 允许挂第二个签名而 `--print-certs` 会把它们全列出来——只取第一条会漏。取值锚定 `certificate SHA-256 digest:` 整串而不是宽抓 `SHA-256 digest`：实测 `apksigner verify --verbose --print-certs` 还会多打一行 `V2 Signer: public key SHA-256 digest: a23f8a7f…`，**那是公钥的指纹、和证书指纹是两个数**，宽正则会把 1 个 signer 抓成 2 条而判成不符（这台机器上 `Number of signers: 1`）。另外本机 `apksigner.bat` 的输出实测带 CRLF（`raw bytes tail` 打出来是 `\r\n`），runner 上是 LF，所以取值管道里加了 `tr -d '\r'`——少了这一步，同一份脚本在本机是绿的、在 runner 也绿，但谁拿它做本地复跑就会撞上"指纹不符"这种假红。**四种分支是从 YAML 里抽出这一步在 bash 真跑的**（拿本机那份 12,740,698 字节的 release APK）：正例 rc=0 并回显同一串、换成另一份 64 位十六进制 rc=1 且打出两份指纹、文件删掉 rc=1、文件写成大写且不足 64 位 rc=1。再给 `scripts/release-verify.sh` 加一条一致性闸：基线文件存在且格式合法 + `build-apk.yml` 真在读它 + `发布检查清单.md` 里那一串与文件逐字相同，三条负例（清单里的指纹被改、workflow 不再引用该文件、文件写成大写）各自把脚本打成 1 个 FAIL，复原后 47 PASS / 0 FAIL。**这条新断言已在 runner 上绿过**（2026-10-20 深夜第五次 dispatch，`run 39381072797`，head `5293801`，第 10 步 `Verify release APK signature` success；结论用 `/actions/runs/{id}/jobs` 匿名读 steps 的 `conclusion`，日志体仍 403）——**CI 那份 APK 的证书 SHA-256 与 `android/release-cert.sha256` 的基线逐字相符，从此是 CI 自己验过的事实**，上面那条"只验非 debug、同一把 key 靠间接证据"的缺口就此合上；换 key 从此是有意的破坏性动作（先写 CHANGELOG 再改那一串）。
- **`docker-publish.yml` 不再在构建现场改写版本号，改成构建前核对**：旧写法在 `v*` 事件上跑 `set-version.sh "${GITHUB_REF_NAME#v}"`，于是"tag 说 1.2.3、pom 写 1.1.0"也能构建成功，推上去的是一个自称 1.2.3、里面跑着 1.1.0 的镜像——而 Docker Hub 推出去收不回来，被人拉走的那份 manifest 永远错着。现在新增"版本号一致性闸门"，在 buildx 与 login **之前**现读 `pom.xml` 与 `deploy/docker-compose.yml` 比对，不一致直接红并写出该补的命令。取值把 `<version>` 尖括号一起锚定：本机试过 pom 写成 `1.1.0-SNAPSHOT`，宽正则会把 `-SNAPSHOT` 读成 `1.1.0`，闸门就成了假绿（本仓全仓零 SNAPSHOT，所以判死）。闸门读出的那个值同时盖进镜像的 `org.opencontainers.image.version`——此前谁查这一版都会被骗：WSL 实测本机编出来的那份继承的是基底的标签 `{"org.opencontainers.image.title":"ubuntu","org.opencontainers.image.version":"26.04"}`，查出来的是 Ubuntu 的版本号。手动触发新增 `dry_run` 输入（默认勾上）：只构建并 load 进 runner，用来在不往 Hub 留任何东西的前提下验 `DOCKERHUB_*` 这对 secrets 与 metadata 算出的标签清单；`push=false` 必须配 `load=true`，否则 buildx 因"没有导出目标"直接失败
- **这条链第一次在 GitHub runner 上真跑过（2026-10-20，对 `publish` 分支点 dry-run dispatch）**：9 步全 success，含凭据校验、版本闸门、metadata 与多阶段构建，其中 `Login to Docker Hub` 是真登录——`DOCKERHUB_*` 这对 secrets 因此从"配好了"升级为"验过能用"。而跑完后 `hub.docker.com/v2/repositories/iamplusdev/push-box/tags` 仍是 **404**（对照组 `library/mysql` 返 200，排除网络假象），这才是"确实一个字节都没推上去"的证据。两个证据缺一个都不算验证过
- `scripts/release-verify.sh` 里"三个出制品 workflow 都该有版本注入"这条改成按 workflow 分别断言（apk/msi 注入、docker-publish 只核对），并新增反向判据：`docker-publish.yml` 里出现真正的注入命令就 FAIL。**判"有没有注入"必须锚定行首的命令**，不能整文件 `grep 'set-version.sh'`——第一版就是这么写的，结果闸门自己的报错文案里那句"先跑 set-version.sh 再重打 tag"被读成了"我在现场改写版本号"，一条正确的改动被判成 FAIL

### 文档

- **管理台 API 第一次有参考页：`site/admin-api.html`**（2026-10-20 新增）。此前官网三页只覆盖入站 `/message`、客户端 `/api/v1/**` 与协议，管理台那 16 个端点（登录 1 + 频道 5 + 设备 2 + 消息搜索 1 + 概览 1 + 定时任务 6）在 `docs/软件设计方案.md` 里只有决策描述，给用户一条 curl 都凑不出来。这一页的字段表、错误码与示例全部从代码读出来写：`AdminAuthController`、`AdminController`、`DeviceGuard`、`ChannelService`、`DeviceService`、`ScheduledTaskService`、`PbStore`。其中几条是任何既有文档都没说过的：管理台线是 **camelCase 请求 / snake_case 响应**（Jackson 没配命名策略，请求体字段名拼错会被静默丢掉而不是报错）；`hint` 永远是字符串、不会是 `null`；定时任务的 `channel_name` 与 `content_head`（80 字符）**只在列表里给**，`content`+`images` **只在详情里给**；`last_fire_at` 用 `0` 而不是 `null` 表示从没跑过；`enabled` 只有 `1` 算真；cron 是 6 段 Spring 方言；`msg_key = sched:<id>:<分钟桶>` 意味着一条任务每分钟最多产出一条消息，与 sweep 跑多快无关
- **顺手修掉的 6 处文档错误 + 自查中新发现的 2 处**：管理台消息搜索被写成 `/api/v1/admin/messages` + `/search` 那一段（真实只有 `/messages?q=`，照抄 404），出现在 `deploy/WSL测试速查.md`；`docs/软件设计方案.md` 三处把 REST 补拉的游标写成 `since_seq`（那是 WS 连接 URL 上的参数，REST 叫 `after_seq`）、两处媒体/头像路径写的是不存在的形状（真实是 `/api/v1/media/{msg_id}/{idx}` 与 `/api/v1/avatars/{sha256(sender_id)}`）；`site/usage.html` 把入站写入写成挂在 `/api/v1` 下的那条（真实是 `POST /message`，前缀由 `pb.ingest.base-path` 配置），`CHANGELOG.md` v1.0.0 那条同源错误一起改；`site/api.html` 的 `/healthz` 口径整个是旧的（补上：白名单由 `PB_SECURITY_INTERNAL_CIDRS` 控制且默认只放环回、真做 DB `isValid(2s)` 与本地存储写探针、down 时 503，所以它是**就绪探针不是存活探针**）；`docs/审计报告.md` §2 把 `GET /api/v1/admin/messages` 归到了服务设备侧的 `MessageQueryController` 上，且它"所有管理台端点完全一致"的结论是拿 6 个抽查项外推的（当时定时任务那一组还没进视野），连同会静默腐烂的文件行号引用一起改成"方法 + 完整路径"记账
- **新增一条闸：文档里写到的端点路径必须存在于代码**（`scripts/release-verify.sh`）。代码侧从 `web/*.java` 的 `@RequestMapping`/`@Get…Mapping` 提路由（要处理三种坑：`path = "${pb.ingest.base-path:/message}"` 取默认值、光有注解不带路径的映射到类前缀、以及类前缀必须拼在方法路径前面——第一版没拼，判据把文档里每一条路径都报成不存在）；文档侧扫 `site/*.html` + README + CHANGELOG + 发布检查清单 + `docs/*.md` + `deploy/*.md`。同位段先归一化（`{channelNo}`、纯数字、ULID 都归成 `{}`），否则真端点会被误报。**2026-10-20 实跑：代码侧 34 条、文档侧 36 条、全部命中，全脚本 51 PASS / 0 FAIL / 2 WARN**（文档侧那个数不当基线：傍晚补文档时它自己涨到 37，闸看的始终是"每一条能不能匹配上代码里的路由"）。反向验证往判据里喂一条从未存在的路径，确认可抓出。**这一轮第一次实跑就是红的，红在我自己新写的 发布检查清单 两条勾选项上**：那两句把"这个路径从来不存在"的错误写法整条抄了出来，而闸是纯字符串匹配，分不出"照着用"和"提醒别这么写"——改的是文档措辞（错误写法不再整条照抄），不是给闸加白名单开口子。**闸只看路径**：请求体字段名、鉴权头、响应形状都不在它的能力范围内，那些仍然只能靠人对着代码读。**当天傍晚这条闸第二次红，第二次也红在我自己的句子上**：管理台那一页新写"末段拼错会 404"时，为了举例子把一个从未存在的路径整条照抄进正文——纯字符串匹配的闸分不出"照着用"和"提醒别这么写"，于是判它不存在。改的仍然是措辞（例子里不出现完整路径），**这条闸到今天为止两次红、两次都没需要放宽判据**
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
- **WSL 部署套件**：新增 `deploy/docker-compose-wsl.yml`、`wsl-test.env`、`deploy-wsl.sh`/`deploy-wsl.ps1` 一键脚本，配套快速指南 [WSL测试速查.md](deploy/WSL测试速查.md) 与 [WSL部署指南.md](deploy/WSL部署指南.md)
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
