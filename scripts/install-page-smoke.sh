#!/usr/bin/env bash
# 安装页冒烟：真起一次 fat jar，验五件只有"跑起来的进程"才验得到的事。
#
#   ① 没有配置文件时安装页真的起得来（端口、页面资源、注入的监听端口都在）；
#   ② 未配置时除了安装页都 302 回 `/`（同端口复用的那条判据，而不是 404）；
#   ③ 写配置的 POST 要一次性安装口令：不带口令 401，口令在启动日志里；
#   ④ 表单校验先于任何网络（库地址空着就直接被点名，不会去连）；
#   ⑤ 连不上库时**一个字节都不落盘**，且错误是"连不上"而不是"找不到驱动"。
#
# 为什么单测照不出 ⑤：`DriverManager` 初始化时用**系统类加载器**跑 ServiceLoader，
# 而 Spring Boot 把 mysql-connector 放在 `BOOT-INF/lib/` 下，只有 LaunchedClassLoader
# 看得见——于是安装页（它在 Spring 之前跑）直接 getConnection 会报
# "No suitable driver found"。`mvn test` 与 IDE 跑的都是 exploded classpath，
# 系统类加载器本来就看得见驱动，所以 InstallServerTest 全绿也照样漏掉这一条。
#
# 用法:
#   bash scripts/install-page-smoke.sh                       # 自己找 apps/server/target 下最新的 jar
#   bash scripts/install-page-smoke.sh path/to/app.jar
#   SMOKE_PORT=7499 bash scripts/install-page-smoke.sh       # 换端口（默认 7499）
#
# 单独跑时最好带上 JAVA_HOME（fat jar 是 release=21 编的，本机 PATH 上的 java 是 JDK 8）：
#   JAVA_HOME=/d/Apps/DE/Java/jdk-21.0.8 bash scripts/install-page-smoke.sh
#
# 不需要 MySQL、不需要桶、不碰仓库里的任何 data/ 目录：配置文件指到 mktemp 出来的临时目录，
# 而且后两条判据走的是"失败路径"，本来就一个字节都不该写。

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${SMOKE_PORT:-7499}"
JAR="${1:-}"

pass_n=0; fail_n=0
pass() { echo "  ✅ $1"; pass_n=$((pass_n + 1)); }
fail() { echo "  ❌ $1"; fail_n=$((fail_n + 1)); }

if [ -z "$JAR" ]; then
    JAR="$(ls -1t "$ROOT_DIR"/apps/server/target/pushbox-server-*.jar 2>/dev/null | head -1)"
fi
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
    echo "❌ 找不到 jar：$JAR"
    echo "   先出包：JAVA_HOME=<jdk21> mvn -B -pl :pushbox-server -am -DskipTests package"
    exit 1
fi
echo "安装页冒烟：$JAR（端口 $PORT）"

if ! command -v curl >/dev/null 2>&1; then
    echo "❌ 没有 curl，跑不了这条冒烟"; exit 1
fi
# 端口被占会让下面所有判据都变成"连不上"，那是在判别人的进程。先确认它是空的。
if curl -s -o /dev/null --max-time 2 "http://127.0.0.1:$PORT/" 2>/dev/null; then
    echo "❌ 端口 $PORT 已经有东西在听。换个端口：SMOKE_PORT=别的 bash $0"
    exit 1
fi

WORK="$(mktemp -d)"
CONFIG="$WORK/pushbox.json"
LOG="$WORK/jar.log"
PID=""

cleanup() {
    if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
        kill "$PID" 2>/dev/null || true
        # 只等自己叫起来的这一个；java 退出后 HttpServer 的线程跟着走。
        for _ in $(seq 1 20); do kill -0 "$PID" 2>/dev/null || break; sleep 0.5; done
        kill -9 "$PID" 2>/dev/null || true
    fi
    [ -n "${WORK:-}" ] && [ -d "$WORK" ] && rm -rf "$WORK"
}
trap cleanup EXIT

# 库地址指到一个必然拒连的端口：要的就是"驱动找到了、连接失败了"这个组合。
PAYLOAD_UNREACHABLE='{"port":'"$PORT"',"database":{"host":"127.0.0.1","port":1,"name":"pushbox","username":"pushbox","password":"x"},"storage":{"s3":false,"localPath":"data/media","encrypted":false,"passphrase":"","masterKey":""},"admin":{"password":"","jwtKey":""},"security":{"internalCidrs":[]}}'
# 库地址留空：这条必须在连网之前就被 validate() 拦下。
PAYLOAD_INVALID='{"port":'"$PORT"',"database":{"host":"","port":3306,"name":"pushbox","username":"pushbox","password":"x"},"storage":{"s3":false,"localPath":"data/media","encrypted":false,"passphrase":"","masterKey":""},"admin":{"password":"","jwtKey":""},"security":{"internalCidrs":[]}}'

# 挑一个能跑 release=21 fat jar 的 java：本机 PATH 与默认 JAVA_HOME 都指向 JDK 8，
# 直接 `java -jar` 会 UnsupportedClassVersionError，而症状是"60 秒没起来"加一屏堆栈。
# release-server.sh 会先把 JDK 21 塞进 PATH，但这个脚本的用法注释写着可以单独跑，那就自己认 JAVA_HOME。
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
if [ -z "$JAVA_BIN" ] || [ ! -x "$JAVA_BIN" ]; then
    JAVA_BIN="$(command -v java || true)"
fi
if [ -z "$JAVA_BIN" ]; then
    echo "❌ 找不到 java：PATH 里没有，JAVA_HOME 也没设"; exit 1
