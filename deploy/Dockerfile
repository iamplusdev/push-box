# syntax=docker/dockerfile:1
# §11.2 服务端多阶段构建。构建上下文必须是仓库根（要 core/ + server/ + web-admin/ + 根 pom.xml），
# compose 里写成 context: .. / dockerfile: deploy/Dockerfile。
# D27 后 Java 侧走 Maven，所以基础镜像用 maven 而不是设计早期草稿里的 gradle。
# web-admin SPA 在独立 node 阶段构建后拷进 server 的静态资源目录，最终 fat jar 自带前端，
# 由同一个 Spring Boot 进程在 PB_SERVER_PORT（默认 7404）上同时吐 API 与页面
# （同源，无需 CORS/额外反代规则；SPA 的路由回落见 SpaWebConfig）。
#
# 这个镜像的目标用法是"在图形界面里拉下来、填两个框就能跑"（飞牛/群晖/1Panel 这类 NAS UI 没有
# .env 这一层，只能在表单里逐条加环境变量）。所以 D76 把配置分成三类：
#   烘焙（下面的 ENV 段）：非凭据的拓扑默认值——端口、存储模式、目录、库名与库用户名、区域与桶。
#   必填（只剩 2 个）：PB_DB_HOST 或 PB_DB_URL、PB_DB_PASSWORD。口令绝不给默认值。
#   按需：管理台两把钥（留空即整个管理台关闭，这本身是一个安全状态）、外接对象存储的 PB_S3_* 三项。

# ---- 前端：构建管理台 SPA，产物 /web/dist ----
FROM node:22-alpine AS webbuild
WORKDIR /web
# 先只拷依赖清单，让 npm ci 这一层在 package-lock 不变时被缓存复用。
COPY web-admin/package.json web-admin/package-lock.json ./
RUN npm ci
COPY web-admin/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY core/pom.xml core/
COPY core/src core/src
COPY server/pom.xml server/
COPY server/src server/src
# 把 SPA 打进 jar：拷进 server 的静态资源目录，mvn package 会一并塞进 BOOT-INF/classes/static。
COPY --from=webbuild /web/dist server/src/main/resources/static
# 依赖缓存放 BuildKit cache mount：源码改动不重下几百个依赖，但也不把 .m2 烙进镜像层。
# 单测跑在 CI（mvn -B verify）里把关跨端向量，这里只为出包，跳过避免镜像构建拖慢。
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:21-jre AS run
WORKDIR /app
# Boot 的分层 jar 由 repackage 默认产出；这里整包拷入即可，层缓存收益留给 registry 端。
COPY --from=build /src/server/target/pushbox-server-*.jar app.jar

# 启动前验证脚本：把"缺什么、填在哪、填成什么形态"一次说清，并顺手预检库可达。
# 它是容器拓扑唯一的入口守门人：compose 那一侧不再用 ${VAR:?} 拦（NAS 的图形界面没有 .env，
# 解析阶段的报错既读不懂也改不动），所以凡是"缺了就等于裸奔或静默半死"的判据必须落在这里。
COPY <<EOF /app/entrypoint.sh
#!/bin/bash
set -euo pipefail

echo "🔍 验证生产环境配置..."

# ---- 1. 数据库：接受两种写法，出门时一律是完整的 PB_DB_URL ----
# 让人在 NAS 表单里手打带 ? & = 的 JDBC 串是最高频的填错点（少一个 & 只表现为连不上，
# 猜错原因还要去翻 Java 堆栈），所以允许只填 PB_DB_HOST。两个都填时以 PB_DB_URL 为准。
DB_HOST="\${PB_DB_HOST:-mysql}"
DB_PORT="\${PB_DB_PORT:-3306}"
DB_NAME="\${PB_DB_NAME:-pushbox}"
if [ -z "\${PB_DB_URL:-}" ]; then
  export PB_DB_URL="jdbc:mysql://\${DB_HOST}:\${DB_PORT}/\${DB_NAME}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8"
  echo "ℹ️  PB_DB_URL 未设置，已按 PB_DB_HOST=\$DB_HOST PB_DB_PORT=\$DB_PORT PB_DB_NAME=\$DB_NAME 拼出连接串"
