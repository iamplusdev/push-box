#!/usr/bin/env bash
# 本机启动服务端（§11.1 D23：服务端不进容器，直连本机 MySQL）。
# 配置来自 server/data/config/pushbox.json —— 服务端已经不读环境变量了。
# 那个文件不在仓库里（.gitignore 排掉了 /server/data/），首次跑会看到安装页：
#   浏览器打开 http://localhost:7404/ 填一遍，或者直接跑 mvn -pl server -am test 之外的那条
#   （测试用的也是同一份文件，见 dev.pushbox.server.test.TestConfig）。
set -euo pipefail
cd "$(dirname "$0")/../server"

# 服务端需要 JDK 21，机器默认 JAVA_HOME 指向 JDK 8，所以不吃环境变量、直接按绝对路径调用。
JAVA_HOME="${PB_JAVA_HOME:-D:/Apps/DE/Java/jdk-21.0.8}"

# 工作目录必须是 server/：RuntimeConfig 的路径是相对工作目录的 data/config/pushbox.json，
# 而 `mvn -pl server -am test` 的工作目录也是 server/ —— 两边指同一个文件，
# 本机跑起来看到的配置才是测试看到的那一份。
# 按通配取 jar，别把版本号写死在这里：pom 一改，这条启动命令就会"找不到文件"，
# 而报错长得像"你没构建"，实际是版本号漂移。
jar="$(ls target/pushbox-server-*.jar 2>/dev/null | head -n 1 || true)"
if [ -z "$jar" ]; then
  echo "server/target/ 下没有 pushbox-server-*.jar —— 先跑 mvn -B -DskipTests package" >&2
  exit 1
fi

exec "$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 \
  -jar "$jar" "$@"
