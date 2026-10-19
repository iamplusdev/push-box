# syntax=docker/dockerfile:1
# §11.2 服务端多阶段构建。构建上下文必须是仓库根（要 core/ + server/ + web-admin/ + 根 pom.xml），
# compose 里写成 context: .. / dockerfile: deploy/Dockerfile。
# D27 后 Java 侧走 Maven，所以基础镜像用 maven 而不是设计早期草稿里的 gradle。
# web-admin SPA 在独立 node 阶段构建后拷进 server 的静态资源目录，最终 fat jar 自带前端，
# 由同一个 Spring Boot 进程在 8080 上同时吐 API 与页面（同源，无需 CORS/额外反代规则）。

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
if [ -z "\${PB_DB_URL:-}" ]; then
  echo "❌ 错误: PB_DB_URL 未设置"
  exit 1
fi

if [ -z "\${PB_DB_PASSWORD:-}" ]; then
  echo "❌ 错误: PB_DB_PASSWORD 未设置"
  exit 1
fi

if [ -z "\${PB_S3_ENDPOINT:-}" ]; then
  echo "❌ 错误: PB_S3_ENDPOINT 未设置"
  exit 1
fi

if [ -z "\${PB_S3_ACCESS_KEY:-}" ]; then
  echo "❌ 错误: PB_S3_ACCESS_KEY 未设置"
  exit 1
fi

if [ -z "\${PB_S3_SECRET_KEY:-}" ]; then
  echo "❌ 错误: PB_S3_SECRET_KEY 未设置"
  exit 1
fi

# 2. SSE-C 加密模式下的主钥长度验证
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

# 3. Web 管理台：根密钥决定"谁能登录"，签名密钥决定"JWT 能不能被伪造"，两把不能是同一把。
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

# 非 root 运行；/app/logs 归运行用户，compose 把 ./data/logs 挂在这里。
RUN useradd -r -u 10001 pushbox && mkdir -p /app/logs && chown -R pushbox:pushbox /app
USER pushbox
EXPOSE 8080
# prod 不加载 application-dev.yml（那套写死了 127.0.0.1），凭据与地址全部来自 env。
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["/app/entrypoint.sh"]