fi

if [ -z "\${PB_DB_PASSWORD:-}" ]; then
  echo "❌ 错误: PB_DB_PASSWORD 未设置（库口令——本镜像剩下的唯一必填项）"
  echo "   飞牛/群晖的容器表单里加一行 PB_DB_PASSWORD=<你的库口令>；用 compose 就写在 environment 段里。"
  echo "   这一项同时是 MySQL 里那个用户的口令，所以取值以「能连上那台库」为准，不能随便填一个字符串。"
  exit 1
fi

# 从 JDBC 串里认主机与端口：jdbc:mysql://host:port/db?params → host / port
# 这段 heredoc 的转义规则是实测出来的，别按 shell 的直觉写：BuildKit 会对未加引号的 heredoc
# 做变量展开，"反斜杠 + 美元符"变成运行时变量引用，而**其它反斜杠它直接吃掉**——
# "反斜杠 + 左括号"落进镜像里只剩一个左括号，"反斜杠 + 引号"同理。第一版这里写的是 case 模式
# 里带转义括号的字符类，装出来的 /app/entrypoint.sh 变成未转义的括号，bash 当场报
# "syntax error near unexpected token"，容器 1 秒退出。
# 所以判"带括号"用 grep 的单引号字符类，一个反斜杠都不写；运行时变量一律用转义形式。
DB_ENDPOINT=\$(printf '%s' "\$PB_DB_URL" | sed -E 's#^jdbc:mysql://##; s#^[a-z]+://##; s#[/?].*##')
DB_PROBE_HOST=\${DB_ENDPOINT%%:*}
DB_PROBE_PORT=\${DB_ENDPOINT##*:}
case "\$DB_PROBE_PORT" in ''|*[!0-9]*) DB_PROBE_PORT=3306;; esac
if [ -z "\$DB_PROBE_HOST" ] || printf '%s' "\$DB_PROBE_HOST" | grep -q '[()]'; then
  echo "⚠️  没能从 PB_DB_URL 里认出主机名（比如 address=() 这类别名写法），跳过连通性预检"
  DB_PROBE_HOST=""
else
  case "\$DB_PROBE_HOST" in
    127.*|localhost|::1)
      echo "⚠️  库地址写的是 \$DB_PROBE_HOST：容器里的环回是它自己。除非这个容器跑在宿主网络模式里，"
      echo "   这条地址一定连不上。NAS 上要填那台机器的局域网 IP（例如 192.168.x.x）；"
      echo "   库在同一个 Compose 项目里时直接填服务名（默认就是 mysql）。"
      ;;
  esac
fi

# TCP 预检。为什么值得等：省下来的是一条没人会读的堆栈——Hikari 连不上时 Spring 先打几百行
# WARN 再退出，restart 策略又反复重放，日志里完全看不出"其实是地址写错了"。
# 只判 TCP，不判口令：判口令要么得在镜像里放 mysql 客户端，要么把凭据交给外部工具。
if [ -n "\$DB_PROBE_HOST" ]; then
  WAITED=0
  LIMIT=\${PB_DB_WAIT_SECONDS:-60}
  while ! (timeout 3 bash -c "exec 3<>/dev/tcp/\${DB_PROBE_HOST}/\${DB_PROBE_PORT}") 2>/dev/null; do
    if [ "\$WAITED" -ge "\$LIMIT" ]; then
      echo "❌ 错误: 连不上 \$DB_PROBE_HOST:\$DB_PROBE_PORT（已经等满 \${LIMIT}s）"
      echo "   按这个顺序查："
      echo "     1) 主机填错。127.0.0.1 / localhost 在容器里指容器自己；库装在飞牛上就填 NAS 的局域网 IP，"
      echo "        库在同一个 Compose 项目里就填服务名 mysql。"
      echo "     2) 库那一层没把端口发布出来（在宿主机用 docker ps 看它那行的 PORTS）。"
      echo "     3) 库容器自己在崩溃循环——这条最容易漏：TCP 连不上和「库根本没起来」长得一样。"
      echo "        docker logs <库容器名> --tail 20；mysqld 会明说，比如 unknown variable 后面跟一个"
      echo "        它不认识的启动参数——初始化做完就退出，端口从来没人监听过。"
      echo "     4) NAS 或 Windows 防火墙拦了入站连接。"
      echo "   口令错不会走到这一步——那一层由 MySQL 自己拒，报的是 1045。"
      echo "   确实要先起服务、库稍后才有：把 PB_DB_WAIT_SECONDS 调大（空库首次初始化在 NAS 上普遍要"
      echo "   1～2 分钟，60s 这个默认只是给「地址写错」那种情况快速出结果），或靠 restart 策略反复重试。"
      exit 1
    fi
    WAITED=\$((WAITED + 2))
    sleep 2
  done
  echo "✅ 数据库端口可达：\$DB_PROBE_HOST:\$DB_PROBE_PORT"
