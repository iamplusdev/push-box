# NAS 部署指南（飞牛 fnOS / 群晖 / 1Panel 这类图形界面）

> 目标：**复制一份 YAML、改两个值，服务就起来**。
> 这条路径与 `deploy/docker-compose.yml` 的区别写在 [软件设计方案](../docs/软件设计方案.md) D76：
> 那一本面向"有 `.env`、有 Caddy 反代、外接对象存储"的生产拓扑；这一本面向"一台 NAS、一个库、
> 图片落在卷里"的自用拓扑。判据（哪些必须填、缺了会怎样）由镜像里的 entrypoint 统一负责。
> 不想用 compose 项目、只想起一个容器的话，[部署快速参考](部署快速参考.md)「🚀 主路径」是同一条口径的
> `docker run` 写法；面向使用者的说明在 [官网](https://iamplusdev.github.io/push-box/install.html)。

## 0. 先确认三件事

- **镜像标签没有 `v`**。Hub 上打的是去掉 `v` 的 `X.Y.Z` / `X.Y` / `latest`（CI 的 `type=semver` 就这么算的）。
  写 `push-box:v1.1.1` 会报 manifest unknown。
- **PushBox 不自带数据库**，且必须是 **MySQL 8.0 或 8.4**。第一版迁移用了 `utf8mb4_0900_ai_ci`
  与 `FULLTEXT ... WITH PARSER ngram`（`server/src/main/resources/db/migration/V1__init.sql`），
  这两样 5.7 都没有。8.0/8.4 两个实例上都实测过迁移可过。
- **Hub 上的镜像只有 `linux/amd64` 一种架构**：发布工作流那一步没有传 `platforms`
  （`.github/workflows/docker-publish.yml` 的 `Build and push`），构建机是 x86_64 就只出 x86_64。
  飞牛 2026 年 2 月起有 ARM 公测版，ARM 机型拉 Hub 镜像会得到 `no matching manifest for
  linux/arm64/v8`——那种机器请自己在那台机器上 `docker build -f deploy/Dockerfile .`
  （这一本 Dockerfile 是多阶段构建，ARM 上现场跑 npm + Maven，比 amd64 慢不少）。
  这一条是从工作流文件读出来的，**没有在 ARM 机器上实测过**。

## 1. 准备数据库（一次性）

飞牛「应用中心」装一个 MySQL 8（或自己起一个 `mysql:8.4` 容器），然后在库里执行：

```sql
CREATE DATABASE pushbox CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'pushbox'@'%' IDENTIFIED BY '换成你自己的强口令';
GRANT ALL PRIVILEGES ON pushbox.* TO 'pushbox'@'%';
FLUSH PRIVILEGES;
```

表**不用建**：服务端启动时由 Flyway 自动迁移（首次启动慢几十秒是正常的）。

> `'pushbox'@'%'` 这个主机写法是必须的：PushBox 跑在另一个容器里，MySQL 看到的连接来源不是
> `localhost`。写成 `'pushbox'@'localhost'` 的话，口令全对也照样是 `1045 Access denied`。

## 2. 路径 A：已有 MySQL，只起一个容器（默认走法）

「Docker」→ 左侧「Compose」→「新增项目」→ 填项目名、选 YAML 保存位置，然后把
[deploy/docker-compose.nas.yml](docker-compose.nas.yml) 整本粘进去，**只改两行**：

```yaml
      PB_DB_HOST: 192.0.2.20      # 库在哪：NAS 的局域网 IP
      PB_DB_PASSWORD: 换成你自己的强口令
```

然后「部署 / up」。这两个值就是全部——其余项镜像自己带默认值：

| 变量 | 不填会怎样 |
|---|---|
| `PB_SERVER_PORT` | 7404。改了要同步改 `ports` 那一行 |
| `PB_STORAGE_TYPE` | `local`：图片与头像落进 `server_media` 卷，不需要任何对象存储 |
| `PB_S3_OBJECT_ENCRYPT` | `none`：本地盘明文（逐文件 600 权限）。要连本地盘也加密，见 [docker-compose.nas.yml](docker-compose.nas.yml) 里那两行注释；想用桶见下面「图片想落进 NAS 上已有的桶」 |
| `PB_ADMIN_SECRET` / `PB_ADMIN_JWT_SECRET` | 都留空 = **Web 管理台不开放**。客户端收发与管理频道不受影响，Android/PC 里都能建频道 |
| `PB_DB_URL` | 由 `PB_DB_HOST`/`PB_DB_PORT`/`PB_DB_NAME` 拼出完整 JDBC 串；自己填了 URL 就以 URL 为准 |

### 图片想落进 NAS 上已有的桶

那一本文件里 `PB_STORAGE_TYPE` 写的是字面值 `local`，所以**这一行必须改成 `s3`**，只加 `PB_S3_*` 是不够的：
不给这个开关，那几项一个字都不读，图片照旧落 NAS 的卷里（启动日志会点名"配了但读不到"）。

```yaml
      PB_STORAGE_TYPE: s3
      PB_S3_ENDPOINT: http://192.0.2.30:7101   # 桶在哪台机器的哪个端口
      PB_S3_ACCESS_KEY: 换成你自己的 AK
      PB_S3_SECRET_KEY: 换成你自己的 SK
      PB_S3_BUCKET: pushbox                    # 桶名不是 pushbox 就改这里；区域不是 us-east-1 再加 PB_S3_REGION
      PB_S3_OBJECT_ENCRYPT: sse-c              # 桶上落明文是拒启的，见下面那句
      PB_S3_MASTER_KEY: 一段 base64 的 32 字节（openssl rand -base64 32）
```

三条要知道的：

- **`sse-c` 那两行不是可选的装饰**。`PB_S3_OBJECT_ENCRYPT` 在这一本文件里默认 `none`，而桶上明文意味着
  任何拿到同一组 AK/SK 的读者都能直接把图片与头像读走，所以 `s3 + none` 直接拒绝启动。想先把服务跑起来
  就显式加一行 `PB_ALLOW_PLAINTEXT_MEDIA: "1"` 表态；但**主钥一旦有对象落库就不可轮换**——换了它，旧图片
  再也解不开，所以 `PB_S3_MASTER_KEY` 生成后当场备份到 Git 之外。
- **`server_media` 那个卷不用删**。落桶之后它只是空着；留着它，以后想回 `local` 时路径还是通的。日志卷
  `server_logs` 任何时候都要留。
- **`PB_S3_PATH_STYLE` 镜像里烘焙的是 `true`**，MinIO / RustFS 是对的；接阿里云 OSS 或 AWS 时按服务商文档
  可能要加一行 `PB_S3_PATH_STYLE: "false"`。桶本身要你先建好，程序不建桶。

## 3. 路径 B：NAS 上还没有库，顺带起一个 MySQL

同一本文件里把 `mysql:` 那段注释取消，并做三件事：

1. `PB_DB_HOST` 改成 `mysql`（服务名，不是 IP）；
2. `PB_DB_PASSWORD` 与 `MYSQL_PASSWORD` 填**同一个值**（compose 不能在一个文件里引用自己写过的值，
   两处同值是这套写法的代价）；
3. 文件末尾 `volumes:` 里把 `db_data: {}` 的注释去掉。

这条路不用手工建库：`MYSQL_DATABASE=pushbox` + `MYSQL_USER`/`MYSQL_PASSWORD` 会把库和账号建好，
表仍由 Flyway 迁移。**3306 不发布到宿主**是刻意的——两个容器在同一张默认网络上，按服务名就够，
把一个能读写 pushbox 库的端口摆进局域网没有收益。

> 空库首次初始化在 NAS 上普遍要 1～2 分钟，而 entrypoint 的 TCP 预检默认只等 60s。第一次起
> （只有这一次）建议补一行 `PB_DB_WAIT_SECONDS: "180"`，起来之后可以再删掉。判断是不是撞上了这个：
> 日志会明说 `连不上 mysql:3306（已经等满 60s）`，而 `docker logs <库容器> --tail 20` 那边还在
> `Initing db` / `Preparing system tables`。

## 4. 验收

- **看容器日志**，这一段是判据给的，不用猜：
  ```
  🔍 验证生产环境配置...
  ℹ️  PB_DB_URL 未设置，已按 PB_DB_HOST=192.0.2.20 PB_DB_PORT=3306 PB_DB_NAME=pushbox 拼出连接串
  ✅ 数据库端口可达：192.0.2.20:3306
  ℹ️  对象存储走本地文件系统（PB_STORAGE_TYPE=local）……
  ⚠️  图片与头像会明文落在 /app/data/media……
  ℹ️  PB_ADMIN_SECRET 未设置，Web 管理台已禁用……

  ✅ 配置验证通过，启动 Spring Boot...
  ```
  任何一条 `❌ 错误:` 都会让容器直接退出，并且**那句话里就写了下一步做什么**。
- 日志里出现 `Started PushboxServerApplication` 就算就绪（首次启动还要跑 Flyway，多等一会儿）。
- 浏览器打开 `http://<NAS_IP>:7404/`：开了管理台就是登录页（口令 = `PB_ADMIN_SECRET`）；
  没开的话是一个 404/空白页，属正常。
- Android / PC 客户端注册时服务端地址填 `http://<NAS_IP>:7404`。同一路由器内可达；
  要跨公网必须自己上 HTTPS 反代——明文链路上跑的是消息正文。

**`/healthz` 不给你从浏览器验收**：运维端点默认只放行容器环回（`PB_SECURITY_INTERNAL_CIDRS` 留空），
从宿主打过去源地址是 docker 网桥 → **404**。这不是坏了，是刻意的：对外一律 404 而不是 403，
扫描器不该从状态码里确认"这里确实有个运维端点"。要在容器里自己看一眼，这句是通的（镜像里有 curl）：

```bash
docker exec <容器名> curl -s http://127.0.0.1:7404/healthz
```

返回体是 `{"ok":true,"db":"up","storage":"up","uptime_ms":…}`。要让外部监控抓到，把这套 compose
网络实际分到的子网显式写进 `PB_SECURITY_INTERNAL_CIDRS`（`<项目名>` 就是你在界面上填的那个）：

```bash
docker network inspect <项目名>_default -f '{{range .IPAM.Config}}{{.Subnet}}{{end}}'
```

## 5. 起不来时按这句找

| 日志里的那句 | 真正的原因 |
|---|---|
| `PB_DB_PASSWORD 未设置` | 少填一项。注意空串等同没填 |
| `连不上 <host>:3306（已经等满 60s）` | 那句报错里已经按顺序列了四条：主机填成 `127.0.0.1`/`localhost`、库那层没发布端口、**库容器自己在崩溃循环**（`docker logs <库容器> --tail 20` 一看就知道，比如 `unknown variable` 后面跟一个它不认识的启动参数）、防火墙拦了。口令错**不会**报这句，那一层报的是 1045。第一次起空库撞超时 → 见上面路径 B 的 `PB_DB_WAIT_SECONDS` |
| `1045 Access denied`（在 Java 的日志里） | 口令不对，或用户主机写法不是 `'pushbox'@'%'`。**库名对不上也报这一句**（`Access denied for user 'pushbox'@'%' to database '<库名>'`），MySQL 8 在这种配法下不会给你 `Unknown database`；那句 `using password: ***` 里的 `***` 是我们自己的脱敏把 YES/NO 遮了，不是口令 |
| `本地存储目录 /app/data/media 不可写（容器内进程是 uid 10001）` | 你挂的是 NAS 共享文件夹（绑定挂载带来 root 属主）。改用文件里那两个 named volume，或 `sudo chown -R 10001:10001 <那个目录>` |
| `PB_STORAGE_TYPE=local 但环境里还配着 PB_S3_*` | type 忘了改回 `s3`，或确实只想本地存储——后者把那几项删掉，别留一个"看着像配了桶"的现场 |
| `生产环境不允许对象存储不加密` | 只在 `PB_STORAGE_TYPE=s3` 时会出现。设 `PB_S3_OBJECT_ENCRYPT=sse-c` + 主钥，或明确接受明文就 `PB_ALLOW_PLAINTEXT_MEDIA=1` |
| `启用 Web 管理台必须另设 PB_ADMIN_JWT_SECRET` / `不能与 PB_ADMIN_SECRET 同值` / `过短` | 管理台的两把钥必须都有、≥32 字节、且互不相同。各生成一把：`openssl rand -base64 32` |
| 服务起来了，图片消息一直转圈/`failed` | 本地存储下 `/healthz` 会真的往目录里写一个临时文件，所以 `storage=down` 就是不可写；`s3` 那一支才只判配置非空。先看容器日志有没有 entrypoint 那句"目录不可写"，再看 `docker volume ls` 里 `<项目名>_server_media` 在不在 |

## 6. 备份与迁移

- 数据 = **MySQL 里的 `pushbox` 库** + **`server_media` 卷里的对象**，缺任何一边都不算备份成功。
  查卷落在哪：
  ```bash
  docker volume inspect <项目名>_server_media -f '{{.Mountpoint}}'
  ```
- 只换镜像版本、不动卷 → 数据照用。删容器不删卷 → 数据还在；`docker compose down -v` 会把卷一起删掉。
- 生产那套 `deploy/backup/backup.sh`（mysqldump + age 加密）读的是 `PB_DB_*`/`PB_S3_*`，
  本地存储模式下把 `MEDIA_DIR` 指向上面那个 Mountpoint 即可。

## 7. 这一节的验证边界

**已经在真容器里跑过一遍**（写这份文档的这台开发机，WSL2 里的 Docker，`uname -m` 是 x86_64）：
用本仓库的 `deploy/Dockerfile` 现场构建出一个镜像，起一个 `mysql:8.4` 容器并在自定义网络上挂
别名 `mysql`，应用容器**运行时只喂了一个 `-e PB_DB_PASSWORD`**——连 `PB_DB_HOST` 都没给，
走的是镜像烘焙的默认值 `mysql`。结果：

- 上面第 4 节那段日志逐字出现，没有多出或缺少判据行；
- Flyway 在空库上把 9 条迁移全跑完（`Successfully applied 9 migrations … now at version v9`），
  进程 `Started PushboxServerApplication in 7.163 seconds`；
- 容器内验收（第 4 节那条命令）：`/healthz` 返回 200 + `{"ok":true,"db":"up","storage":"up",…}`，
  根路径 `/` 吐出管理台页面，`/admin` 是 404，`POST /message` 不带凭据是 401 `invalid_credential`，
  管理台登录是 403「管理台未启用」——四条都和"没设 `PB_ADMIN_SECRET`"这个状态一致。

判据本身另有 25 条场景矩阵跑过（缺口令、库地址写成环回、库不可达、`local` 下残留 `PB_S3_*`、
媒体目录不可写、加密取值带大小写与空白、管理台只给一把钥／两把同值／过短 等），
每条都对上了预期的输出与退出码。

**没有实测过的**：飞牛/群晖/1Panel 的图形界面本身——按钮的确切位置、"Compose 项目"表单怎么吃这本
YAML、镜像拉取要不要先在 Docker 设置里配国内加速源、ARM 机型（见第 0 节架构那条）。
第 5 节里"绑定挂载到 NAS 共享文件夹 → uid 10001 写不动"那条是按容器内运行用户推出来的判据，
判据代码在镜像里跑过（给一个 root 属主的空目录能稳定复现那句报错），但**没有在飞牛的共享文件夹上复现过**。
上面第 4 节那句 `Started PushboxServerApplication in 7.163 seconds` 是这台开发机的耗时，
NAS 机械盘上会更慢，别拿这个数当基准。
