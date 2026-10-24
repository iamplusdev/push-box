# PushBox WSL 测试栈速查

这份是**发布前在 WSL Docker 上真跑一次镜像**的操作卡：拓扑 B（MySQL + RustFS + 服务端 + Caddy 全都自己起）。
它顺带也是**安装页的端到端验证**——`deploy-wsl.sh setup` 走的正是真人点「保存并启动」那一次
`POST /api/v1/setup`，不是另一条后门。

配套文档：部署形态与口径看 [部署快速参考.md](部署快速参考.md)，NAS 图形界面装法看 [NAS部署指南.md](NAS部署指南.md)，
WSL 这套栈本身的说明看 [WSL部署指南.md](WSL部署指南.md)。这一份只放**跑过、踩过、下次还要照抄**的东西。

> 服务端的配置**不在环境变量里**，全在安装页上填，落进容器的 `/app/data/config/pushbox.json`。
> `wsl-test.env` 只是 `deploy-wsl.sh` 与 compose 的输入：前者用它拼那一次 POST 的 JSON，
> 后者用它喂 mysql / rustfs / grafana 那几个第三方镜像。键的三类归属见 WSL部署指南「配置说明」。

---

## 0. 前置检查

```bash
wsl --version          # PowerShell 里
wsl docker version     # 客户端 + 服务端都要有输出
wsl docker info        # 起不来说明 Docker Desktop 没在跑，或没给这个发行版开集成
```

`wsl-test.env` 不存在就先抄一份模板再填（**这个文件不入库**，里面有真口令）：

```bash
cd infra
cp wsl-test.env.example wsl-test.env
```

---

## 1. 首跑四条命令

顺序不能换：**桶不会自动出现**，`setup` 之前它必须已经在了。

```bash
cd infra
./deploy-wsl.sh build && ./deploy-wsl.sh up && ./deploy-wsl.sh bucket && ./deploy-wsl.sh setup
```

| 子命令 | 做什么 |
|--------|--------|
| `build` | `docker compose -f docker-compose-wsl.yml build pushbox-server`（用 `infra/Dockerfile`，构建上下文是仓库根） |
| `up` | 起 MySQL 8.4 + RustFS + 服务端 + Caddy。服务端会**停在安装页上**，这是正常的 |
| `bucket` | 在 RustFS 上建桶。借 `amazon/aws-cli` 跑在 rustfs 自己的网络命名空间里（compose 没给 rustfs 发布端口，而 `minio/mc` 已从 Docker Hub 下架） |
| `setup` | 把 `wsl-test.env` 拼成 JSON 提交给安装页，然后等 `/healthz` 通 |
| `down` | 停栈，**卷留着**（配置与图片都还在） |
| `logs [service]` | 跟日志，默认 `pushbox-server` |
| `restart` | 重启全部服务 |
| `clean` | 连卷一起删（`down -v`）+ 清 `data/prometheus`、`data/grafana`。会先问一次确认，答 `yes` 才动手；没有可交互终端时按「取消」处理 |

手工等价（脚本坏了或想看清楚每一步时在 WSL 里跑）：

```bash
cd /mnt/d/Projects/Android/PushBox/infra
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY   # Docker 代理不通时先清
docker compose -f docker-compose-wsl.yml --env-file wsl-test.env build pushbox-server
docker compose -f docker-compose-wsl.yml --env-file wsl-test.env up -d
docker compose -f docker-compose-wsl.yml --env-file wsl-test.env ps
```

**没有 `mkdir data/mysql`、没有 `chown`、不要 `rm -rf data/*`**：服务端与两个存储都是 named volume
（`server_data` / `mysql_data` / `rustfs_data`），只有 prometheus 与 grafana 用 `infra/data/` 下的绑定挂载。
刻意不把服务端那份挂成宿主目录——`deploy-wsl.sh` 多半跑在 `/mnt/d` 上，drvfs 不支持 `chown`，
宿主目录属主进不了 10001，entrypoint 的写入探针会直接拒绝启动。清数据用 `clean`，不是 `rm`。

---

## 2. 镜像标签要对得上

`docker-compose-wsl.yml` 里写死的是 `image: pushbox/server:1.1.1`（跟 `pom.xml` 走，
由 `scripts/set-version.sh --only=server` 统一改）。绕开 compose 自己构建时必须打成同一个标签，
否则 compose 找不到镜像又去拉远端：