fi

# ---- 2. 对象存储：只有走 S3 协议才要那三项；local 直接落进卷 ----
# 镜像默认 local（单容器自包含），而 deploy/docker-compose.yml 显式传 s3（生产那两套拓扑）。
# 两边默认不同是刻意的，别把它当成不一致去"统一"。
STORAGE_TYPE=\$(printf '%s' "\${PB_STORAGE_TYPE:-local}" | tr -d '[:space:]' | tr 'A-Z' 'a-z')
if [ "\$STORAGE_TYPE" = "local" ]; then
  MEDIA_DIR="\${PB_STORAGE_LOCAL_PATH:-/app/data/media}"
  echo "ℹ️  对象存储走本地文件系统（PB_STORAGE_TYPE=local），对象落在 \$MEDIA_DIR；不需要 PB_S3_*"

  # 分档必填引进来的反面风险，与 dev 侧 DevCredentialsGuard 同一条判据：忘了把 type 改成 s3
  # 不再让启动失败，只会让整套部署静默落在本地目录上，而跑起来的一切看着都像桶形态。
  LINGERING=""
  for var in PB_S3_ENDPOINT PB_S3_ACCESS_KEY PB_S3_SECRET_KEY; do
    if [ -n "\${!var:-}" ]; then LINGERING="\$LINGERING \$var"; fi
  done
  if [ -n "\$LINGERING" ]; then
    echo "⚠️  PB_STORAGE_TYPE=local 但环境里还配着\$LINGERING：这几项在 local 模式一个字都不读。"
    echo "   想跑桶形态就设 PB_STORAGE_TYPE=s3 重启；确实只要本地存储就把它们从 .env 删掉，"
    echo "   别给下一个排障的人留一个「看着像配了 S3」的现场。"
  fi

  # 目录必须真能写，而且要在进程起来之前就判死。健康检查那一层抓不到这件事给人看：
  # HealthzController 的 local 分支确实会试写并把 storage 报成 down，但 /healthz 默认只放行容器环回
  # （InternalAccessFilter），而 compose 的 healthcheck 只探 7404 的 TCP——在图形界面里看"容器已启动"
  # 的人两头都看不见。照 2026-10-19「健康检查全绿、每条图片消息停在 failed」那次的教训，
  # 这类错只能在启动时出声。
  mkdir -p "\$MEDIA_DIR" 2>/dev/null || true
  if ! ( : > "\$MEDIA_DIR/.entrypoint-write-probe" ) 2>/dev/null; then
    echo "❌ 错误: 本地存储目录 \$MEDIA_DIR 不可写（容器内进程是 uid 10001）"
    echo "   两种修法任选其一："
    echo "     1) compose 用 named volume（推荐）：把 - ./data/media:/app/data/media"
    echo "        换成 - server_media:/app/data/media，并在文件末尾的 volumes: 里补一行 server_media: {}"
    echo "     2) 继续用宿主目录，就把它交给运行用户：sudo chown -R 10001:10001 ./data/media"
    echo "   飞牛/群晖的图形界面里「选一个共享文件夹挂进来」就是第 2 种：要么改用它的「卷」，"
    echo "   要么把那个目录 chown 给 10001，否则图片永远写不进去。"
    echo "   不挂卷也起得来（对象写容器可写层），但 docker rm 之后图片全没——那是 local 拓扑的代价，不是缺陷"
    exit 1
  fi
  rm -f "\$MEDIA_DIR/.entrypoint-write-probe"
