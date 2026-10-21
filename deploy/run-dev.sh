#!/usr/bin/env bash
# 本机启动服务端（§11.1 D23：服务端不进容器，直连本机 MySQL；图片按 PB_STORAGE_TYPE 落盘或进桶）。
# 口令由 local.env 的 export 带进来，本脚本不出现任何明文。
set -euo pipefail
cd "$(dirname "$0")/.."

# 服务端需要 JDK 21，机器默认 JAVA_HOME 指向 JDK 8，所以不吃环境变量、直接按绝对路径调用。
JAVA_HOME="${PB_JAVA_HOME:-D:/Apps/DE/Java/jdk-21.0.8}"

source deploy/env/local.env

# 按通配取 jar，别把版本号写死在这里：pom 一改，这条启动命令就会"找不到文件"，
# 而报错长得像"你没构建"，实际是版本号漂移。
jar="$(ls server/target/pushbox-server-*.jar 2>/dev/null | head -n 1 || true)"
if [ -z "$jar" ]; then
  echo "server/target/ 下没有 pushbox-server-*.jar —— 先跑 mvn -B -DskipTests package" >&2
  exit 1
fi

exec "$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 \
  -jar "$jar" "$@"