```bash
docker buildx build --network=host -f infra/Dockerfile -t pushbox/server:1.1.1 --load .
```

`--network=host` 是为了躲开下面 Q3 那个 IPv6 坑，它只影响构建，不进镜像、不影响运行时网络。

---

## 3. 验收

### 3.1 健康检查要在**容器里**取

```bash
docker exec pushbox-test-pushbox-server-1 curl -s http://127.0.0.1:7404/healthz
# {"ok":true,"db":"up","storage":"up","uptime_ms":27023}
```

从**宿主**打 `http://localhost:7404/healthz` 会拿到 **404**：`InternalAccessFilter` 只看 `getRemoteAddr()`，
经端口映射进来的连接在容器里是网桥网关地址（拒绝时刻意不用 403，免得扫描器确认这里有个运维端点）。
要宿主或监控直接读到它，把这套网络分到的子网填进 `INTERNAL_CIDRS`（→ 安装页「运维端点放行网段」）。

`storage:up` 在对象存储模式下**只代表 endpoint 与 bucket 非空**，不探桶；本地存储模式才真的写一个临时文件。
容器状态 `healthy` 也只说明 TCP 探活通了（healthcheck 是 `exec 3<>/dev/tcp/127.0.0.1:7404`），不等于 `db=up`。

### 3.2 配置真的落盘了吗

```bash
docker exec pushbox-test-pushbox-server-1 cat /app/data/config/pushbox.json
```

看两个地方就够：`storage.s3` 是不是 `true`、`media.allowHosts` 是不是你填的那几台。
**口令值不要往终端上打**——要核就核键在不在，别 `cat` 整份。

### 3.3 十二条判据的真服务体检

`apps/windows/PushBox.LiveCheck` 是控制台项目（xunit 2.5 没有运行时 skip，所以做成能自己起服务端的形式），
它在自己那个 8081 上跑 ①–⑫。**别把 7404 上手动起的实例停掉**：那是别人的，体检用 8081。

### 3.4 最近一次实测（2026-10-23）

四个容器全部 `Up`（mysql `healthy`、server `healthy`、rustfs、caddy），`/healthz` 在容器里 200，
LiveCheck 12/12，入站 `POST /message` 带一张内网图片走到 `media_objects.status=ready` + `enc_mode=sse-c` +
`length(sse_key_wrapped)=61`，桶里那份字节既无 PNG 魔数也无 `IHDR`（落盘是密文）。
这一轮照出三条只有真跑容器才看得见的缺陷，都记在 CHANGELOG 的「缺陷与修法（真跑一次容器才照出来的三条）」。

---

## 4. 管理台 API 速查

口令从 `wsl-test.env` 读，别写进命令行历史，也别贴进聊天：

```bash
cd /mnt/d/Projects/Android/PushBox/infra
ADMIN_PASSWORD="$(sed -n 's/^ADMIN_PASSWORD=//p' wsl-test.env | tr -d '\r')"

TOKEN=$(curl -s -X POST http://localhost:7404/api/v1/admin/login \
  -H 'Content-Type: application/json' \
  -d "{\"secret\":\"$ADMIN_PASSWORD\"}" | sed -E 's/.*"token":"([^"]+)".*/\1/')

curl -s http://localhost:7404/api/v1/admin/overview   -H "Authorization: Bearer $TOKEN"
curl -s http://localhost:7404/api/v1/admin/channels   -H "Authorization: Bearer $TOKEN"
curl -s http://localhost:7404/api/v1/admin/devices    -H "Authorization: Bearer $TOKEN"
```

**建频道**：`groupId` 必填，而且必须是 `/admin/devices` 里真出现过的 `group_id`（`groups` 表里有这一行）。
省略或写不存在的 id 一律 400 `invalid_param`——管理台 JWT 是虚拟身份 `admin`，回退过去会建出一个
**任何设备都永远查不到**的频道。响应里的 `secret` **只出现这一次**，库里存的是哈希。

```bash
curl -s -X POST http://localhost:7404/api/v1/admin/channels \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"groupId":"把 /admin/devices 里某个 group_id 抄过来","name":"测试频道","maxLevel":"warning","maxImages":4}'

curl -s -X POST http://localhost:7404/api/v1/admin/channels/12345678/rotate-secret \
  -H "Authorization: Bearer $TOKEN"

# 搜索消息：路径就是 /messages，没有 /search 那一段
curl -s "http://localhost:7404/api/v1/admin/messages?q=test&limit=50" -H "Authorization: Bearer $TOKEN"
```