else
  for var in PB_S3_ENDPOINT PB_S3_ACCESS_KEY PB_S3_SECRET_KEY; do
    if [ -z "\${!var:-}" ]; then
      echo "❌ 错误: \$var 未设置（PB_STORAGE_TYPE=\$STORAGE_TYPE 时它是必填）"
      echo "   不想外接存储就设 PB_STORAGE_TYPE=local"
      exit 1
    fi
  done
fi

# ---- 3. 媒体加密：桶形态硬拦，本地盘只出声 ----
# 分档理由见 docs/软件设计方案.md D76：这条守卫防的是"图片被同一把 AK/SK 的任何读者读走"，
# 而 local 的对象就在自己那块盘上、权限 600，威胁模型不同。判据与 Java 侧
# ProdPlaintextMediaGuard 必须一致，否则容器在 entrypoint 起来了、Java 里退出，看着像坏了。
# 连取值口径也要一致：Java 那边是 trim + 转小写，这里不归一就会出现"SSE-C 被 Java 认、
# 被 entrypoint 当成明文拒掉"——同一份配置两个组件各说一套，是最难查的那类错。
OBJECT_ENCRYPT=\$(printf '%s' "\${PB_S3_OBJECT_ENCRYPT:-none}" | tr -d '[:space:]' | tr 'A-Z' 'a-z')
if [ "\$OBJECT_ENCRYPT" = "sse-c" ]; then
  MASTER_KEY="\${PB_S3_MASTER_KEY:-}"
  if [ -z "\$MASTER_KEY" ]; then
    echo "❌ 错误: SSE-C 模式下 PB_S3_MASTER_KEY 不能为空"
    echo "   生成命令: openssl rand -base64 32"
    exit 1
  fi

  # base64 编码的 32 字节 ≈ 44 字符（含 padding）
  KEY_LEN=\${#MASTER_KEY}
  if [ \$KEY_LEN -lt 40 ] || [ \$KEY_LEN -gt 48 ]; then
    echo "❌ 错误: PB_S3_MASTER_KEY 长度不正确 (\$KEY_LEN 字符)"
    echo "   期望: base64 编码的 32 字节（约 44 字符）"
    echo "   不把值本身打出来——这一行会进容器日志，日志还会被采集走"
    exit 1
  fi

  echo "✅ SSE-C 加密已启用，主钥长度验证通过（提醒：主钥一旦用过就不可轮换）"
else
  ALLOW_PLAINTEXT=\$(printf '%s' "\${PB_ALLOW_PLAINTEXT_MEDIA:-}" | tr -d '[:space:]' | tr 'A-Z' 'a-z')
  case "\$ALLOW_PLAINTEXT" in
    1|true|yes|on)
      echo "⚠️  对象明文入库（PB_S3_OBJECT_ENCRYPT=\$OBJECT_ENCRYPT），已用 PB_ALLOW_PLAINTEXT_MEDIA 显式确认"
      ;;
    *)
      # 拦的判据与 Java 侧同一条：只有"模式确实是明文（none）且逃生阀没开"才拦。
      # 写成"凡不是 sse-c 一律拦"会把 `sse`、`aes256` 这类拼错的值也判死，而服务端那边
      # 对它们另有自己的报错，两处各说一套就等于让容器替配置背一条没发生过的罪。
      if [ "\$OBJECT_ENCRYPT" != "none" ]; then
        echo "ℹ️  PB_S3_OBJECT_ENCRYPT=\$OBJECT_ENCRYPT（这一层只认 none 与 sse-c，其余取值交给服务端校验）"
      elif [ "\$STORAGE_TYPE" = "local" ]; then
        echo "⚠️  图片与头像会明文落在 \$MEDIA_DIR（PB_S3_OBJECT_ENCRYPT=\$OBJECT_ENCRYPT）。"
        echo "   本机盘上的明文靠的是文件权限 600 和这块盘本身；要密文就设"
        echo "   PB_S3_OBJECT_ENCRYPT=sse-c 并配 PB_S3_MASTER_KEY（openssl rand -base64 32，用过不可轮换）。"
      else
        echo "❌ 错误: 生产环境不允许对象存储不加密"
        echo "   请设 PB_S3_OBJECT_ENCRYPT=sse-c 并配 PB_S3_MASTER_KEY（openssl rand -base64 32）；"
        echo "   确实要明文（例如内网试用）再显式设 PB_ALLOW_PLAINTEXT_MEDIA=1"
        exit 1
      fi
      ;;
  esac
