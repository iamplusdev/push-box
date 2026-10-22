# syntax=docker/dockerfile:1
# §11.2 服务端多阶段构建。构建上下文必须是仓库根（要 core/ + server/ + web-admin/ + 根 pom.xml），
# compose 里写成 context: .. / dockerfile: deploy/Dockerfile。
# D27 后 Java 侧走 Maven，所以基础镜像用 maven 而不是设计早期草稿里的 gradle。
# web-admin SPA 在独立 node 阶段构建后拷进 server 的静态资源目录，最终 fat jar 自带前端，
# 由同一个 Spring Boot 进程在 PB_SERVER_PORT（默认 7404）上同时吐 API 与页面
# （同源，无需 CORS/额外反代规则；SPA 的路由回落见 SpaWebConfig）。

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
COPY server/pom.xml server/
COPY core/src core/src
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

# 启动前验证脚本：检查关键环境变量和密钥格式
COPY <<EOF /app/entrypoint.sh
#!/bin/bash
set -euo pipefail

echo "🔍 验证生产环境配置..."

# 1. 检查必填变量（compose 已通过 :\${VAR:?} 强制，这里做二次确认）
#    报错文案一律自带"下一步做什么"：这个容器是 prod 拓扑唯一的入口，起不来时人只看到一段红字，
#    不会去翻文档；只说"未设置"等于让人自己猜该写在哪、填什么形态。
if [ -z "\${PB_DB_URL:-}" ]; then
  echo "❌ 错误: PB_DB_URL 未设置（JDBC 连接串，必填）"
  echo "   写在 deploy/.env（compose 与本文件都不接受明文地址/口令，.env 已 gitignore）："
  echo "   复用外部 MySQL：PB_DB_URL=jdbc:mysql://<DB_HOST>:3306/pushbox?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8"
  echo "   compose 自带的 mysql 段：主机名换成服务名 mysql，其余参数照抄"
  echo "   三种拓扑的完整取值见 deploy/部署快速参考.md"
  exit 1
fi

if [ -z "\${PB_DB_PASSWORD:-}" ]; then
  echo "❌ 错误: PB_DB_PASSWORD 未设置（库口令，必填）"
  echo "   写在 deploy/.env，生成：openssl rand -base64 24"
  echo "   这一项同时喂给 MySQL 的 pushbox 用户，所以取值以「能连上那台库」为准，不是随便一个字符串"
  exit 1
fi

# 2. 对象存储：只有走 S3 协议才要这三样。PB_STORAGE_TYPE=local（本机试用、单容器自包含部署）
#    时对象直接落在容器文件系统里，一个外部存储都不需要——原来这里无条件要求 PB_S3_*，
#    等于把"一个 docker run 起一套 PushBox"这条路堵死，只能为了过检查塞三个假值。
if [ "\${PB_STORAGE_TYPE:-s3}" = "local" ]; then
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

  # 目录必须真能写。这里当场判死，不等第一条带图的消息：/healthz 的 storage 只校验配置项非空，
  # 既不探端点也不试写（HealthzController.storageConfigValid），而把宿主目录绑定挂载进来时属主是
  # root、容器里的 pushbox(10001) 写不动。照 2026-10-19「健康检查全绿、每条图片消息停在 failed」
  # 那次的教训，这类错只能在启动时出声。
  mkdir -p "\$MEDIA_DIR" 2>/dev/null || true
  if ! ( : > "\$MEDIA_DIR/.entrypoint-write-probe" ) 2>/dev/null; then
    echo "❌ 错误: 本地存储目录 \$MEDIA_DIR 不可写（容器内进程是 uid 10001）"
    echo "   两种修法任选其一："
    echo "     1) compose 用 named volume（推荐）：把 - ./data/media:/app/data/media"
    echo "        换成 - server_media:/app/data/media，并在文件末尾的 volumes: 里补一行 server_media: {}"
    echo "     2) 继续用宿主目录，就把它交给运行用户：sudo chown -R 10001:10001 ./data/media"
    echo "   不挂卷也起得来（对象写容器可写层），但 docker rm 之后图片全没——那是 local 拓扑的代价，不是缺陷"
    exit 1
  fi
  rm -f "\$MEDIA_DIR/.entrypoint-write-probe"
else
  for var in PB_S3_ENDPOINT PB_S3_ACCESS_KEY PB_S3_SECRET_KEY; do
    if [ -z "\${!var:-}" ]; then
      echo "❌ 错误: \$var 未设置（PB_STORAGE_TYPE=\${PB_STORAGE_TYPE:-s3} 时它是必填）"
      echo "   不想外接存储就设 PB_STORAGE_TYPE=local"
      exit 1
    fi
  done
fi

# 3. SSE-C 加密模式下的主钥长度验证
if [ "\${PB_S3_OBJECT_ENCRYPT:-none}" = "sse-c" ]; then
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
  
  echo "✅ SSE-C 加密已启用，主钥长度验证通过"
else
  # 与 application-prod.yml 里的 ProdPlaintextMediaGuard 同一条判据：镜像里也要在启动前挡住，
  # 否则"忘了开加密"要等第一次图片抓取才在服务端日志里冒出来。
  case "\${PB_ALLOW_PLAINTEXT_MEDIA:-}" in
    1|true|TRUE|yes|YES|on|ON)
      echo "⚠️  对象存储明文入库（PB_S3_OBJECT_ENCRYPT=\${PB_S3_OBJECT_ENCRYPT:-none}），已用 PB_ALLOW_PLAINTEXT_MEDIA 显式确认"
      ;;
    *)
      echo "❌ 错误: 生产环境不允许对象存储不加密"
      echo "   请设 PB_S3_OBJECT_ENCRYPT=sse-c 并配 PB_S3_MASTER_KEY（openssl rand -base64 32）；"
      echo "   确实要明文（例如内网试用）再显式设 PB_ALLOW_PLAINTEXT_MEDIA=1"
      exit 1
      ;;
  esac
fi

# 4. Web 管理台：根密钥决定"谁能登录"，签名密钥决定"JWT 能不能被伪造"，两把不能是同一把。
if [ -z "\${PB_ADMIN_SECRET:-}" ]; then
  echo "ℹ️  PB_ADMIN_SECRET 未设置，Web 管理台已禁用"
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

echo "✅ 配置验证通过，启动 Spring Boot..."

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
USER pushbox
# 监听端口是 ${PB_SERVER_PORT:7404}（application-prod.yml），EXPOSE 只是给人看的元数据，
# 写 8080 会让"照着 docker inspect 猜端口"的人连不上——这一行以前就是错的。
EXPOSE 7404
# prod 不加载 application-dev.yml（那套写死了 127.0.0.1），凭据与地址全部来自 env。
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["/app/entrypoint.sh"]