**入站消息不带 `/api/v1` 那个前缀**，就是根路径 `/message`（给它加上前缀会得到 Spring 的 404）：

```bash
curl -s http://localhost:7404/message -H 'Content-Type: application/json' \
  -d '{"channel":"12345678","secret":"…","title":"测试","content":"正文","level":"info","sender_id":"dev"}'
```

Git Bash 里别用 `-d` 内联长 JSON（引号会被改动，服务端只回「请求体必须是合法 JSON」）：写进文件用 `--data-binary @payload.json`。

---

## 5. 要验图片链路：额外起一个内网图床

这台机器上的容器**没有公网出口**，所以图片外链只能指向 compose 网络里的东西；而那种地址正是
`SsrfGuard` 默认拒绝的私网段——不点名的话每条图片都落成 `media_objects.error=ssrf_blocked`，
除了服务端日志之外没有任何提示。

```bash
# 1) 宿主上准备一张图
mkdir -p /tmp/pbimg && cp 某张.png /tmp/pbimg/test.png

# 2) 在 compose 网络里起一个只读 HTTP 服务（容器名 pb-img，端口 8899）
wsl docker run -d --name pb-img --network pushbox-test_pushbox-net \
  -v /tmp/pbimg:/img:ro python:3-alpine python -m http.server 8899 --directory /img

# 3) 把它写进 wsl-test.env 的放行主机（逗号分隔，裸 host 或 host:port）
#    MEDIA_ALLOW_HOSTS=pb-img:8899
```

**放行主机是精确等值比较，没有通配**，写 `http://pb-img:8899` 或 `*.local` 都等于没填，安装页会当场拒。
`host.docker.internal` 在这套网络里**解析不出来**，而 `SsrfGuard` 是**先解析 DNS 再比白名单**——
解析不了的主机即使在名单里也照样 `ssrf_blocked`。所以要用网络里真能解析到的名字。

改完 `MEDIA_ALLOW_HOSTS` 要重灌一次配置（见第 6 节）才生效。

验完清理：

```bash
wsl docker rm -f pb-img && rm -rf /tmp/pbimg
```

读侧那两个字节端点（`/api/v1/media/{msgId}/{idx}` 与 `/api/v1/avatars/{token}`）都要 `DeviceGuard` 的设备签名，
**curl 复现不出来**；要看读侧就走 LiveCheck 或真客户端。

---

## 6. 改了 wsl-test.env 想重灌配置

安装页只在配置文件**不存在**时启动，所以要先删掉它再重启，`setup` 才有的可提交：

```bash
docker exec pushbox-test-pushbox-server-1 rm -f /app/data/config/pushbox.json
docker restart pushbox-test-pushbox-server-1
./deploy-wsl.sh setup
```

只想改一项就别跑 `setup`（它整份重灌）：删掉配置重启之后，安装页上先点「从库里找回上一次的配置」
把上次那份捞回来（配置副本存在 `app_state.runtime_config_v1`），改那一项再保存。

**加密口令不能这么改**：KEK 一旦有对象落库就不可轮换，换了口令之前写进桶里的图片再也解不开，只能清桶重来。

---

## 7. 常见问题

### Q1: Docker 代理连不上

现象 `proxyconnect tcp: dial tcp 127.0.0.1:7890: connect: connection refused`。

```bash
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY
```

永久改就编辑 WSL 里的 `~/.docker/config.json`，去掉 `proxies` 那段。

### Q2: MySQL 报 `Cannot change permissions of the file 'private_key.pem.temp' (OS errno 1)`

NTFS 不支持 MySQL 要的那套 POSIX 权限操作。**已修**：mysql 与 rustfs 都用 named volume，不再绑定挂载。
看到这条说明有人把 `./data/mysql` 又挂回去了。

### Q3: 构建卡在下载依赖（容器里 IPv6 不通）

现象：`mvn package` 停在某个 `.pom` 上不动，或 `curl: (35) Recv failure: Connection reset by peer`；
同一个 URL 在 WSL 宿主上 `curl` 秒回。原因是容器解析 `repo.maven.apache.org` 拿到 AAAA 记录，
而 Docker bridge 网络里没有可用的 IPv6 出口。修法就是第 2 节那条 `--network=host`。