fi

# ---- 4. Web 管理台：两把钥，默认关闭 ----
# 留空 = 管理台整个不开放（AdminAuthController 对空 pb.admin.secret 直接 403「管理台未启用」），
# 所以"什么都不填"是一个安全状态，不是"忘了配"；频道与密钥的日常管理在 Android/PC 客户端里也有。
# 根密钥决定"谁能登录"，签名密钥决定"JWT 能不能被伪造"，两把不能是同一把。
if [ -z "\${PB_ADMIN_SECRET:-}" ]; then
  echo "ℹ️  PB_ADMIN_SECRET 未设置，Web 管理台已禁用（客户端收发消息不受影响）。"
  echo "   要用管理台就同时设两项：PB_ADMIN_SECRET=<登录口令>、PB_ADMIN_JWT_SECRET=<另一个强随机值>，"
  echo "   各用 openssl rand -base64 32 生成。"
else
  ADMIN_LEN=\${#PB_ADMIN_SECRET}
  if [ \$ADMIN_LEN -lt 20 ]; then
    echo "❌ 错误: PB_ADMIN_SECRET 过短 (\$ADMIN_LEN 字符)，至少要用 32 字节的强随机值"
    echo "   生成命令: openssl rand -base64 32"
    exit 1
  fi
  if [ -z "\${PB_ADMIN_JWT_SECRET:-}" ]; then
    echo "❌ 错误: 启用 Web 管理台必须另设 PB_ADMIN_JWT_SECRET（登录票据的 HMAC 签名钥）"
    echo "   生成命令: openssl rand -base64 32"
    exit 1
  fi
  JWT_LEN=\${#PB_ADMIN_JWT_SECRET}
  if [ \$JWT_LEN -lt 32 ]; then
    echo "❌ 错误: PB_ADMIN_JWT_SECRET 过短 (\$JWT_LEN 字符)，至少 32 字节"
    exit 1
  fi
  if [ "\$PB_ADMIN_JWT_SECRET" = "\$PB_ADMIN_SECRET" ]; then
    echo "❌ 错误: PB_ADMIN_JWT_SECRET 不能与 PB_ADMIN_SECRET 同值"
    echo "   一把泄露就得同时换两把；轮换根密钥时不应该顺动作废全部已登录会话"
    exit 1
  fi
  echo "✅ Web 管理台密钥已设置（根密钥 + 独立签名密钥）"
fi

# ---- 5. 出门前把"接下来去哪看"说一遍 ----
# 这一段是给在图形界面里第一次跑起来的人：容器状态是 up 不等于能用，而唯一的外部判据
# （/healthz）默认只放行容器环回，从宿主浏览器打过去是 404（InternalAccessFilter 拒绝时故意
# 不用 403，免得扫描器确认这里有个运维端点）——不写清楚就会被当成部署失败。
ADMIN_STATE="禁用（要启用就设 PB_ADMIN_SECRET + PB_ADMIN_JWT_SECRET）"
[ -n "\${PB_ADMIN_SECRET:-}" ] && ADMIN_STATE="已启用，登录页在根路径 /，口令就是 PB_ADMIN_SECRET 那个值"
echo ""
echo "✅ 配置验证通过，启动 Spring Boot..."
echo "   监听端口   \${PB_SERVER_PORT:-7404}（同一个端口吐 API、WebSocket 与管理台页面）"
echo "   对象存储   \$STORAGE_TYPE"
echo "   管理台     \$ADMIN_STATE"
echo "   验收       浏览器打开 http://<这台机器的IP>:\${PB_SERVER_PORT:-7404}/ ；日志里出现"
echo "              Started PushboxServerApplication 即就绪。Android/PC 注册时填同一个地址。"
echo "   注意       /healthz 与 /actuator 默认只放行容器环回（拒绝时回 404，故意不用 403，"
echo "              免得扫描器确认这里有个运维端点），所以从宿主浏览器打 /healthz 看到 404 不是坏了。"
echo "              要在容器里验收：docker exec <容器名> curl -s http://127.0.0.1:\${PB_SERVER_PORT:-7404}/healthz"
echo "              （镜像里有 curl；mysql 客户端没有，库那侧的排查得在库所在的那台机器上做）"
echo "              要让外部浏览器或监控抓到 /healthz，设 PB_SECURITY_INTERNAL_CIDRS=<这套网络分到的子网>。"

# 执行原始入口点
exec java -XX:MaxRAMPercentage=75 -jar /app/app.jar
EOF

RUN chmod +x /app/entrypoint.sh

# 非 root 运行；/app/logs 归运行用户，compose 把 named volume server_logs 挂在这里。
# PB_STORAGE_TYPE=local 时对象落在 /app/data/media（见 application-prod.yml）。这个目录要在镜像里
# 就存在并归 pushbox：named volume 首次挂载时会把镜像里那个目录的属主与内容拷进去，所以
# - server_media:/app/data/media 一挂就是可写的；反之若用宿主绑定挂载，属主跟着宿主目录走，
# root 建的目录 uid 10001 写不动——entrypoint 会当场判死并给出两种修法，不留到第一条带图的消息。
# 完全不挂卷也能起（对象落容器可写层），但 docker rm 之后图片全没，这是 local 拓扑的代价不是缺陷。
RUN useradd -r -u 10001 pushbox && mkdir -p /app/logs /app/data/media && chown -R pushbox:pushbox /app

# ---- 烘焙的默认值：全是非凭据的拓扑参数，每一个都能被同名环境变量覆盖 ----
# 这一段的用途是"让人只填真正只有他自己知道的东西"：库地址、库口令。其余项在 NAS 表单里
# 根本不该出现，出现了也只会被填错。口令类一律不进这个 ENV 段，连空占位都不给：
#   - 空占位没有运行时作用（`${VAR:-}` 与 unset 同解，Spring 那侧也一样落到"未配置"），
#   - 但它会让 docker build 每轮都报 `SecretsUsedInArgOrEnv`——这 lint 管的就是"把秘密烙进镜像层"，
#     而烙进层里的东西删不掉：镜像一旦推出去，改一个 ENV 就得重新出 tag。留两行"看着无害"的
#     占位，等于给下一个想省事的人留了一条"那就顺手填个值"的路。
#   - `PB_ADMIN_SECRET` 留空 = 管理台禁用（AdminAuthController 对空 secret 直接 403「管理台未启用」），
#     所以"镜像里没有这两个键"本身就是一个安全状态，不是漏配。
# 与 compose 的分工：compose 显式传 PB_STORAGE_TYPE=s3 等值，会覆盖这里的 local——
# 图形界面单容器要的是自包含，生产那两套拓扑要的是外接存储，两个默认服务于两种人。
ENV SPRING_PROFILES_ACTIVE=prod \
    PB_SERVER_PORT=7404 \
    PB_LOG_DIR=/app/logs \
    PB_DB_HOST=mysql \
    PB_DB_PORT=3306 \
    PB_DB_NAME=pushbox \
    PB_DB_USERNAME=pushbox \
    PB_DB_WAIT_SECONDS=60 \
    PB_STORAGE_TYPE=local \
    PB_STORAGE_LOCAL_PATH=/app/data/media \
    PB_S3_REGION=us-east-1 \
    PB_S3_BUCKET=pushbox \
    PB_S3_PATH_STYLE=true \
    PB_S3_OBJECT_ENCRYPT=none

USER pushbox
# 监听端口是 ${PB_SERVER_PORT:7404}（application-prod.yml），EXPOSE 只是给人看的元数据，
# 写 8080 会让"照着 docker inspect 猜端口"的人连不上——这一行以前就是错的。
EXPOSE 7404
ENTRYPOINT ["/app/entrypoint.sh"]
