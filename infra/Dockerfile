# syntax=docker/dockerfile:1
# §11.2 服务端多阶段构建。构建上下文必须是仓库根（要 core/ + server/ + web-admin/ + 根 pom.xml），
# compose 里写成 context: .. / dockerfile: infra/Dockerfile。
# web-admin SPA 在独立 node 阶段构建后拷进 server 的静态资源目录，最终 fat jar 自带前端，
# 由同一个 Spring Boot 进程在 7404 上同时吐 API、WebSocket 与管理台页面（同源，无需 CORS）。
#
# 这个镜像**不带任何配置**：没有 ENV，没有必填变量，没有占位值。第一次启动时它自己在 7404 上
# 起一个安装页，部署者在浏览器里填库地址、存储与管理台口令，服务端把结果写进
# /app/data/config/pushbox.json 然后接着启动——不重启容器。
# 之所以不再走环境变量：NAS 的「创建容器」表单只能逐行填 ENV，于是"要填几项"变成了
# 十几个键名与占位值的组合，填错任何一项的表现都是容器起来但半死；而一个网页表单能把
# "库连不上"这种错当场说清楚，还能顺手把库建出来。
# 唯一需要人管的只有「存储位置」那一行：把 /app/data 挂到 NAS 的文件夹上，
# 配置、图片、日志就都保住了。

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
# 单测跑在 CI 里把关跨端向量，这里只为出包，跳过避免镜像构建拖慢。
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:21-jre AS run
WORKDIR /app
COPY --from=build /src/server/target/pushbox-server-*.jar app.jar

# 入口只剩一件事：确认数据目录写得动，然后 exec java。
#
# 引号 heredoc（<<'EOF'）是刻意的：不加引号时 BuildKit 会展开 $ 并**吃掉其它所有反斜杠**，
# 上一版这里为了绕开那条规则写了满屏 \$，改一个字就要重新核对镜像里的产物。
# 配置校验全部搬进了安装页与 Java，这个脚本不再需要读任何配置，也就不需要那些转义。
#
# 目录可写这件事必须留在 Java 之前判：Logback 在 Spring 起来之前就要打开 data/logs/pushbox-server.log，
# 写不动的话容器只会吐一段没人读的 "Permission denied" 堆栈。图形界面把 /app/data 指到
# NAS 共享文件夹时最容易撞上——绑定挂载带来宿主目录的属主（通常 root），而容器进程是 uid 10001。
COPY <<'EOF' /app/entrypoint.sh
#!/bin/bash
set -euo pipefail

DATA_DIR=/app/data
mkdir -p "$DATA_DIR" 2>/dev/null || true
if ! ( : > "$DATA_DIR/.write-probe" ) 2>/dev/null; then
  echo "❌ 错误: 数据目录 $DATA_DIR 不可写（容器内进程是 uid 10001）"
  echo "   配置、图片与日志都在这个目录下，写不动容器起不来。"
  echo "   在 NAS 上把它交给运行用户即可："
  echo "     sudo chown -R 10001:10001 <你挂给 /app/data 的那个宿主目录>"
  echo "   或者在「存储位置」里把 /app/data 那一行删掉，让 Docker 建匿名卷（属主继承镜像里的 pushbox）；"
  echo "   代价是 docker rm 之后配置与图片一起没。"
  exit 1
fi
rm -f "$DATA_DIR/.write-probe"

exec java -XX:MaxRAMPercentage=75 -jar /app/app.jar
EOF

RUN chmod +x /app/entrypoint.sh

# 非 root 运行。/app/data 要在镜像里就存在并归 pushbox：named volume 首次挂载时会把镜像里
# 那个目录的属主与内容拷进去，所以 - pushbox_data:/app/data 一挂就是可写的；
# 宿主绑定挂载则跟着宿主目录走，root 建的目录 uid 10001 写不动——入口脚本会当场判死并给出修法。
RUN useradd -r -u 10001 pushbox && mkdir -p /app/data && chown -R pushbox:pushbox /app

# 一个持久化目录装下配置、图片与日志。飞牛/群晖的「创建容器」表单会把 VOLUME 渲染成「存储位置」行：
# 没有这一句，那一栏根本没有行可填。必须放在上面那条 mkdir/chown 之后——VOLUME 之后再改同一路径的
# RUN 不会进镜像层。
VOLUME ["/app/data"]

USER pushbox
# 同一个端口吐 API、WebSocket、管理台与安装页。EXPOSE 只是给人看的元数据，但写错了会让
# "照着 docker inspect 猜端口"的人连不上。
EXPOSE 7404
ENTRYPOINT ["/app/entrypoint.sh"]