fi
JAVA_VER="$("$JAVA_BIN" -version 2>&1 | head -1)"
case "$JAVA_VER" in
    *'"1.'*) JAVA_MAJOR="${JAVA_VER#*\"1.}" ;;
    *)       JAVA_MAJOR="${JAVA_VER#*\"}" ;;
esac
JAVA_MAJOR="${JAVA_MAJOR%%.*}"
if [ "${JAVA_MAJOR:-0}" -lt 21 ] 2>/dev/null; then
    echo "❌ $JAVA_BIN 是 Java ${JAVA_MAJOR:-?}，跑不动 release=21 的 fat jar"
    echo "   换 JDK 21：JAVA_HOME=/d/Apps/DE/Java/jdk-21.0.8 bash $0"
    exit 1
fi

"$JAVA_BIN" -jar "$JAR" --server.port="$PORT" --pb.config-file="$CONFIG" >"$LOG" 2>&1 &
PID=$!

ready=0
for _ in $(seq 1 120); do
    if curl -sf -o /dev/null --max-time 2 "http://127.0.0.1:$PORT/" 2>/dev/null; then ready=1; break; fi
    kill -0 "$PID" 2>/dev/null || break
    sleep 0.5
done

if [ "$ready" != 1 ]; then
    fail "安装页 60 秒内没起来（判据无从谈起）。进程日志末尾："
    tail -25 "$LOG" | sed 's/^/      /'
    echo; echo "结果：0 通过 / 1 失败"; exit 1
fi

# 一次性安装口令只打在启动横幅里，后续所有写配置的 POST 都要带上它。
# 只按 32 位 hex 抓，不按中文抓：本机 Windows 控制台可能把横幅写成 GBK，
# 但口令本身是 ASCII，两种编码下都一样（横幅里口令行先于配置路径行，head -1 取到的就是它）。
TOKEN="$(grep -o '[0-9a-f]\{32\}' "$LOG" | head -1)"
if [ -z "$TOKEN" ]; then
    fail "启动日志里没有安装口令（或格式不是 32 位 hex）"
    echo; echo "结果：0 通过 / 1 失败"; exit 1
fi

# ① 页面真的在，而且是安装页而不是别的什么 200。
body="$(curl -s --max-time 5 "http://127.0.0.1:$PORT/")"
if grep -q "PushBox 安装" <<<"$body" && grep -q 'id="dbHost"' <<<"$body" \
   && grep -q "name=\"pb-install-port\" content=\"$PORT\"" <<<"$body"; then
    pass "① 安装页起来了，表单字段与真实端口都在（$(wc -c <<<"$body" | tr -d ' ') 字节）"
else
    fail "① 端口 $PORT 上有 200，但不是安装页（缺「PushBox 安装」、dbHost 或注入端口）"
fi

# ② 未配置时只有安装页：管理台的路由一律 302 回 `/`，而不是 404 或一片 200+HTML。
#    同端口复用的前提就是"同一地址在配置前后都能打开"。
redirect="$(curl -s -o /dev/null --max-time 5 -w '%{http_code} %{redirect_url}' "http://127.0.0.1:$PORT/dashboard")"
if [ "$redirect" = "302 http://127.0.0.1:$PORT/" ]; then
    pass "② 未配置时 /dashboard 302 回安装页"
else
    fail "② /dashboard 期望 302 回 /，实际：$redirect"
fi

# ③ 写配置要先过一次性安装口令：不带口令 401；带错的也 401。
code="$(curl -s -o /dev/null --max-time 5 -w '%{http_code}' -X POST "http://127.0.0.1:$PORT/api/v1/setup" \
        -H 'Content-Type: application/json' --data "$PAYLOAD_INVALID")"
if [ "$code" = 401 ]; then
    pass "③ 不带安装口令的 POST 被拒（401）"
else
    fail "③ 不带安装口令期望 401，实际：$code"
fi

# ④ 校验先于网络：库地址空着就该被点名，而不是去连一个空地址。
resp="$(curl -s --max-time 10 -X POST "http://127.0.0.1:$PORT/api/v1/setup" \
        -H "X-Setup-Token: $TOKEN" \
        -H 'Content-Type: application/json' --data "$PAYLOAD_INVALID")"
if grep -q '"ok":false' <<<"$resp" && grep -q '数据库地址不能为空' <<<"$resp"; then
    pass "④ 表单校验先跑：库地址空着当场被点名"
else
    fail "④ 空库地址没被 validate() 拦下，回的是：$resp"
fi

# ⑤ 驱动装得上、连接失败说得清、且不落盘。这一条是本脚本存在的理由。
resp="$(curl -s --max-time 30 -X POST "http://127.0.0.1:$PORT/api/v1/setup" \
        -H "X-Setup-Token: $TOKEN" \
        -H 'Content-Type: application/json' --data "$PAYLOAD_UNREACHABLE")"
if grep -q 'No suitable driver' <<<"$resp"; then
    fail "⑤ fat jar 里没装上 MySQL 驱动（InstallServer.loadMysqlDriver 失效了）：$resp"
elif grep -q '"ok":false' <<<"$resp" && grep -q '连不上数据库' <<<"$resp"; then
    pass "⑤ 驱动找到了、连接失败被说成「连不上数据库」"
else
    fail "⑤ 期望「连不上数据库」，实际回的是：$resp"
fi
if [ -e "$CONFIG" ]; then
    fail "⑤ 连不上库却把配置写盘了（$CONFIG）：半份配置比让人重填一遍更糟"
else
    pass "⑤ 连不上库时一个字节都没落盘"
fi

echo
echo "结果：$pass_n 通过 / $fail_n 失败"
[ "$fail_n" = 0 ] || exit 1