### Q4: 走 80 端口（Caddy）返回 200 但响应体是空的

`Caddyfile` 的站点地址是 `{$PB_CADDY_HOST:pushbox.internal}:80`，Caddy 按 Host 头匹配站点。`wsl-test.env` 里是
`PB_CADDY_HOST=localhost`，所以 Host 为 `127.0.0.1` 的请求匹配不到任何站点，Caddy 直接回一个空的 200——
请求根本没进 `reverse_proxy`。用配置里那个主机名访问，或把 `PB_CADDY_HOST` 改成 `_` 接受任意 Host。

### Q5: 端口 7404 / 80 被占

改 `wsl-test.env` 的 `PB_SERVER_PORT` / `PB_HTTP_PORT`（这两个是**宿主侧**映射，容器里永远是 7404），
然后 `./deploy-wsl.sh down && ./deploy-wsl.sh up`。

**这台机器上 7404 常常已经被手动起的服务端占着**：那种实例不要停，测试栈换端口，或者先确认没人用再动。

### Q6: MySQL 起得慢，服务端报连不上库

首次启动要初始化，等 30–60 秒。`./deploy-wsl.sh logs mysql` 看它到哪一步了；
compose 里服务端是 `depends_on: condition: service_healthy`，正常情况下不会抢跑。

### Q7: 服务端起来了但每条图片都 `failed`

查 `media_objects.error`：`ssrf_blocked` 是第 5 节那个放行主机没配（或配了个解析不出来的名字），
`timeout` 是容器出不去那个地址。

```bash
docker exec -e P="$MYSQL_ROOT_PASSWORD" pushbox-test-mysql-1 \
  sh -c 'mysql -uroot -p"$P" pushbox -e "select msg_id, idx, status, enc_mode, ifnull(error,\"-\") from media_objects order by id desc limit 10;"'
```

列名容易记错的是 `idx`（不是 `slot`）与 `size`（不是 `size_bytes`）。

---

## 8. 停止与清理

```bash
./deploy-wsl.sh down     # 停栈，卷留着：配置与图片都还在
./deploy-wsl.sh clean    # 连卷一起删，会问一次确认；下次 up 之后要重新 bucket + setup
```

`clean` 删掉的 `server_data` 里既有图片也有那份 `pushbox.json`。想留着配置就只 `down`。

---

## 9. 监控（可选）

```bash
docker compose -f docker-compose-wsl.yml --env-file wsl-test.env --profile monitoring up -d
```

- Prometheus: <http://localhost:9090>
- Grafana: <http://localhost:3000>（admin / `wsl-test.env` 里的 `GRAFANA_ADMIN_PASSWORD`）

**开监控必须把这条网络分到的子网填进 `INTERNAL_CIDRS`**，否则进程好好的、targets 全 403：

```bash
docker network inspect pushbox-test_pushbox-net -f '{{range .IPAM.Config}}{{.Subnet}}{{end}}'
```

---

## 10. 从 Git Bash 驱动 WSL 的四个坑

1. **`wsl-test.env` 是 CRLF**。WSL 那边直接 `. ./wsl-test.env` 会报 `$'\r': 未找到命令`，
   要 `. <(sed 's/\r$//' ./wsl-test.env)`。Git Bash 里 `deploy-wsl.sh` 自己的 `envval` 已经带 `tr -d '\r'`，不受影响。
2. **整段脚本别塞进 `wsl bash -c '…'`**：引号会被嚼碎（典型症状是 `-H \"Content-Type…` 语法错）。
   用 `wsl bash -s <<'SCRIPT' … SCRIPT` 从标准输入喂。
3. **`MSYS_NO_PATHCONV=1` 只该给 docker/wsl 那几条命令用**。全局 export 会顺手关掉 Git Bash 对
   **所有**原生 Windows 程序的路径转换，于是 `mvn` 把 `/d/Apps/...` 原样交给 `java.exe`，
   报 `ClassNotFoundException: org.codehaus.plexus.classworlds.launcher.Launcher`——看着像 Maven 坏了，其实是路径没转换。
4. **`cygpath` 在 WSL 里不存在**。别写 `wsl bash -c "cd $(cygpath -m ...)"` 这种嵌套；
   路径转换在 Git Bash 这一侧做完，或者直接写 `/mnt/d/...`。
