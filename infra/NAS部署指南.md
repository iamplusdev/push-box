# NAS 部署指南（飞牛 fnOS / 群晖 / 1Panel 这类图形界面）

> 目标：**复制一份 YAML、up 起来、在浏览器里填一张表**，服务就起来了。
> 这一版镜像里**一个服务端环境变量都没有**：没有 `ENV`、没有必填变量、没有 `__SET_ME__` 占位值
> （见 [infra/Dockerfile](Dockerfile)）。库地址、库口令、要不要对象存储、加密口令、管理台口令，
> 全都在**首次启动弹出的安装页**里填；提交后落盘成容器里的 `/app/data/config/pushbox.json`，
> **同一个进程接着启动 Spring，不用重启容器**。
> 这条路径与 `infra/docker-compose.yml` 的区别写在 [软件设计方案](../docs/软件设计方案.md) D76：
> 那一本面向"有 `.env`、有 Caddy 反代、外接对象存储"的生产拓扑；这一本面向"一台 NAS、一个库、
> 图片落在卷里"的自用拓扑。只想起一个容器、不建 Compose 项目的 `docker run` 写法见
> [README「Docker 部署」形态一](../README.md)；面向使用者的说明在
> [官网](https://iamplusdev.github.io/push-box/install.html)。

## 0. 先确认三件事

- **镜像标签没有 `v`**。Hub 上打的是 `X.Y.Z` / `X.Y` / `latest`（发布工作流从 `pom.xml` 现读版本号）。
  写 `push-box:v1.1.5` 或 `push-box:server-v1.1.5` 都会报 manifest unknown。
- **PushBox 不自带数据库**，且必须是 **MySQL 8.0 或 8.4**。第一版迁移用了 `utf8mb4_0900_ai_ci`
  与 `FULLTEXT ... WITH PARSER ngram`（`apps/server/src/main/resources/db/migration/V1__init.sql`），
  这两样 5.7 都没有；中文全文搜索还要求实例带 `ngram_token_size=2`。8.0/8.4 两个实例上都实测过迁移可过。
- **Hub 上的镜像只有 `linux/amd64` 一种架构**：发布工作流那一步没有传 `platforms`
  （`.github/workflows/docker-publish.yml` 的 `Build and push`），构建机是 x86_64 就只出 x86_64。
  飞牛 2026 年 2 月起有 ARM 公测版，ARM 机型拉 Hub 镜像会得到 `no matching manifest for
  linux/arm64/v8`——那种机器请自己在那台机器上 `docker build -f infra/Dockerfile .`
  （这一本 Dockerfile 是多阶段构建，ARM 上现场跑 npm + Maven，比 amd64 慢不少）。
  这一条是从工作流文件读出来的，**没有在 ARM 机器上实测过**。

## 1. 准备数据库（一次性）

飞牛「应用中心」装一个 MySQL 8（或自己起一个 `mysql:8.4` 容器）。**账号要你先建好**——
安装页会替你建库、首次启动会由 Flyway 建表，但**不会替你建用户**。在库所在那台机器上执行：

```sql
CREATE USER IF NOT EXISTS 'pushbox'@'%' IDENTIFIED BY '换成你自己的强口令';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES
  ON pushbox.* TO 'pushbox'@'%';
FLUSH PRIVILEGES;

SELECT @@ngram_token_size;   -- 期望 2；不是 2 就搜不出中文长句
```

库本身**可以不手工建**：安装页连上库服务器之后会执行 `CREATE DATABASE IF NOT EXISTS pushbox`
（`InstallServer.checkDatabase`），表由 Flyway 首次启动迁移（慢几十秒是正常的）。
当然你先 `CREATE DATABASE pushbox CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;` 也行。

> `'pushbox'@'%'` 这个主机写法是必须的：PushBox 跑在另一个容器里，MySQL 看到的连接来源不是
> `localhost`。写成 `'pushbox'@'localhost'` 的话，口令全对也照样是 `1045 Access denied`。
> 用第 3 节那段自带的 `mysql:8.4` 时，`MYSQL_USER`/`MYSQL_PASSWORD` 会把账号建好，这一步可省。

## 2. 路径 A：已有 MySQL，起一个容器（默认走法）

「Docker」→ 左侧「Compose」→「新增项目」→ 填项目名、选 YAML 保存位置，然后把
[infra/docker-compose.nas.yml](docker-compose.nas.yml) 整本粘进去，「部署 / up」。

**这一版不用改任何一行**：那本文件里零 `${VAR}` 插值、零环境变量，镜像也不带配置。
up 之后浏览器打开 `http://<NAS_IP>:7404/`，看到的就是安装页。

> 为什么整本零插值：图形界面里没有文件可编辑，解析阶段的 `required variable ... is missing a value`
> 既读不懂也改不动——那正是 2026-10-22 有人在飞牛上撞到的那条。现在连必填项都挪进安装页了，
> 这一条只是留给下面第 3 节那段可选的 `mysql`。

### 安装页上填什么

界面分三节，带 `*` 的必填，其余留空即用默认值（默认值取自 `RuntimeConfig`）：

| 节 | 项（界面上的字） | 说明 |
|---|---|---|
| 1 · 数据库 | 地址 `*` / 端口 `*` / 库名 `*` / 用户名 `*` / 密码 `*` | 五段分开填，JDBC 串由服务端拼。地址默认 `127.0.0.1`、端口 `3306`、库名与用户名默认 `pushbox`。**库和容器在同一台机器上也别填 `127.0.0.1`**——容器里的环回是它自己，要填库所在机器的局域网 IP。旁边有「测试连接」按钮先单独判库 |
| 2 · 图片与头像存哪里 | 本地存储目录 | 默认 `data/media`（容器里是 `/app/data/media`）。不勾对象存储时图片就落这里 |
| 2 | **改用对象存储**（开关） | 关 = 本地目录，零依赖；开 = 下面那几项必填 |
| 2 | 服务地址 `*` / 桶名 `*` / 区域 / Access Key `*` / Secret Key `*` / Path-Style（开关） | 区域默认 `us-east-1`、桶名默认 `pushbox`。Path-Style 默认勾上，MinIO / RustFS 是对的；接阿里云 OSS 或 AWS 时按服务商文档可能要去掉 |
| 2 | **加密存储的图片与头像**（开关）+ 加密口令 / 主钥 | 关 = 明文；开 = 每对象客户钥（SSE-C）。口令至少 12 个字符，由 Argon2id 派生出 KEK；主钥是 base64 的 32 字节（`openssl rand -base64 32`），与口令**二选一** |
| 2 | 图片抓取放行主机（可选） | 管的是图片**从哪抓**：抓取器默认拒绝一切回环 / 私网 / CGNAT 地址（防 SSRF），所以内网图床、内网监控截图服务这类必须在这里点名。写 `host` 或 `host:port`，逗号分隔，**精确等值**匹配，不带 `http://`、不带路径、没有通配。漏填的表现是每条图片都静默失败（`media_objects.error=ssrf_blocked`），客户端只看到图不见了 |
| 3 · 管理台与服务 | 管理台口令 | **留空 = 管理台整个不开放**（所有 `/api/v1/admin/*` 一律 403「管理台未启用」），客户端收发不受影响，这是安全状态不是漏配。要开就填一个至少 8 个字符的口令。JWT 签名钥不用你填，服务端首次启动自动生成并回写配置文件 |
| 3 | 服务端口 | 默认 7404。容器内改端口要同步改 compose 的 `ports` 与 healthcheck，所以**通常别改**——要换端口就换宿主侧映射 |
| 3 | 运维端点放行网段（可选） | `/healthz` 与 `/actuator/**` 的额外放行网段，环回永远放行。留空 = 只有容器自己能读。写成 CIDR，逗号或换行分隔 |

填完点「保存并启动」。提交时 `install()` 会**先全量校验、再真连一次库、勾了对象存储还会真连一次桶**
（putObject + deleteObject），任何一项不过就**一个字节都不写盘**，并把具体原因回在页面上。
所以"配了一半"这种状态在新模型下不存在：要么这份配置能跑，要么它根本没落盘。
写盘成功后同一个 JVM 接着起 Spring，根路径 `/` 稍后就从安装页变成管理台页面。

### 图片想落进 NAS 上已有的桶

在「2 · 图片与头像存哪里」里勾上**改用对象存储**，把服务地址（例如 `http://192.0.2.30:7101`）、
桶名、Access Key、Secret Key 填全。**桶要你先建好，程序不建桶**：连通性检查会真写一个对象再删掉，
桶不存在就直接报回来。

要不要加密由**加密存储**那个开关表态（不再是一道拒绝启动的闸门）：

- 勾上加密，KEK 来源二选一——**加密口令**（给人记，至少 12 个字符，Argon2id 派生）或
  **主钥**（`openssl rand -base64 32`，直接给 32 字节 KEK）。两个都填安装页会拒绝
  （`加密口令与主钥只能填一个：两者派生出的密钥不同，同时填无法判断该用哪一把`）。
- **KEK 一旦有对象落库就不可轮换**——换了口令或主钥，之前写进去的图片再也解不开，且不可恢复。
  这一条在表单上没法拦，只能写在这里。口令本身不落库，`app_state.media_kek_v1` 只存一份随机盐、
  KDF 参数和校验值，所以重装/重建容器后只要库还在、口令还是原来那个，旧图片照常能读；口令忘了也一样救不回来。
- 不勾加密就是桶上明文：任何拿到同一组 AK/SK 的读者都能直接把图片与头像读走。勾不勾由你表态。

### 路径 C：不建 Compose 项目，直接在「创建容器」表单里填

飞牛/群晖的「创建容器」会把镜像拉下来。**这一版表单里几乎没有要填的服务端项**：

- **「环境变量」那一页没有任何服务端变量可填**。镜像现在零 `ENV`，所以那一页是空的。
  （旧版这里会逐行显示五个 `__SET_ME__` 占位值——`PB_DB_PASSWORD`、`PB_S3_*` 那些；
  这是 2026-10 配置模型重构后变的，重构把必填项全挪进了安装页，入口脚本也不再读任何配置。）
- **「存储位置」只有一行**：镜像只声明了一个 `VOLUME ["/app/data"]`，那一个卷装下
  `config/`（安装页写的 `pushbox.json`）+ `media/`（图片与头像）+ `logs/`。较新的表单会把
  **容器内路径** `/app/data` 直接带出来，**宿主文件夹那一列仍然是空的**——Docker 不会替人猜
  NAS 上的路径，得你点选或新建。表单里一行都没有（镜像是旧的）就手动点「添加」补这一行。
- **端口映射**填 `7404:7404`（容器内固定 7404）。

指向共享文件夹时，先在 NAS 上把目录建好，再用 SSH 执行一次（容器里的进程是 uid 10001）：

```bash
sudo chown -R 10001:10001 /vol1/1000/pushbox
```

不 chown 就指向共享文件夹，容器会在启动时退出。entrypoint 现在只剩这一件事——`/app/data` 的写入探针，
写不进就当场退出并给出两种修法：

```
❌ 错误: 数据目录 /app/data 不可写（容器内进程是 uid 10001）
   配置、图片与日志都在这个目录下，写不动容器起不来。
   在 NAS 上把它交给运行用户即可：
     sudo chown -R 10001:10001 <你挂给 /app/data 的那个宿主目录>
   或者在「存储位置」里把 /app/data 那一行删掉，让 Docker 建匿名卷（属主继承镜像里的 pushbox）；
   代价是 docker rm 之后配置与图片一起没。
```

把「存储位置」那一行留空（或删掉）也能正常起，Docker 会建一个继承镜像属主的匿名卷；
代价是 `docker rm` 之后配置、图片、日志一起没——包括安装页填的那些值。所以**生产上务必挂卷**。

起容器之后，剩下的全在浏览器那张安装页上（`http://<NAS_IP>:7404/`），填法与路径 A 完全相同。

## 3. 路径 B：NAS 上还没有库，顺带起一个 MySQL

同一本 `docker-compose.nas.yml` 里把 `mysql:` 那段注释取消，并做两件事：

1. 把那段里 `MYSQL_PASSWORD` 填成你的库口令（`MYSQL_DATABASE=pushbox` + `MYSQL_USER`/`MYSQL_PASSWORD`
   会把库和账号建好，表仍由 Flyway 迁移）；
2. 文件末尾 `volumes:` 里把 `db_data: {}` 的注释去掉。

然后 up，打开安装页：「地址」填服务名 `mysql`（不是 IP），「密码」填**与 `MYSQL_PASSWORD` 同一个值**
（compose 没法在一个文件里引用自己写过的值，两处同值是这套写法的代价）。那段 `mysql` 已经带了
`command: --ngram_token_size=2`，中文全文搜索的前提满足。

**3306 不发布到宿主**是刻意的——两个容器在同一张默认网络上，按服务名就够，
把一个能读写 pushbox 库的端口摆进局域网没有收益。

> 空库首次初始化在 NAS 上普遍要 1～2 分钟。这期间安装页的「测试连接」会报连不上——
> 用 `docker logs <mysql 容器名> --tail 20` 看它到哪一步了（`Initing db` / `Preparing system tables`），
> 起好再回来点一次「测试连接」，通了再「保存并启动」。
> （旧版这里靠 entrypoint 的 TCP 预检与 `PB_DB_WAIT_SECONDS` 等库；那个入口脚本已经删了，
> 现在改用安装页的「测试连接」手动判，连不上就一个字节都不写。）

## 4. 验收

- **看容器日志**。没配置完时，日志里会先出现安装页那段提示：
  ```
  ┌─ PushBox 还没有配置 ────────────────────────────────────────
  │  请在浏览器里打开： http://localhost:7404/
  │  （从别的机器访问就把 localhost 换成这台机器的 IP）
  │  填完提交，服务端会自动接着启动，不需要重启容器。
  │  配置文件将写到： /app/data/config/pushbox.json
  └────────────────────────────────────────────────────────────
  ```
  在浏览器里填完、点「保存并启动」之后，同一个进程接着起 Spring；安装页会轮询到服务端就绪后
  **自己跳到管理台**，不用手动刷新（端口框改了则提示去新端口，不会往旧地址跳）。日志里出现
  `Started PushboxServerApplication` 就算就绪（首次启动还要跑 Flyway 建表，多等一会儿）。
- 浏览器打开 `http://<NAS_IP>:7404/`：装完之后根路径就是管理台页面，登录口令 = 安装页里填的那个。
  管理台口令留空时，登录一律 403「管理台未启用」，这是安全状态不是坏了，客户端收发照旧。
- Android / PC 客户端注册时服务端地址填 `http://<NAS_IP>:7404`。同一路由器内可达；
  要跨公网必须自己上 HTTPS 反代——明文链路上跑的是消息正文与管理台口令。

**`/healthz` 不给你从浏览器验收**：运维端点默认只放行容器环回（安装页「运维端点放行网段」留空），
从宿主打过去源地址是 docker 网桥 → **404**。这不是坏了，是刻意的：`InternalAccessFilter` 对外一律
回 404 而不是 403，扫描器不该从状态码里确认"这里确实有个运维端点"。要在容器里自己看一眼
（镜像里有 curl）：

```bash
docker exec <容器名> curl -s http://127.0.0.1:7404/healthz
```

返回体是 `{"ok":true,"db":"up","storage":"up","uptime_ms":…}`。要让外部监控抓到，把这套 compose
网络实际分到的子网填进安装页的「运维端点放行网段」（值落在配置文件的 `security.internalCidrs`）。
查子网（`<项目名>` 就是你在界面上填的那个，本文件默认 `pushbox-nas`）：

```bash
docker network inspect <项目名>_default -f '{{range .IPAM.Config}}{{.Subnet}}{{end}}'
```

### 装完之后要改配置

两条路：

- 直接编辑那份 JSON 再重启容器。配置文件在容器里是 `/app/data/config/pushbox.json`
  （宿主上就是那个卷里的同名文件），字段名与默认值见 [infra/pushbox.json.example](pushbox.json.example)。
- 或者把那个文件删掉，下次启动就回到安装页，用「从数据库找回上次配置」把上次那份捞回来改
  （配置副本存在 `app_state.runtime_config_v1`，填对库凭据就能拉回整份 JSON）。

**加密口令与主钥不能这么换**：KEK 一旦有对象落库就不可轮换，改了旧图片再也解不开。

## 5. 起不来时按这句找

| 看到的那句 | 真正的原因 |
|---|---|
| `❌ 错误: 数据目录 /app/data 不可写（容器内进程是 uid 10001）` | 你挂的是 NAS 共享文件夹（绑定挂载带来 root 属主）。改用 named volume，或 `sudo chown -R 10001:10001 <你挂给 /app/data 的那个宿主目录>`。这是 entrypoint 唯一的判据 |
| `数据库地址不能为空。库和容器在同一台机器上也别填 127.0.0.1……` | 安装页「地址」留空，或填了环回。填库所在机器的局域网 IP |
| `连不上数据库：……`（127.0.0.1 时还会跟一句`提示：容器里的 127.0.0.1 指容器自己……`） | 地址/端口不对、库那层没发布端口、库容器自己在崩溃循环、或防火墙拦了。口令错**不会**报这句，那一层报的是 1045 |
| `能连上数据库服务器，但打不开库 pushbox：……（多半是这个用户没有该库的权限）` | 账号没有那个库的授权，或用户主机写法不是 `'pushbox'@'%'` |
| `1045 Access denied`（在连库报错里） | 口令不对，或用户主机写法不是 `'pushbox'@'%'`。**库名对不上也报这一句**，MySQL 8 在这种配法下不会给你 `Unknown database` |
| `启用了对象存储，就要填它的地址……` / `……就要填桶名` / `……Access Key 与 Secret Key 都要填` | 勾了「改用对象存储」但没填全。桶还要你**先建好**，程序不建桶 |
| `对象存储连上了但拒绝了这次操作（…）` / `连不上对象存储：…` | 桶不存在、AK/SK 错、端口错。连通性检查是真 putObject + deleteObject |
| `加密口令与主钥只能填一个：……` | 勾了加密、两个都填了。删掉其中一个 |
| `加密口令至少 12 个字符（……）` | 口令太短 |
| `主钥格式不对：它应当是 base64 的 32 字节（openssl rand -base64 32）` | 主钥不是那条命令的产物 |
| `开启了存储加密，就要填一个加密口令（推荐）或一把主钥` | 勾了加密但口令与主钥都空着 |
| `管理台口令至少 8 个字符；不想开管理台就把它留空` | 口令填了 1～7 个字符。要么补足 8 位，要么整个留空（留空 = 管理台关闭） |
| `运维网段要写成 CIDR（例如 172.16.0.0/12），逗号或换行分隔` | 「运维端点放行网段」里有不是 CIDR 的项 |
| `图片抓取放行主机要写成 host 或 host:port（例如 img.lan 或 10.0.0.20:8080），不带 http:// 也不带路径` | 「图片抓取放行主机」里写了 scheme、路径或通配。只留裸主机名，端口用 `:8080` 这种写法 |
| 服务起来了，图片消息一直转圈/`failed` | 先看 `media_objects.error`：`ssrf_blocked` = 图床在内网而没在「图片抓取放行主机」里点名（抓取器默认拒绝私网），`timeout` = 容器出不去那个地址；`storage=down` 则是本地存储不可写——`/healthz` 会真的往目录里写一个临时文件，先看容器日志有没有 entrypoint 那句"数据目录不可写"，再看 `docker volume ls` 里 `<项目名>_pushbox_data` 在不在 |

## 6. 备份与迁移

- 数据 = **MySQL 里的 `pushbox` 库** + **`/app/data` 那个卷**（`config/pushbox.json` + `media/` + `logs/`），
  缺任何一边都不算备份成功。查卷落在哪：
  ```bash
  docker volume inspect pushbox-nas_pushbox_data -f '{{.Mountpoint}}'
  ```
- **`pushbox.json` 里明文存着凭据**：库口令、桶 AK/SK、管理台口令，勾了加密还有加密口令或主钥。
  这是刻意的（`RuntimeConfig` 注释里写了理由：生产部署一定要有可复核的凭据落点，与其把真值散在
  compose 的 environment 或 NAS 表单里，不如收敛成一个文件，容器内权限 `rw-------`）。
  所以**那份文件、或整个卷，要跟着数据库一起备份**；加密口令丢了等于图片全丢且不可恢复。
- 只换镜像版本、不动卷 → 数据照用。删容器不删卷 → 数据还在；`docker compose down -v` 会把卷一起删掉。
- 生产那套 `infra/backup/backup.sh`（mysqldump + age 加密）入参里那几个 `PB_DB_*`/`PB_S3_*`
  是**脚本自己的入参**，不是服务端读的环境变量（服务端一个环境变量都不读）；值可以从
  `/app/data/config/pushbox.json` 里抄。本地存储模式下把媒体目录指向上面那个 Mountpoint 即可。

## 7. 这一节的验证边界

**新模型的事实来源是代码**，不是实机截图：`infra/Dockerfile`（零服务端 ENV、一个
`VOLUME ["/app/data"]`、entrypoint 只剩写入探针）、`InstallServer`（安装页的校验规则与连通性检查）、
`RuntimeConfig`（配置文件的字段名与默认值）、`docker-compose.nas.yml`（零插值、纯 TCP healthcheck）。
本文里每一条具体命令、路径、字段名、默认值、错误话术都出自这几处。

**没有在真容器里跑过新模型的全流程**：旧版那段"只喂一个 `-e PB_DB_PASSWORD`、走镜像烘焙默认值
`mysql`、entrypoint 判据 25 条场景矩阵"的实测，是针对**已经删掉的环境变量入口脚本**做的，
新模型下不再适用——镜像现在零 ENV，配置全在安装页，entrypoint 也不再读任何配置。

**关于 Hub config blob 的旧结论已被推翻**：旧版 v1.1.5 实查到镜像元数据里
`VOLUME` 是 `{"/app/data/media":{},"/app/logs":{}}`、ENV 里有五枚 `__SET_ME__` 与两个默认值。
**这一条在 2026-10 的配置模型重构后不再成立**：当前 Dockerfile 只声明一个 `VOLUME ["/app/data"]`、
零服务端 ENV，所以「创建容器」表单的「环境变量」页没有任何占位值可填，「存储位置」也只有一行。

**仍然没有实测过的**：飞牛/群晖/1Panel 的图形界面本身——按钮的确切位置、"Compose 项目"表单怎么吃
这本 YAML、镜像拉取要不要先在 Docker 设置里配国内加速源、ARM 机型（见第 0 节架构那条）。
第 5 节里"绑定挂载到 NAS 共享文件夹 → uid 10001 写不动"那条，判据代码（entrypoint 写入探针）在镜像里，
给一个 root 属主的空目录能稳定复现那句报错，但**没有在飞牛的共享文件夹上复现过**。
`Started PushboxServerApplication` 的耗时因机器而异，NAS 机械盘上会更慢，别拿某个数当基准。
