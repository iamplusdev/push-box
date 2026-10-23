#!/bin/bash
# PushBox 发布前自动化验证脚本
# 用法: bash scripts/release-verify.sh
# 只做静态检查（配置、文档、闸门一致性），不构建、不跑测试，所以没有 --skip-build 之类的开关。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

PASS_COUNT=0
FAIL_COUNT=0
WARN_COUNT=0

# 三处计数都用展开式而不是 ((X++))：后者在 X 为 0 时表达式的值是 0，
# 算术命令就把 0 当"失败"返回，配着上面的 set -e 会让脚本在第一条 PASS 上直接退出。
pass() {
    echo -e "${GREEN}✅ PASS${NC}: $1"
    PASS_COUNT=$((PASS_COUNT + 1))
}

fail() {
    echo -e "${RED}❌ FAIL${NC}: $1"
    FAIL_COUNT=$((FAIL_COUNT + 1))
}

warn() {
    echo -e "${YELLOW}⚠️  WARN${NC}: $1"
    WARN_COUNT=$((WARN_COUNT + 1))
}

section() {
    echo ""
    echo "=========================================="
    echo "📋 $1"
    echo "=========================================="
}

# ========================================
# 1. 版本号自洽检查（分端）
# ========================================
section "检查各端版本号自洽"

# 三条发布链各打各的标签（server-v* / android-v* / windows-v*），版本号也各走各的：
# 只改服务端时打 server-v1.2.0，客户端不必跟着刷号、也不该被连带重建。所以这里不再要求
# 三端同号，只要求每一端在自己会引用的那些地方对得上——服务端是 pom / 两份 compose /
# WSL 速查 / 官网徽章，Android 是 versionName / versionCode，Windows 是 csproj / Inno。
ANDROID_VERSION=$(grep 'versionName' "$ROOT_DIR/apps/android/app/build.gradle.kts" | grep -oP '"[^"]+"' | tr -d '"')
WINDOWS_VERSION=$(grep '<Version>' "$ROOT_DIR/apps/windows/PushBox.Windows/PushBox.Windows.csproj" | grep -oP '[0-9]+\.[0-9]+\.[0-9]+')
INNO_VERSION=$(grep '#define MyAppVersion' "$ROOT_DIR/apps/windows/installer/PushBox.iss" | grep -oP '"[^"]+"' | tr -d '"')
DOCKER_TAG=$(grep -E 'image: (docker\.io/iamplusdev/push-box|pushbox/server):' "$ROOT_DIR/infra/docker-compose.yml" | head -1 | grep -oP '[0-9]+\.[0-9]+\.[0-9]+')
MAVEN_VERSION=$(grep -m1 '<version>' "$ROOT_DIR/pom.xml" | grep -oP '[0-9]+\.[0-9]+\.[0-9]+')
WSL_TAG=$(grep -m1 -E 'image: pushbox/server:' "$ROOT_DIR/infra/docker-compose-wsl.yml" | sed -E 's/^.*://')
WSL_DOC_TAG=$(grep -m1 -oE 'pushbox/server:[0-9]+\.[0-9]+\.[0-9]+' "$ROOT_DIR/infra/WSL测试速查.md" | sed -E 's/^.*://')

echo "服务端:  $MAVEN_VERSION（compose $DOCKER_TAG / WSL compose $WSL_TAG / WSL 文档 $WSL_DOC_TAG）"
echo "Android: $ANDROID_VERSION"
echo "Windows: $WINDOWS_VERSION（Inno $INNO_VERSION）"
echo "（三端版本号互相独立，不要求相等）"

# 服务端：pom 是唯一真值，jar 名、两份 compose 的标签、排障文档里的本地镜像名都跟着它走。
if ! [[ "$MAVEN_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    fail "没从 pom.xml 读出 X.Y.Z 版本号（读到：'$MAVEN_VERSION'）：pom 写法变了就改这条判据的取值方式，别删"
elif [ "$DOCKER_TAG" != "$MAVEN_VERSION" ]; then
    fail "infra/docker-compose.yml 钉的是 :$DOCKER_TAG，pom 是 $MAVEN_VERSION；照 compose 起的人拉不到这一版（跑 bash scripts/set-version.sh --only=server）"
elif [ "$WSL_TAG" != "$MAVEN_VERSION" ]; then
    fail "infra/docker-compose-wsl.yml 钉的是 :$WSL_TAG，pom 是 $MAVEN_VERSION（跑 bash scripts/set-version.sh --only=server）"
elif [ "$WSL_DOC_TAG" != "$MAVEN_VERSION" ]; then
    fail "infra/WSL测试速查.md 里的本地镜像名是 :$WSL_DOC_TAG，pom 是 $MAVEN_VERSION；照文档 buildx 出来的镜像 compose 会找不到（跑 bash scripts/set-version.sh --only=server）"
else
    pass "服务端版本自洽: $MAVEN_VERSION（pom / 生产 compose / WSL compose / WSL 速查）"
fi

# Android / Windows 各自只要求自己是严格 X.Y.Z、且本端两处引用一致。
if [[ "$ANDROID_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    pass "Android versionName 是严格 X.Y.Z: $ANDROID_VERSION"
else
    fail "Android versionName 不是严格 X.Y.Z（读到：'$ANDROID_VERSION'）；set-version.sh 只接受正式版本"
fi

if ! [[ "$WINDOWS_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    fail "PushBox.Windows.csproj 的 <Version> 不是严格 X.Y.Z（读到：'$WINDOWS_VERSION'）"
elif [ "$INNO_VERSION" != "$WINDOWS_VERSION" ]; then
    fail "Inno 的 MyAppVersion=$INNO_VERSION 与 csproj 的 $WINDOWS_VERSION 不一致（跑 bash scripts/set-version.sh --only=windows）"
else
    pass "Windows 版本自洽: $WINDOWS_VERSION（csproj / Inno）"
fi

# Dockerfile 与 run-dev.sh 不许写死 jar 名：pom 的版本号一改，写死的那份就找不到 jar 了
if grep -q 'pushbox-server-\*\.jar' "$ROOT_DIR/infra/Dockerfile" \
    && grep -q 'pushbox-server-\*\.jar' "$ROOT_DIR/infra/run-dev.sh"; then
    pass "Dockerfile 与 run-dev.sh 都按通配取 jar，不吃版本号"
else
    fail "部署脚本里写死了 jar 文件名，版本号一改就找不到（应使用 pushbox-server-*.jar）"
fi

# 官网徽章是第七个自称拥有版本号的地方，而且是人手写的：set-version.sh 一开始不覆盖它，
# 结果 1.1.0 这轮构建配置全对、site/ 七个页面却挂着 1.0.0 —— 公开仓里这种数字不会自己回头修。
# 站点展示的是服务端/产品版本，所以口径是徽章 == pom，不跟 Android/Windows 的号走。
BADGE_PATTERN='([0-9]+\.[0-9]+\.[0-9]+ · 自托管推送|class="ver">[0-9]+\.[0-9]+\.[0-9]+)'
BADGE_VERSIONS=$(grep -hoE "$BADGE_PATTERN" "$ROOT_DIR"/site/*.html | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | sort -u)
BADGE_HITS=$(grep -hoE "$BADGE_PATTERN" "$ROOT_DIR"/site/*.html | wc -l)
if [ "$BADGE_HITS" -lt 6 ]; then
    fail "只认出 $BADGE_HITS 处站点版本徽章（预期至少 6 处）：徽章写法改了，这条判据得跟着改，别删"
elif [ "$BADGE_VERSIONS" = "$MAVEN_VERSION" ]; then
    pass "官网版本徽章与服务端一致: $BADGE_VERSIONS（$BADGE_HITS 处）"
else
    fail "官网徽章里的版本是 $(echo "$BADGE_VERSIONS" | tr '\n' ' ')，与 pom 的 $MAVEN_VERSION 不一致（跑 bash scripts/set-version.sh --only=server）"
fi

# 测试计数是第九个自称拥有单一真相的地方，而且和版本号一样是人手抄的：README、开发指南、
# 测试指南、发布检查清单/SUMMARY 与 CHANGELOG 都写着 "core 38 + server 223"。
# v1.1.0 这轮抓到过四处同时挂着两版之前的 197/553，靠人肉对齐一定会漏，所以现读现比：
# 以 ci.yml 里的基线为唯一真值，把上面这些文件里出现的 (core + server) 求和后比对。
# docs/审计报告.md 不在扫描范围内——它有意把"旧声称值"和"复验值"并排留着当证据，
# 一份文档里同时存在两个数，任何单值判据都会在那里误报。
# site/ 同样不在扫描范围内，而且原因和审计报告不同：官网是给使用者看的，不该出现
# "本项目有多少条单测"这种仓库内部数字（2026-10-22 改版时全部删掉）。把数字留在文档站
# 只会让下一轮改版顺手删掉它、然后把这条闸判成"写法变了"。
CI_BASELINE=$(grep -oE 'if tests < [0-9]+' "$ROOT_DIR/.github/workflows/ci.yml" | grep -oE '[0-9]+' | tail -1)
TEST_COUNT_PATTERN='(core [0-9]+ \+ server [0-9]+|server [0-9]+ \+ core [0-9]+)'
DOC_PATHS=(README.md docs/开发指南.md docs/测试指南.md docs/release/发布检查清单.md docs/release/发布准备报告.md CHANGELOG.md)
COUNT_HITS=$(git -C "$ROOT_DIR" grep -hhoE "$TEST_COUNT_PATTERN" -- "${DOC_PATHS[@]}" | wc -l)
COUNT_FILES=$(git -C "$ROOT_DIR" grep -lE "$TEST_COUNT_PATTERN" -- "${DOC_PATHS[@]}" | wc -l)
DOC_TOTALS=$(git -C "$ROOT_DIR" grep -hhoE "$TEST_COUNT_PATTERN" -- "${DOC_PATHS[@]}" | awk '{print $2 + $5}' | sort -un | tr '\n' ' ')
DOC_TOTALS=${DOC_TOTALS% }
# 覆盖度这道门原来判的是"命中处数 ≥ 10"，而 2026-10-21 那轮实跑正好 10 处、余量 0：
# 任何人把其中一句改成叙述式写法（"这一版实测 273 条"这种，意思完全一样）都会把闸门判红，
# 而红的原因跟"数字腐烂"这件它本来要防的事毫无关系。改成数**带这份计数的文档个数**：
# 一处改写不会掉文件，整种写法被换掉才会——那正是这条判据想抓的东西。
# 2026-10-22 实测（site/ 摘出去之后）：5 个文件带计数（README、两份指南、清单、发布准备报告），
# 所以下限取 4——容许一处改成叙述式，容许不了两处；真的少了两处就该来改这条判据。
if [ -z "$CI_BASELINE" ]; then
    fail "没从 .github/workflows/ci.yml 读到用例基线（这条判据跟着 ci.yml 的「if tests < N」走，改了那边要改这里）"
elif [ "$COUNT_FILES" -lt 4 ]; then
    fail "只有 $COUNT_FILES 个文档还带着「core N + server N」这份用例数（预期至少 4 个）：写法变了或文档被清空了，这条判据得跟着改，别删"
elif [ "$DOC_TOTALS" = "$CI_BASELINE" ]; then
    pass "文档里的 Java 用例总数与 ci.yml 基线一致：$DOC_TOTALS（$COUNT_HITS 处、$COUNT_FILES 个文档）"
else
    fail "文档里的 Java 用例总数是 $DOC_TOTALS，ci.yml 基线是 $CI_BASELINE；不一致的行：$(git -C "$ROOT_DIR" grep -hoE "$TEST_COUNT_PATTERN" -- "${DOC_PATHS[@]}" | sort -u | tr '\n' ' ')"
fi

# C# 侧原来是彻底没闸的：ci.yml 的 windows job 只跑 dotnet test，而它的退出码只回答"有没有红"，
# 用例被静默裁掉（删了 [Fact]、改了分类过滤、整类没被 discover）照样退 0。文档那个数同样是人手抄——
# 2026-10-22 加这条闸的当天就抓到一处现值腐烂：发布准备报告的状态表还挂着 556/556，
# 其余六处在同一天已经改成实测的 554（撤掉「全部/未读/已读」筛选时连带删了 MainModelTest 两条）。
# 唯一真值取 ci.yml 里那道断言的 $CS_BASELINE：改了它就同步改文档，别反过来抄文档。
#
# 判据形状与 Java 那条同源，但有两个刻意的差别，都是为了不在历史证据上误报：
#   ① 只认"关键词同一行里的三位数对"（`554/554`、`303 / 554`），Java 与 C# 两个基线之外的数一律 FAIL；
#   ② 带日期的行整行跳过——`发布检查清单` 的高 DPI 那条把 2026-10-21 那次的 556/556 当证据留着，
#      和 CHANGELOG、docs/审计报告.md 一样属于"有意并排两个数"，任何单值判据在那里只会误报。
# site/ 与 Java 那条同因由排除：官网不写仓库内部用例数（2026-10-22 实测 5 个文件带计数，下限 3 够用）。
CS_CI_BASELINE=$(grep -oE 'CS_BASELINE = [0-9]+' "$ROOT_DIR/.github/workflows/ci.yml" | grep -oE '[0-9]+' | tail -1)
CS_KEYWORD_PATTERN='dotnet test|Windows 测试|C# 单测|C# 协议向量|冒烟命令'
CS_SCAN_PATHS=(README.md docs/开发指南.md docs/测试指南.md docs/Windows开发环境安装教程.md docs/软件设计方案.md docs/release/发布检查清单.md docs/release/发布准备报告.md)
CS_LINES=$(git -C "$ROOT_DIR" grep -nE "$CS_KEYWORD_PATTERN" -- "${CS_SCAN_PATHS[@]}" \
    | grep -vE '2026-1[0-2]-[0-9]{2}' || true)
CS_NUMS=$(printf '%s\n' "$CS_LINES" | grep -oE '[0-9]{3}[[:space:]]*/[[:space:]]*[0-9]{3}' | grep -oE '[0-9]{3}' | sort -un | tr '\n' ' ' || true)
CS_NUMS=${CS_NUMS% }
CS_FILES=$(printf '%s\n' "$CS_LINES" | grep -oE '^[^:]+' | sort -u | wc -l || true)
CS_BAD=$(printf '%s\n' $CS_NUMS | grep -vE "^($CI_BASELINE|$CS_CI_BASELINE)$" | tr '\n' ' ' || true)
CS_BAD_RE=$(printf '%s\n' $CS_BAD | paste -sd'|' - || true)
if [ -z "$CS_CI_BASELINE" ]; then
    fail "没从 .github/workflows/ci.yml 读到 C# 用例基线（这条判据跟着 windows job 的「\$CS_BASELINE = N」走，改了那边要改这里）"
elif [ "$CS_FILES" -lt 3 ]; then
    fail "只有 $CS_FILES 个文档还在写 C# 用例数（预期至少 3 个）：写法变了或计数被删光了，这条判据得跟着改，别删"
elif [ -n "$CS_BAD" ]; then
    fail "文档里出现了基线之外的 C#/Java 用例数：$CS_BAD（Java 基线 $CI_BASELINE、C# 基线 $CS_CI_BASELINE）；不一致的行：$(printf '%s\n' "$CS_LINES" | grep -E "(^|[^0-9])($CS_BAD_RE)([^0-9]|$)" | cut -c1-160 | tr '\n' ' ')"
else
    pass "文档里的 C# 用例数与 ci.yml 基线一致：$CS_CI_BASELINE（现值 $CS_NUMS，$CS_FILES 个文档）"
fi
# 这条闸不能是空转的：如果没有任何一处文档真的写着 C# 基线那个数，上面只会"全都对"却没在比东西。
if [ -n "$CS_CI_BASELINE" ] && ! printf '%s\n' $CS_NUMS | grep -qx "$CS_CI_BASELINE"; then
    fail "C# 计数闸空转：没有任何一处文档写着基线值 $CS_CI_BASELINE（命中数：$CS_NUMS）"
fi

# 镜像体积只在真构建过之后才知道（v1.1.0 实测 654 MB）。清单里不再单独列这一条，
# 阈值由本脚本自己定义，所以这里只报实测数、不判失败：
# "报了数"和"没人报"差别很大——后者会让下一个发版的人继续勾一条他以为过了的勾。
IMAGE_REF=$(grep -m1 -E 'image: docker\.io/' "$ROOT_DIR/infra/docker-compose.yml" | awk '{print $2}')
# 本机日常构建落的是 pushbox/server:<版本> 这个名字（compose 的 image: 只在拉 CI 产物时才用得上），
# 所以两个名字都要试，否则脚本在开发机上永远报"判不了"，等于没加这条。
LOCAL_REF="$IMAGE_REF"
if ! docker image inspect "$LOCAL_REF" >/dev/null 2>&1; then
    LOCAL_REF="pushbox/server:$MAVEN_VERSION"
fi
if ! command -v docker >/dev/null 2>&1; then
    warn "本机没有 docker CLI，镜像体积判不了（在 WSL 里跑这个脚本，或 docker image inspect $IMAGE_REF --format '{{.Size}}'）"
elif ! docker image inspect "$LOCAL_REF" >/dev/null 2>&1; then
    warn "本地既没有 $IMAGE_REF 也没有 pushbox/server:$MAVEN_VERSION，体积判不了；先起一次 compose 让它编出来"
else
    IMAGE_BYTES=$(docker image inspect "$LOCAL_REF" --format '{{.Size}}')
    IMAGE_MB=$((IMAGE_BYTES / 1048576))
    if [ "$IMAGE_MB" -le 500 ]; then
        pass "镜像体积 ${IMAGE_MB} MB（$LOCAL_REF，阈值 500 MB 定义在本脚本里）"
    else
        warn "镜像体积 ${IMAGE_MB} MB（$LOCAL_REF），超过本脚本的 500 MB 阈值——要么换基底/上 Spring 分层把体积压下来，要么接受现状并在部署文档里写清镜像体量"
    fi
fi

# ========================================
# 2. 密钥配置检查
# ========================================
section "检查生产环境密钥配置"

# 配置模型换过一次（2026-10-22）：服务端不再读任何环境变量，全部配置来自安装页写出的
# data/config/pushbox.json（容器里是 /app/data/config/pushbox.json）。原来那一整片
# "compose 透传 + 镜像 ENV 占位值 + entrypoint 逐项判死"的判据因此整体作废——
# 它们守的是一个已经不存在的入口，留着只会把新设计判红、把真缺陷放过去。
# 换成守新入口的四条：① 服务端一侧再没有任何 PB_* 环境变量（否则那些键是死开关，填了不生效）；
# ② 镜像里不再烘焙任何配置（连占位值都不该有）；③ 配置真的能到 Spring；④ 安装页与它的校验真的在。
#
# ① 只扫**取值行**（剥掉注释）：主 compose 里那段注释掉的拓扑 B 仍然合法地给 mysql/rustfs
#    传 PB_DB_PASSWORD 之类的值——那是第三方镜像自己的口径，不是服务端的。
#    PB_CADDY_HOST / PB_HTTP_PORT 同样排除：一个给 Caddy 容器，一个只是宿主侧端口映射。
SERVER_ENV_HITS=$(sed 's/#.*//' "$ROOT_DIR/infra/docker-compose.yml" "$ROOT_DIR/infra/docker-compose.nas.yml" \
    | grep -oE '\$\{?PB_[A-Z0-9_]+' | grep -vE 'PB_CADDY_HOST|PB_HTTP_PORT' | sort -u | tr '\n' ' ' || true)
YML_ENV_HITS=$(grep -oE '\$\{PB_[A-Z0-9_]+' "$ROOT_DIR/apps/server/src/main/resources/application.yml" | sort -u | tr '\n' ' ' || true)
if [ -n "$SERVER_ENV_HITS$YML_ENV_HITS" ]; then
    fail "服务端一侧还在读环境变量：compose [$SERVER_ENV_HITS] application.yml [$YML_ENV_HITS] —— 这些键现在没人消费，是死开关"
else
    pass "compose 与 application.yml 里没有服务端环境变量（配置只有安装页那一个入口）"
fi

# ② 镜像里一个 ENV 都不该有。留一个"看起来无害"的默认值，就等于给下一个人留了一条
#    "那就顺手填个值"的路，而镜像层里的东西删不掉：推出去之后改一个 ENV 就得重出 tag。
if grep -qE '^ENV ' "$ROOT_DIR/infra/Dockerfile"; then
    fail "Dockerfile 里又有 ENV 段了：镜像应当不带任何配置，第一次启动由安装页把配置写进 /app/data"
else
    pass "镜像不烘焙任何配置（没有 ENV，也就没有占位值与\"填错一半\"的现场）"
fi

# ③ 配置到 Spring 的唯一通路是 EnvironmentPostProcessor，而它只有在 spring.factories 里登记过才会被调用。
#    漏掉登记的症状不是报错，是"安装页填的值一个都没生效，进程按 application.yml 的默认值跑"。
if [ -f "$ROOT_DIR/apps/server/src/main/resources/META-INF/spring.factories" ] \
    && grep -q 'RuntimeConfigEnvironmentPostProcessor' "$ROOT_DIR/apps/server/src/main/resources/META-INF/spring.factories"; then
    pass "RuntimeConfigEnvironmentPostProcessor 已登记进 spring.factories（安装页的值真的会到 Spring）"
else
    fail "spring.factories 没登记 RuntimeConfigEnvironmentPostProcessor：配置文件写了也没人读，进程会按 yml 默认值静默跑起来"
fi

# ④ 安装页三件套：迷你服务、它的页面资源、以及"页面里不再出现环境变量名"。
#    最后一条是防止文档腐烂进 UI——页面上写着 PB_DB_PASSWORD，而那个键已经没人读了。
if [ -f "$ROOT_DIR/apps/server/src/main/java/dev/pushbox/server/setup/InstallServer.java" ] \
    && [ -f "$ROOT_DIR/apps/server/src/main/resources/setup/index.html" ]; then
    if grep -q 'PB_' "$ROOT_DIR/apps/server/src/main/resources/setup/index.html"; then
        fail "安装页里出现了 PB_* 键名：那些环境变量已经没人消费，页面上写着等于教人填一个死开关"
    else
        pass "安装页与它的服务都在位，且页面里不含任何 PB_* 键名"
    fi
else
    fail "缺安装页（setup/InstallServer.java 或 resources/setup/index.html）：没有它，镜像第一次启动就无从配置"
fi

# 守卫类随这套模型一起删掉了：它们的判据（"prod 不许明文对象"、"local 模式却带着 PB_S3_*"）
# 现在由安装页的表单本身表达——不勾就是 local，勾了就必须填全，没有"配了一半"这种状态可守。
# 文件回来了就说明有人把旧模型抄了回来，而旧模型读的键一个都不存在了。
for gone in DevCredentialsGuard ProdPlaintextMediaGuard; do
    if [ -f "$ROOT_DIR/apps/server/src/main/java/dev/pushbox/server/config/$gone.java" ]; then
        fail "$gone 又出现了：它读的是已经删掉的 PB_* 环境变量，留着只会在新模型下静默不生效"
    fi
done
if [ ! -f "$ROOT_DIR/apps/server/src/main/java/dev/pushbox/server/config/DevCredentialsGuard.java" ] \
    && [ ! -f "$ROOT_DIR/apps/server/src/main/java/dev/pushbox/server/config/ProdPlaintextMediaGuard.java" ]; then
    pass "两个环境变量守卫已随旧模型一起删除"
fi

# profile 文件也不该回来：application-{dev,prod}.yml 的全部内容是 ${PB_*} 映射，
# 留着就会有人以为"设个 profile 就能配"，而 SPRING_PROFILES_ACTIVE 现在也没人传了。
for gone in application-dev.yml application-prod.yml; do
    if [ -f "$ROOT_DIR/apps/server/src/main/resources/$gone" ]; then
        fail "$gone 又出现了：它通篇是 \${PB_*} 映射，那些键已经没人消费"
    fi
done
if [ ! -f "$ROOT_DIR/apps/server/src/main/resources/application-dev.yml" ] \
    && [ ! -f "$ROOT_DIR/apps/server/src/main/resources/application-prod.yml" ]; then
    pass "只剩一份 application.yml（没有 profile 分叉，也就没有\"改了 dev 忘了 prod\"）"
fi

# 镜像里那份 /app/entrypoint.sh 与仓库里这段 heredoc 现在**逐字相同**：Dockerfile 用的是带引号的
# `COPY <<'EOF'`，BuildKit 对这种形态既不展开 `$` 也不吃反斜杠。上一版用的是不带引号的 `<<EOF`，
# 于是"反斜杠 + 美元符"变成运行时变量引用、**其它反斜杠被直接吃掉**——2026-10-22 就靠这个吃掉了一个
# case 模式里的转义括号，容器起来 1 秒 `syntax error near unexpected token`，而当时手上那 25 条场景
# 矩阵全绿（矩阵跑的是"只把 \$ 换成 $"的渲染副本，恰好把出错那行还原成了合法写法）。
# **本地渲染 != 构建产物**这件事只靠人记住一定会漏，所以换成带引号的 heredoc 把整类问题消掉，
# 判据也跟着简化成两条，都不需要 docker：
#   ① 不许再出现 `\$`（那是给不带引号的 heredoc 用的写法，带引号之后会原样落进镜像变成字面反斜杠）；
#   ② 原样取出就得过 `bash -n`（不再有"还原规则"，也就没有"还原规则写错了"这类失效）。
if ! grep -q "^COPY <<'EOF' /app/entrypoint.sh$" "$ROOT_DIR/infra/Dockerfile"; then
    fail "Dockerfile 的 entrypoint heredoc 不是带引号的 <<'EOF'：不加引号时 BuildKit 会展开 \$ 并吃掉反斜杠，镜像里的脚本与仓库里读到的不是同一份"
fi
EP_HEREDOC=$(awk '/^COPY <<.EOF. \/app\/entrypoint\.sh$/{f=1;next} f && /^EOF$/{exit} f' "$ROOT_DIR/infra/Dockerfile")
if [ -z "$EP_HEREDOC" ]; then
    fail "没从 infra/Dockerfile 里取出 entrypoint 的 heredoc（那段的路径标记变了，这条闸和镜像一起失守）"
else
    if printf '%s' "$EP_HEREDOC" | grep -q '[\\]\$'; then
        fail "entrypoint 里还有转义美元符：带引号的 heredoc 会把它原样送进镜像，脚本里就成了一个字面反斜杠"
    else
        pass "entrypoint 里没有转义美元符（带引号的 heredoc 逐字进镜像）"
    fi
    EP_TMP=$(mktemp)
    printf '%s\n' "$EP_HEREDOC" > "$EP_TMP"
    if bash -n "$EP_TMP" 2>/dev/null; then
        pass "镜像里的 /app/entrypoint.sh 过 bash -n（容器不会 1 秒退出）"
    else
        fail "entrypoint 语法就不对：镜像里的 /app/entrypoint.sh 会让容器启动即退出。$(bash -n "$EP_TMP" 2>&1 | head -2 | tr '\n' ' ')"
    fi
    rm -f "$EP_TMP"
fi

# NAS 图形界面的「存储位置」直接由镜像的 VOLUME 渲染：少这一句，表单里一行都没有，用户只能靠读文档
# 手动补。收敛成一个 /app/data 之后，表单里只有一行要选、chown 只 chown 一次、备份只指一个目录
# （配置、图片、日志全在里面）。这条只查源码；发布后还要查 Hub 镜像的 config blob（见 CHANGELOG）。
if grep -qE '^VOLUME \["/app/data"\]' "$ROOT_DIR/infra/Dockerfile"; then
    pass "Dockerfile 声明了唯一的 VOLUME /app/data（config + media + logs 同属一处）"
else
    fail "Dockerfile 的 VOLUME 不是单个 /app/data：飞牛/群晖的「存储位置」表单要么是多行、要么是空的"
fi

# 图形界面把 /app/data 指到 NAS 共享文件夹时最容易撞上权限：绑定挂载带来宿主目录的属主（通常 root），
# 而容器进程是 uid 10001。这一层必须留在 Java 之前判——Logback 在 Spring 起来之前就要打开
# data/logs/pushbox-server.log，写不动的话容器只会吐一段没人读的 Permission denied 堆栈。
if grep -q '不可写（容器内进程是 uid 10001）' "$ROOT_DIR/infra/Dockerfile" \
    && grep -q 'chown -R 10001:10001' "$ROOT_DIR/infra/Dockerfile"; then
    pass "entrypoint 在启动 Java 前预检 /app/data 可写，并把 chown 的修法一起说出来"
else
    fail "entrypoint 没预检数据目录：/app/data 绑到 root 属主目录时，容器只会吐 Logback 的 Permission denied 堆栈"
fi

# 上面那条只查了 `^ENV `，但键名还能从别处漏进镜像：entrypoint 里 export、ARG 转 ENV、
# CMD 里 -DPB_xxx。任何一种都会让照 docker inspect 猜配置的人以为"这个键还能填"，
# 而它其实没人读。所以这里查得更死：整个 Dockerfile 里一个 PB_ 都不许出现。
DOCKER_PB_HITS=$(grep -oE 'PB_[A-Z0-9_]+' "$ROOT_DIR/infra/Dockerfile" | sort -u | tr '\n' ' ' || true)
if [ -n "$DOCKER_PB_HITS" ]; then
    fail "Dockerfile 里还有服务端配置键名：$DOCKER_PB_HITS —— 镜像不带配置，这些键现在没人读"
else
    pass "Dockerfile 里不含任何 PB_* 键名（配置的唯一入口是安装页）"
fi

# 校验从 entrypoint 搬进了 InstallServer.validate。少任何一条，安装页就会落盘一份
# "写得进去但跑不起来"的配置，而症状全在启动之后：
#   口令与主钥同时填 → 用哪把无从判断；口令太短 → 图片"加密了"其实等于没加密；
#   勾了对象存储却没填桶 → 图片一路 404；库地址填 127.0.0.1 → 容器连的是它自己。
INSTALL_VALIDATE="$ROOT_DIR/apps/server/src/main/java/dev/pushbox/server/setup/InstallServer.java"
for needle in '加密口令与主钥只能填一个' 'MIN_PASSPHRASE_CODE_POINTS' 'Access Key 与 Secret Key 都要填' '容器里的 127.0.0.1 是容器自己' '管理台口令至少 8 个字符' '运维网段要写成 CIDR'; do
    if grep -q "$needle" "$INSTALL_VALIDATE"; then
        pass "安装页仍守着：$needle"
    else
        fail "安装页丢了这条校验：$needle —— 它现在是配置的唯一入口，少一条就会落盘一份跑不起来的配置"
    fi
done

# 表单填完要真的连通性验过再落盘：install() 的三步（validate → checkDatabase → checkS3）
# 少了后两步，"库地址打错了"要等到 Spring 起来才炸，而那时安装页已经关了、只能改文件重来。
if grep -q 'error = checkDatabase(config)' "$INSTALL_VALIDATE" \
    && grep -q 'error = checkS3(config.getStorage())' "$INSTALL_VALIDATE"; then
    pass "安装页提交前会真连一次库与桶（连不上当场说清，不会落盘一份起不来的配置）"
else
    fail "安装页不再做连通性校验：填错的库地址/桶会先落盘，再在 Spring 启动时才炸，那时安装页已经关了"
fi

if [ -f "$ROOT_DIR/infra/docker-compose.nas.yml" ]; then
    # 只看值那一侧：注释里提到这种写法是解释"为什么不用它"，不该把自己判红（第一版没剥注释，
    # 结果被文件头那段说明触发了一条假 FAIL）。
    if sed 's/#.*//' "$ROOT_DIR/infra/docker-compose.nas.yml" | grep -q '\${'; then
        fail "docker-compose.nas.yml 的**取值行**里出现插值：这本文件存在的意义就是\"没有 .env 也能 up\""
    else
        pass "docker-compose.nas.yml 零插值（改字面值就能在 NAS 上 up）"
    fi
else
    fail "缺 infra/docker-compose.nas.yml：图形界面部署又只剩那套 \${VAR:?}"
fi

# prod.env.example 里剩下的键全部只服务第三方容器（mysql/rustfs）与 Caddy/监控，不再有服务端键。
# 判据是通用的那一句："写进模板的每个键都必须有人消费"——旧模型下这条靠三处手写判据分别守
# PB_SERVER_PORT / PB_STORAGE_TYPE / PB_SECURITY_INTERNAL_CIDRS，每加一个键就要再补一条，
# 而漏补的症状都是同一类：容器"起来了"，端口没人听、图片一路 404、留空的准入却放进一整段网段。
# 现在一把梭：模板里每个 ^KEY= 都要在 infra/ 的某本文件里被引用过，否则它就是死开关。
ENV_CONSUMERS=(
    "$ROOT_DIR/infra/docker-compose.yml"
    "$ROOT_DIR/infra/docker-compose.monitoring.yml"
    "$ROOT_DIR/infra/docker-compose-wsl.yml"
    "$ROOT_DIR/infra/Caddyfile"
    "$ROOT_DIR/infra/deploy-wsl.sh"
)
DEAD_ENV_KEYS=""
for key in $(grep -oE '^[A-Z][A-Z0-9_]*=' "$ROOT_DIR/infra/env/prod.env.example" | tr -d '=' | sort -u); do
    if ! grep -rq "$key" "${ENV_CONSUMERS[@]}" 2>/dev/null; then
        DEAD_ENV_KEYS="$DEAD_ENV_KEYS $key"
    fi
done
if [ -n "$DEAD_ENV_KEYS" ]; then
    fail "prod.env.example 里有没人消费的键：$DEAD_ENV_KEYS —— 照模板填了也不生效（服务端配置走安装页，第三方容器的键要在 compose 里被引用）"
else
    pass "prod.env.example 里每个键都有人消费（infra/ 里查得到引用）"
fi

# 服务端专属的键一个都不该再出现在模板里：它们读的入口已经删了，留着就是在教人填死开关。
SERVER_ONLY_KEYS=$(grep -oE '^(PB_DB_URL|PB_DB_HOST|PB_DB_USERNAME|PB_STORAGE_TYPE|PB_S3_ENDPOINT|PB_S3_REGION|PB_S3_BUCKET|PB_S3_OBJECT_ENCRYPT|PB_S3_MASTER_KEY|PB_S3_MASTER_PASSPHRASE|PB_ALLOW_PLAINTEXT_MEDIA|PB_ADMIN_SECRET|PB_ADMIN_JWT_SECRET|PB_SERVER_PORT|PB_SECURITY_INTERNAL_CIDRS)=' \
    "$ROOT_DIR/infra/env/prod.env.example" | tr -d '=' | sort -u | tr '\n' ' ' || true)
if [ -n "$SERVER_ONLY_KEYS" ]; then
    fail "prod.env.example 里还有服务端专属键：$SERVER_ONLY_KEYS —— 这些键已经没人读了，它们的值改由安装页写进 data/config/pushbox.json"
else
    pass "prod.env.example 不含任何服务端专属键（库、存储、管理台口令全部只在安装页填）"
fi

# 检查 entrypoint.sh 是否存在
if [ -f "$ROOT_DIR/infra/Dockerfile" ] && grep -q "entrypoint.sh" "$ROOT_DIR/infra/Dockerfile"; then
    pass "Docker 入口点验证脚本已配置"
else
    fail "Docker 入口点验证脚本缺失"
fi

# application.yml 是最后一道默认值，里面不该再有 ${...} 插值：Spring 对解析不出来的占位符
# 抛的是 Could not resolve placeholder，进程直接退出，而不是"退回默认值"。
if grep -qE '\$\{' "$ROOT_DIR/apps/server/src/main/resources/application.yml"; then
    fail "application.yml 里还有 \${} 插值：没设对应变量时进程会因 Could not resolve placeholder 启动即退出（配置应由 EnvironmentPostProcessor 覆盖）"
else
    pass "application.yml 全是字面默认值（覆盖靠 EnvironmentPostProcessor，不靠占位符）"
fi

# 容器内端口现在写死 7404，它同时被四处引用：安装页的默认值、application.yml、
# Caddyfile 的上游、compose 的端口映射与 healthcheck。改其中一处不改其余的症状是
# "反代 502"或"探活永远失败"，都看不出根因是端口对不上。所以这里把四处绑在一起判。
PORT_REFS_OK=1
grep -q 'reverse_proxy pushbox-server:7404' "$ROOT_DIR/infra/Caddyfile" || PORT_REFS_OK=0
grep -q '"127.0.0.1:7404:7404"' "$ROOT_DIR/infra/docker-compose.yml" || PORT_REFS_OK=0
grep -q '/dev/tcp/127.0.0.1/7404' "$ROOT_DIR/infra/docker-compose.yml" || PORT_REFS_OK=0
grep -qE '^[[:space:]]*port: 7404$' "$ROOT_DIR/apps/server/src/main/resources/application.yml" || PORT_REFS_OK=0
if [ "$PORT_REFS_OK" = "1" ]; then
    pass "容器内端口 7404 四处一致（application.yml / Caddyfile 上游 / compose 映射 / compose 探活）"
else
    fail "7404 在四处引用里对不齐：改容器内端口要同时改 application.yml、Caddyfile、compose 的 ports 与 healthcheck，否则反代 502 或探活永远失败"
fi

# compose 的 ${VAR:?} 插值作用于整本文件，跟 profile 有没有激活无关：
# 监控用的必填项留在主文件里，等于"不开 Grafana 也必须有 Grafana 口令"，up -d 直接解析失败。
# 只认插值形态（\${GRAFANA_ADMIN_PASSWORD...），主文件里那段解释为什么挪走的注释不算命中。
if grep -q '\${GRAFANA_ADMIN_PASSWORD' "$ROOT_DIR/infra/docker-compose.yml"; then
    fail "GRAFANA_ADMIN_PASSWORD 还在主 compose 文件里：只起服务端的部署会在解析阶段就被它卡死"
elif [ -f "$ROOT_DIR/infra/docker-compose.monitoring.yml" ] \
  && grep -q '\${GRAFANA_ADMIN_PASSWORD' "$ROOT_DIR/infra/docker-compose.monitoring.yml"; then
    pass "监控栈独立成一本文件，主文件的必填项只剩真必填的那几个"
else
    fail "找不到 infra/docker-compose.monitoring.yml（或它没有接手 GRAFANA_ADMIN_PASSWORD）：prometheus/grafana 没有落脚的地方"
fi

# 凭据明文会落在这些地方：
#   data/config/pushbox.json —— 新模型下**这是主战场**：安装页写出的那份配置里有库口令、
#     Access/Secret Key、加密口令与主钥，而且它按设计就是明文（用户明确要求不再假装
#     "生产环境没有明文密码"）。工作目录随启动位置变：`mvn -pl :pushbox-server test` 落在 apps/server/data/，
#     直接跑 jar 落在仓库根的 data/，所以两个路径都要挡。
#   infra/env/local.env —— 开发机的凭据（只给 deploy-wsl.sh 与本地测试用）。
#   infra/.env / .env —— compose 的 --env-file，装的是第三方容器与 Caddy 的口令。
#   .idea/ —— IDEA 把运行配置的 Environment variables 写进 workspace.xml。
# 全靠 .gitignore 挡，所以"忽略规则还在"本身就是发布判据：少一行，下一次谁跑了一次安装页
# 就等于把整份凭据提交上去了——而且它删不掉，公开仓的历史里会一直留着。
for pat in 'infra/env/local.env' '^\.env$' '^\.idea/' '^/data/' '^/apps/server/data/'; do
    if grep -qE "$pat" "$ROOT_DIR/.gitignore"; then
        pass ".gitignore 仍然排除 $pat"
    else
        fail ".gitignore 不再排除 $pat —— 这个位置会出现凭据明文，必须让它进不了提交"
    fi
done

# 上一道只判"规则在"，这一道判"规则真的生效"：拿 git 自己问一次这两个目录会不会被收进提交。
# 规则写在 .gitignore 里但被后面的取反规则翻回来、或者路径写错一层，grep 是看不出来的。
for d in data apps/server/data; do
    if [ -d "$ROOT_DIR/$d" ] && [ -n "$(git -C "$ROOT_DIR" status --porcelain --ignored=no -- "$d" 2>/dev/null)" ]; then
        fail "$d/ 里有 git 看得见的文件：$(git -C "$ROOT_DIR" status --porcelain -- "$d" | head -3 | tr '\n' ' ') —— 那份 pushbox.json 装着全部凭据"
    fi
done
if [ -z "$(git -C "$ROOT_DIR" ls-files -- 'data/**' 'apps/server/data/**' 2>/dev/null)" ]; then
    pass "data/ 与 apps/server/data/ 都不在版本控制里（安装页写出的明文凭据进不了提交）"
else
    fail "已经有文件被提交进 data/ 或 apps/server/data/：$(git -C "$ROOT_DIR" ls-files -- 'data/**' 'apps/server/data/**' | head -3 | tr '\n' ' ')"
fi

# 模板、文档、脚本与站点里都不该出现真实主机地址（那本身就是可拼出拓扑的情报）。
# 允许 CIDR 前缀形态（100.64.0.0、192.168.0.0 这类写在网络段说明里的），逐条排除。
SCAN_TARGETS=(
    "$ROOT_DIR/infra/env/prod.env.example"
    "$ROOT_DIR/infra/部署快速参考.md"
    "$ROOT_DIR/infra/docker-compose.nas.yml"
    "$ROOT_DIR/infra/NAS部署指南.md"
    "$ROOT_DIR/infra/docker-compose.yml"
    "$ROOT_DIR/infra/Caddyfile"
    "$ROOT_DIR/infra/backup/backup.sh"
    "$ROOT_DIR/infra/ca/gen-ca.sh"
    "$ROOT_DIR/infra/probe"
    "$ROOT_DIR/.github/workflows"
    "$ROOT_DIR/README.md"
    "$ROOT_DIR/docs"
    "$ROOT_DIR/site"
)
LEAKY=$(grep -rhoE '\b(192\.168\.[0-9]+\.[0-9]+|100\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3})\b' \
    "${SCAN_TARGETS[@]}" 2>/dev/null \
    | grep -vE '^(192\.168\.0\.0|100\.64\.0\.0|100\.100\.100\.100)$' | sort -u || true)
if [ -n "$LEAKY" ]; then
    fail "部署资产/文档/站点里写死了真实地址：$(echo "$LEAKY" | tr '\n' ' ')"
else
    pass "部署模板、文档与站点只用占位符地址"
fi
# 代码与测试夹具用另一套口径：私网样例（192.168.1.10、192.168.4.1 这类）本来就该允许，
# 测试正是靠它们构造"不撞到真实机器"的地址。这里只盯这套部署自己会用到的段——
# Tailscale 的 100.90–100.99（存储机与联调机都在里面），那个才是拼得出拓扑的情报。
# 只扫源码树：bin/obj 里的构建产物是源的副本，匹配到也只是把同一条地址数两遍。
LEAKY_CODE=$(grep -rhoE --binary-files=without-match \
    --exclude-dir='bin' --exclude-dir='obj' --exclude-dir='build' --exclude-dir='node_modules' \
    '\b100\.9[0-9]\.[0-9]+\.[0-9]+\b' \
    "$ROOT_DIR/apps/android/app/src" "$ROOT_DIR/apps/server/src" "$ROOT_DIR/apps/windows/PushBox.Windows" \
    "$ROOT_DIR/apps/windows/PushBox.Crypto.Tests" "$ROOT_DIR/apps/web-admin/src" 2>/dev/null \
    | sort -u || true)
if [ -n "$LEAKY_CODE" ]; then
    fail "代码/测试里出现真实地址：$(echo "$LEAKY_CODE" | tr '\n' ' ')"
else
    pass "代码与测试夹具不含真实地址"
fi

# 反向代理：主机名只从 .env 来，缺证书时的现象已经在 Caddyfile 顶部写清
if grep -q '\$PB_CADDY_HOST' "$ROOT_DIR/infra/Caddyfile" \
    && grep -q 'PB_CADDY_HOST: ' "$ROOT_DIR/infra/docker-compose.yml" \
    && grep -q '^PB_CADDY_HOST=' "$ROOT_DIR/infra/env/prod.env.example"; then
    pass "Caddy 主机名参数化到 PB_CADDY_HOST（改一处即可）"
else
    fail "Caddy 主机名仍需在 Caddyfile 里多处手改，或 .env/compose 没接上 PB_CADDY_HOST"
fi

# 7404 只能绑环回：公网流量必须走 Caddy（TLS、/actuator 屏蔽、请求体上限都在那层）。
# 放开成 "7404:7404" 的代价是明文链路 + 绕过 Caddy 的全部限制，所以这里判死；
# 真要手机直连调试走 docker-compose.nas.yml，那本文件是"局域网自用"的独立拓扑。
if grep -q '"127.0.0.1:7404:7404"' "$ROOT_DIR/infra/docker-compose.yml"; then
    pass "主 compose 只把后端端口绑在环回（公网侧看不到 7404）"
else
    fail "主 compose 没有把 7404 限制在环回：明文链路会绕过 Caddy 的 TLS 与 /actuator 屏蔽"
fi

# ========================================
# 3. Android 构建配置检查
# ========================================
section "检查 Android 构建配置"

# versionCode 必须由 versionName 推出来（scripts/set-version.sh 同一套算法），
# 写死一个更大的数会让正式版 tag 注入后反而变小，用户端 INSTALL_FAILED_VERSION_DOWNGRADED。
ANDROID_CODE=$(grep 'versionCode' "$ROOT_DIR/apps/android/app/build.gradle.kts" | grep -oE '[0-9]+' | head -1)
if [[ "$ANDROID_VERSION" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    EXPECTED_CODE=$(( BASH_REMATCH[1] * 10000 + BASH_REMATCH[2] * 100 + BASH_REMATCH[3] ))
    if [ "$ANDROID_CODE" = "$EXPECTED_CODE" ]; then
        pass "versionCode=$ANDROID_CODE 与 versionName=$ANDROID_VERSION 对得上"
    else
        fail "versionCode=$ANDROID_CODE 应为 $EXPECTED_CODE（versionName $ANDROID_VERSION）"
    fi
else
    fail "versionName 不是 X.Y.Z，无法推导 versionCode（读到：'$ANDROID_VERSION'）"
fi

# release 必须由环境变量注入的 keystore 签名：缺签名要构建失败，
# 而不是安静产出一个 debug 签名的包——用户换版时签名对不上只能卸载重装，历史全丢。
if grep -q 'ANDROID_KEYSTORE_BASE64' "$ROOT_DIR/apps/android/app/build.gradle.kts"; then
    pass "Android release 签名由环境变量驱动"
else
    fail "Android release 没有签名配置"
fi

if grep -rq 'pushbox-debug-' "$ROOT_DIR/.github/workflows/build-apk.yml" \
    "$ROOT_DIR/apps/android/app/src/main/java/cn/iamplus/pushbox/net/UpdateApi.kt"; then
    fail "仍在收集或认可 debug APK 资产"
else
    pass "发布链路与客户端都只认签名 release 包"
fi

# 分端发布后每个平台各占一个 GitHub Release，/releases/latest 只返回"最后发的那个"：
# 谁改回去，另一端的更新检查就会静默地永远看不到新版本（不报错，只是不提示）。
UPDATE_KT="$ROOT_DIR/apps/android/app/src/main/java/cn/iamplus/pushbox/net/UpdateApi.kt"
UPDATE_CS="$ROOT_DIR/apps/windows/PushBox.Windows/Update/UpdateClient.cs"
# 只看真正的 URL 常量（结尾带引号），不看注释里"不能再用 /releases/latest"那句解释。
if grep -q 'releases/latest"' "$UPDATE_KT" "$UPDATE_CS"; then
    fail "两端的更新检查又用回了 /releases/latest：分端 Release 会互相顶掉（改回 releases?per_page=… 列表并按资产挑）"
elif grep -q 'releases?per_page=' "$UPDATE_KT" && grep -q 'releases?per_page=' "$UPDATE_CS"; then
    pass "两端更新检查按资产筛选 Release 列表，分端发布不会互相顶掉"
else
    fail "更新检查没有按资产筛选 Release 列表：分端发布后另一平台会读错 Release"
fi

# 签名证书指纹的单一来源：apps/android/release-cert.sha256 给 build-apk.yml 现读现比，
# 发布检查清单.md 那句"每版比对这一串"必须还是同一串。两处分开腐烂过一次，
# 就变成"CI 验的是 A、人写的是 B"，比没验更坏。
CERT_BASELINE_FILE="$ROOT_DIR/apps/android/release-cert.sha256"
if [ ! -f "$CERT_BASELINE_FILE" ]; then
    fail "缺 apps/android/release-cert.sha256（build-apk.yml 的签名核对没有基线可读）"
else
    CERT_BASELINE=$(tr -d '[:space:]' < "$CERT_BASELINE_FILE")
    if ! printf '%s' "$CERT_BASELINE" | grep -qE '^[0-9a-f]{64}$'; then
        fail "apps/android/release-cert.sha256 不是 64 位小写十六进制（读到：$CERT_BASELINE）"
    elif ! grep -q 'release-cert.sha256' "$ROOT_DIR/.github/workflows/build-apk.yml"; then
        fail "build-apk.yml 没在读 apps/android/release-cert.sha256，签名步退回了只判 debug"
    elif ! grep -q "$CERT_BASELINE" "$ROOT_DIR/docs/release/发布检查清单.md"; then
        fail "docs/release/发布检查清单.md 里的证书指纹与 apps/android/release-cert.sha256（$CERT_BASELINE）不是同一串"
    else
        pass "证书指纹基线单一来源且 CI 与清单一致（$CERT_BASELINE）"
    fi
fi

# 机器专属绝对路径不该进仓库（换一台机器就构建不了）
if grep -q 'installations.paths' "$ROOT_DIR/apps/android/gradle.properties"; then
    fail "apps/android/gradle.properties 写死了本机 JDK 路径"
else
    pass "gradle.properties 不含机器专属路径"
fi

# 检查 R8 是否启用
if grep -q 'isMinifyEnabled = true' "$ROOT_DIR/apps/android/app/build.gradle.kts"; then
    pass "R8 混淆已启用"
else
    fail "R8 混淆未启用"
fi

# 检查 proguard-rules.pro 是否存在
if [ -f "$ROOT_DIR/apps/android/app/proguard-rules.pro" ]; then
    RULES_COUNT=$(wc -l < "$ROOT_DIR/apps/android/app/proguard-rules.pro")
    pass "proguard-rules.pro 存在 ($RULES_COUNT 行规则)"
else
    fail "proguard-rules.pro 缺失"
fi

# 检查 Gradle wrapper URL
WRAPPER_URL=$(grep 'distributionUrl' "$ROOT_DIR/apps/android/gradle/wrapper/gradle-wrapper.properties" | cut -d= -f2)
if [[ "$WRAPPER_URL" == *"services.gradle.org"* ]]; then
    pass "Gradle wrapper 使用官方 CDN"
elif [[ "$WRAPPER_URL" == *"file:///"* ]]; then
    warn "Gradle wrapper 使用本地路径（CI 上会失败）"
else
    fail "Gradle wrapper 配置异常"
fi

# ========================================
# 4. Windows 代码签名检查
# ========================================
section "检查 Windows 代码签名配置"

if grep -q 'CODESIGN_CERT_BASE64' "$ROOT_DIR/.github/workflows/build-msi.yml"; then
    pass "build-msi.yml 包含代码签名步骤"
else
    fail "build-msi.yml 缺少代码签名配置"
fi

if [ -f "$ROOT_DIR/docs/代码签名指南.md" ]; then
    pass "代码签名指南文档存在"
else
    warn "代码签名指南文档缺失"
fi

# ========================================
# 5. CI/CD 工作流检查
# ========================================
section "检查 CI/CD 工作流"

# 判"这个 workflow 有没有真的在构建现场跑版本注入"，只认行首（或 `run:` 之后）的那句 `bash scripts/set-version.sh`。
# 不能写 `grep -q 'set-version.sh'` 就算数：docker-publish.yml 的报错文案里正主让运维去跑这条命令，
# 一把全文件 grep 会把"解释该怎么做"读成"我自己做了"，闸门反而判红。
injects_version() {
    grep -qE '^[[:space:]]*(run:[[:space:]]*)?bash scripts/set-version\.sh' "$1"
}

# 出制品的三个 workflow 对版本号的处理**不再一致，这是故意的**：build-apk / build-msi 仍然在构建
# 现场由 tag 注入，产物自称哪一版跟 tag 走，而 GitHub Release 删了重传得回来；docker-publish 反过来
# ——镜像推上 Hub 收不回来，所以它只**核对**仓库里已提交的那份版本，绝不现场改写。
# 注入还必须限定 --only=<本端>：不带 scope 等于把三端版本号一起刷，解耦就只剩标签这一层。
WORKFLOWS=("build-apk.yml:android" "build-msi.yml:windows")
for entry in "${WORKFLOWS[@]}"; do
    wf="${entry%%:*}"
    scope="${entry#*:}"
    if [ ! -f "$ROOT_DIR/.github/workflows/$wf" ]; then
        fail "$wf 工作流文件缺失"
    elif injects_version "$ROOT_DIR/.github/workflows/$wf"; then
        if grep -qE "bash scripts/set-version\.sh --only=$scope" "$ROOT_DIR/.github/workflows/$wf"; then
            pass "$wf 由 tag 注入版本号（--only=$scope）"
        else
            fail "$wf 注入版本号时没限定 --only=$scope：只改这一端也会把其余两端的号一起刷掉"
        fi
    else
        warn "$wf 可能缺少版本注入步骤"
    fi
done

DP="$ROOT_DIR/.github/workflows/docker-publish.yml"
if [ ! -f "$DP" ]; then
    fail "docker-publish.yml 工作流文件缺失"
elif injects_version "$DP"; then
    fail "docker-publish.yml 还在构建现场改写版本号：那样能推出一个自称 v1.2.3、里面跑着 1.1.0 的镜像，而 Hub 上收不回来"
elif grep -q '版本号一致性闸门' "$DP"; then
    pass "docker-publish.yml 只核对版本、不改写（tag / pom / compose 不一致在构建前就红）"
else
    fail "docker-publish.yml 既没有注入也没有核对闸门：tag、pom、compose 三者对不上时没有任何东西会拦下来"
fi

# 三条链必须各认各的标签：自己的 <component>-v* 加上旧的 v*（三端一起发）。
# 漏掉自己的前缀 = 打 tag 根本没有流水线跑；把别人的前缀写进 tags = "只改服务端"又把客户端带上。
# 两种都是解耦承诺的破口，且在本机看不出来（tag 推上去才知道），所以在这里静态判死。
COMPONENT_TAG_GATES=("build-apk.yml:android-v*" "build-msi.yml:windows-v*" "docker-publish.yml:server-v*")
for entry in "${COMPONENT_TAG_GATES[@]}"; do
    wf="${entry%%:*}"
    want="${entry#*:}"
    file="$ROOT_DIR/.github/workflows/$wf"
    if [ ! -f "$file" ]; then
        fail "$wf 工作流文件缺失（$want 这条链没有落点）"
        continue
    fi
    found=$(grep -hE '^[[:space:]]+tags:' "$file" | grep -oE '(android|windows|server)-v\*' | sort -u | tr '\n' ' ')
    found=${found% }
    if [ "$found" != "$want" ]; then
        fail "$wf 的发布标签是 [$found]，应当只认 [$want]：漏掉自己的前缀打 tag 不跑，混进别人的前缀会连带重建"
    elif ! grep -q '"v\*"' "$file"; then
        fail "$wf 不再接受旧的 v*：三端一起发的老流程会静默不跑（这一条故意保留兼容）"
    else
        pass "$wf 只认 $want 与旧的 v*"
    fi
done

# 文件在磁盘上不等于门禁在跑：.github/ 曾被 .gitignore 挡着，那时四个 workflow 全都只活在本机，
# 推上去的仓库里一个 workflow 都没有，"CI 全绿"其实是"CI 从没运行"。
TRACKED_WF=$(cd "$ROOT_DIR" && git ls-files '.github/workflows/*.yml' 2>/dev/null | wc -l | tr -d ' ')
if cd "$ROOT_DIR" && git ls-files --error-unmatch .github/workflows/ci.yml >/dev/null 2>&1; then
    pass "CI 工作流已入库（$TRACKED_WF 个文件被 git 跟踪）"
else
    fail ".github/workflows/ci.yml 没进 git（.gitignore 还在挡？门禁不会被 GitHub 执行）"
fi

if injects_version "$ROOT_DIR/.github/workflows/ci.yml"; then
    fail "ci.yml 里做了版本注入：门禁应当测仓库里那份代码，改过版本号就不是同一件事了"
else
    pass "ci.yml 不改版本号，测的就是提交里的那份"
fi

# 出制品与门禁的四个 workflow 只在 main / 各自的组件标签 / 旧的 v* 上触发，主分支名本身也是发布链的一部分。
# （第五个 site-deploy.yml 故意不挂 v*，它跟版本号无关，判据在下面那段。）
if grep -q 'branches: \[main\]' "$ROOT_DIR/.github/workflows/ci.yml"; then
    pass "CI 触发分支与主分支口径一致（main）"
else
    fail "CI 的触发分支不是 main，推上去不会有任何流水线运行"
fi

# 官网发布链。site/ 是纯静态的，这条链没有构建，所以它的风险全在"什么时候发、发的是什么"上，
# 而这几件事在本机都看不出来：paths 被删掉只有推 main 那次才暴露，树哈希比较被删掉只有重跑一次
# 才看得见（多一个空 commit 与一次 Pages 构建），.nojekyll 被漏掉则 Pages 会拿 Jekyll 过一遍站点。
SITE_WF="$ROOT_DIR/.github/workflows/site-deploy.yml"
if [ ! -f "$SITE_WF" ]; then
    fail "site-deploy.yml 工作流文件缺失：改了 site/ 也不会有任何东西上线"
elif ! grep -qE '^[[:space:]]+paths:' "$SITE_WF"; then
    fail "site-deploy.yml 的 push 触发没有 paths 过滤：任何一次改代码的推送都会重发官网"
elif ! grep -q 'skip=true' "$SITE_WF"; then
    fail "site-deploy.yml 没有内容比较：workflow_dispatch 不吃 paths 过滤，没这一层每点一次就多一个空 commit"
elif grep -qE '^[[:space:]]*tags:' "$SITE_WF"; then
    fail "site-deploy.yml 挂在 tag 事件上：官网内容与版本号无关，打 v* 标签不该重发站点"
elif grep -oE 'uses: [^ ]+' "$SITE_WF" | grep -qv '^uses: actions/'; then
    fail "site-deploy.yml 引了非官方 action：这条链要往 gh-pages 写数据，第三方 action 是白多出来的供应链入口"
elif ! grep -q 'site/.nojekyll' "$SITE_WF"; then
    fail "site-deploy.yml 不检查 .nojekyll：Pages 以分支为源时会把这份静态站过一遍 Jekyll"
elif [ ! -f "$ROOT_DIR/site/.nojekyll" ]; then
    fail "site/.nojekyll 缺失：上面那条判据会红，补上（内容随意，Pages 只看它在不在）"
else
    pass "site-deploy.yml 护栏齐全（paths 过滤 + 树哈希比较 + 不挂 tag + 只用官方 action + .nojekyll 就位）"
fi

# ========================================
# 6. 文档完整性检查
# ========================================
section "检查文档完整性"

REQUIRED_DOCS=(
    "README.md"
    "LICENSE"
    "CHANGELOG.md"
    "docs/release/发布检查清单.md"
    "docs/交互体验改进总结.md"
    "docs/R8混淆配置指南.md"
    "docs/代码签名指南.md"
)

for doc in "${REQUIRED_DOCS[@]}"; do
    if [ -f "$ROOT_DIR/$doc" ]; then
        SIZE=$(du -h "$ROOT_DIR/$doc" | cut -f1)
        pass "$doc 存在 ($SIZE)"
    else
        fail "$doc 缺失"
    fi
done

# 文档里写到的端点路径，必须真的能在 controller 注解里找到。
# 这一条是 2026-10-20 那轮文档复核逼出来的：infra/WSL测试速查.md 里的
# /api/v1/admin/messages/search 从来没有存在过（真实路径是 GET /api/v1/admin/messages?q=），
# docs/审计报告.md 又用 6 个抽查项写下"所有管理台端点完全一致"——两份文档互相引用，
# 谁也没发现对端是个不存在的地址。写给人看的判据会腐烂，所以这条交给机器跑。
# 判据里不写死"多少个端点"：条数跟着代码与文档一起动，写死就变成下一条会腐烂的断言。
pb_routes_from_code() {
    local f base p
    for f in "$ROOT_DIR"/apps/server/src/main/java/dev/pushbox/server/web/*.java; do
        base=$(grep -oP '^@RequestMapping\("\K[^"]+' "$f" | head -1 || true)
        {
            # 方法级：@GetMapping("/x")，以及 @PostMapping(path = "${pb.ingest.base-path:/message}")
            grep -oP '^\s+@(Get|Post|Put|Delete|Patch)Mapping\(\s*(path\s*=\s*)?"\K[^"]+' "$f" || true
            # 光有注解没有路径的（@GetMapping 独占一行）映射到类级前缀本身
            grep -qP '^\s+@(Get|Post|Put|Delete|Patch)Mapping\s*$' "$f" && echo
        } | while read -r p; do
            case "$p" in
                '${'*'}'*) p="${p#*:}"; p="${p%\}*}" ;;   # 取占位符里的默认值
            esac
            printf '%s%s\n' "$base" "$p"
        done
    done
    # WebSocket 端点不在 controller 注解里，是 registry.addHandler(handler, "/ws")
    grep -rhoP 'addHandler\([^,]*,\s*"\K[^"]+' "$ROOT_DIR"/apps/server/src/main/java --include=*.java || true
    # 安装页那个端点也不在 controller 注解里：InstallServer 用的是 JDK 自带的 HttpServer，
    # 注册方式是 createContext("/api/v1/setup", ...)，而它跑在 Spring 之前，所以永远不会有 @*Mapping。
    # 只收 /api 开头的：同一个 createContext 还注册了 "/"（表单页本身），那不是接口路径。
    grep -rhoP 'createContext\("\K/api[^"]+' "$ROOT_DIR"/apps/server/src/main/java --include=*.java || true
}

# 文档里的路径先归一化再比：`{channelNo}` 与 `{}` 与 `12345678` 与一串 ULID 都是同一个位置上的
# 一个段，逐字比较会把真实存在的端点误报成不存在（误报多了，这条闸就没人看了）。
pb_norm_route() {
    awk -F/ '{
        out = ""
        for (i = 1; i <= NF; i++) {
            s = $i
            if (s ~ /^\{/) s = "{}"                                       # {msgId} / 未闭合的 {sha256…
            else if (s ~ /^[0-9]+$/ || (length(s) >= 20 && s ~ /^[0-9A-Za-z]+$/)) s = "{}"
            out = out (i > 1 ? "/" : "") s
        }
        sub(/\/$/, "", out)
        print out
    }'
}

pb_doc_routes() {
    grep -rhoP '/api/v1/[A-Za-z0-9_/{}.\-]*|(?<![\w/.-])(?:/message|/healthz|/ws)\b' \
        "$ROOT_DIR"/site/*.html "$ROOT_DIR"/README.md "$ROOT_DIR"/CHANGELOG.md \
        "$ROOT_DIR"/docs/release/*.md "$ROOT_DIR"/docs/*.md "$ROOT_DIR"/infra/*.md 2>/dev/null \
        | pb_norm_route | sort -u
}

# $1 = 真实路由（已把 {} 换成 [^/]+）；stdin = 待验路径。/api/v1 与 /api/v1/admin 是前缀不是路由。
pb_routes_missing() {
    local patterns="$1" d
    while IFS= read -r d; do
        [ -z "$d" ] && continue
        case "$d" in /api/v1 | /api/v1/admin) continue ;; esac
        printf '%s\n' "$d" | grep -qxE -f <(printf '%s\n' "$patterns") || printf '%s\n' "$d"
    done
}

PB_REAL_ROUTES=$(pb_routes_from_code | sed 's|{[A-Za-z0-9_]*}|[^/]+|g' | grep -v '^$' | sort -u)
PB_ROUTE_COUNT=$(printf '%s\n' "$PB_REAL_ROUTES" | wc -l)
if [ "$PB_ROUTE_COUNT" -lt 10 ]; then
    fail "从代码里只提取到 $PB_ROUTE_COUNT 条路由，判据本身坏了（正常应有几十条），这一轮的端点核对不作数"
else
    PB_MISSING_ROUTES=$(pb_doc_routes | pb_routes_missing "$PB_REAL_ROUTES")
    if [ -z "$PB_MISSING_ROUTES" ]; then
        pass "文档里写到的端点路径全部存在于代码（代码侧 $PB_ROUTE_COUNT 条路由）"
    else
        fail "文档里写到的端点路径在代码里找不到（$(printf '%s\n' "$PB_MISSING_ROUTES" | wc -l) 条）："
        while IFS= read -r line; do echo "        · $line"; done <<< "$PB_MISSING_ROUTES"
        echo "    要么改文档，要么这个端点真的还没实现——别让下一份文档继续抄它。"
    fi

    # 反向验证：喂一条从来没存在过的路径，必须被抓出来。
    # 不加这一句，"提取到 0 条路由所以谁都匹配不上"和"文档全对"都显示成 PASS。
    if printf '%s\n' '/api/v1/admin/messages/search' | pb_routes_missing "$PB_REAL_ROUTES" \
        | grep -q '/api/v1/admin/messages/search'; then
        pass "端点闸能抓到不存在的端点（反向验证：/api/v1/admin/messages/search）"
    else
        fail "端点闸的判据坏了：一条不存在的端点路径没有被抓出来"
    fi
fi

# 文档站的导航完整性：admin-api.html 有 16 个端点的完整契约，页面之间互相链到才有用。
# 判据不列页面名单——加新页面时不该顺手来改这条。
if [ -f "$ROOT_DIR/site/admin-api.html" ]; then
    pass "site/admin-api.html 存在"
    UNLINKED=""
    for page in "$ROOT_DIR"/site/*.html; do
        grep -q 'client-api\.html' "$page" || continue            # 没有文档侧栏的页面不参与
        grep -q 'admin-api\.html' "$page" || UNLINKED="$UNLINKED $(basename "$page")"
    done
    if [ -z "$UNLINKED" ]; then
        pass "每个带文档侧栏的页面都链到了管理台 API"
    else
        fail "这些页面的侧栏缺管理台 API 链接：$UNLINKED"
    fi
else
    fail "site/admin-api.html 缺失：管理台那 16 个端点又没有文档了"
fi

# ========================================
# 7. Git 状态检查
# ========================================
section "检查 Git 状态"

cd "$ROOT_DIR"
# 首个版本推上去之前这里连 .git 都没有，而 git 在仓库外是"用法错误 + 退出码 129"，
# 配着 set -e 会让整份验证在最后一节断掉、前面所有结论都白跑。先确认在仓库里再问状态。
if git rev-parse --git-dir >/dev/null 2>&1; then
    if git diff --quiet HEAD; then
        pass "工作区干净（无未提交更改）"
    else
        CHANGED_FILES=$(git diff --name-only HEAD | wc -l)
        warn "工作区有 $CHANGED_FILES 个文件未提交"
    fi

    if git tag --list | grep -qE '(^v|[a-z]+-v)[0-9]'; then
        LATEST_TAG=$(git tag --sort=-v:refname | head -1)
        pass "最新标签: $LATEST_TAG"
    else
        warn "未发现版本标签"
    fi
else
    warn "当前目录还不是 git 仓库：工作区是否干净、有没有打过 v1.0.0 这两条要等 git init 之后再验"
fi

# ========================================
# 8. 发布脚本检查
# ========================================
section "检查发布脚本"

# 这几个脚本是"发一版"的唯一入口，而它们自己不会被任何工作流触发——坏了不会有人替你发现，
# 只有这一节能在发布之前判出来。判据都是形状级的（在不在、过不过语法、顺序对不对、推去哪儿），
# 不判"跑起来结果对不对"：那要真发一版才知道，而真发一版正是这里要拦的事。
RELEASE_SCRIPTS="release.sh release-common.sh release-server.sh release-android.sh release-windows.sh"
missing=""
for s in $RELEASE_SCRIPTS; do
    [ -f "$ROOT_DIR/scripts/$s" ] || missing="$missing $s"
done
if [ -z "$missing" ]; then
    pass "五个发布脚本都在（release.sh + release-common.sh + 三端各一个）"
else
    fail "发布入口不完整，缺：$missing"
fi

syntax_bad=""
for s in $RELEASE_SCRIPTS; do
    [ -f "$ROOT_DIR/scripts/$s" ] || continue
    bash -n "$ROOT_DIR/scripts/$s" 2>/dev/null || syntax_bad="$syntax_bad $s"
done
if [ -z "$syntax_bad" ]; then
    pass "五个发布脚本都过 bash -n"
else
    fail "语法就不对：$syntax_bad（这种脚本炸在写版本号之后，工作区已经改了一半）"
fi

# 安装页冒烟。它判的那类坑只有 fat jar 里才出现（DriverManager 用系统类加载器跑 ServiceLoader，
# 看不见 BOOT-INF/lib 里的 mysql-connector），而 300 来条单测跑的全是 exploded classpath——
# 那条路上永远绿。所以"有没有真的调它"本身就是一条判据：摘掉调用等于把这类坑放回盲区。
SMOKE_SCRIPT="$ROOT_DIR/scripts/install-page-smoke.sh"
if [ ! -f "$SMOKE_SCRIPT" ]; then
    fail "缺 scripts/install-page-smoke.sh：fat jar 独有的类加载坑又没人判了"
elif ! bash -n "$SMOKE_SCRIPT" 2>/dev/null; then
    fail "scripts/install-page-smoke.sh 过不了 bash -n"
elif ! grep -q 'install-page-smoke\.sh' "$ROOT_DIR/scripts/release-server.sh"; then
    fail "release-server.sh 不再调安装页冒烟：那条判据只有 fat jar 验得到，摘掉调用等于没验"
else
    pass "安装页冒烟脚本在位、过语法，且 release-server.sh 真的会调它"
fi

# "先本地测试才能发布"是硬要求，它的形状就是：测试那一步必须排在写版本号那一步前面。
# 用行号比而不是用印象比——两步调个顺序，脚本照样跑得通，只是发出去的东西没测过。
for s in release-server.sh release-android.sh release-windows.sh; do
    f="$ROOT_DIR/scripts/$s"
    [ -f "$f" ] || continue
    test_ln=$(grep -n 'pb_step "3/6' "$f" | head -1 | cut -d: -f1)
    ver_ln=$(grep -n 'set-version.sh --only=' "$f" | head -1 | cut -d: -f1)
    if [ -z "$test_ln" ] || [ -z "$ver_ln" ]; then
        fail "$s 里认不出「本地测试」或「写版本号」那一步：步骤标题改过了？改完要同步这条判据，别把它留成空转"
    elif [ "$test_ln" -lt "$ver_ln" ]; then
        pass "$s：本地测试（第 $test_ln 行）排在写版本号（第 $ver_ln 行）之前"
    else
        fail "$s：写版本号（第 $ver_ln 行）排在了本地测试（第 $test_ln 行）之前——这等于先发再测"
    fi
done

# 推送只许去 github：gitee 与 gitea 不在授权范围内。判的是 PB_REMOTE 的取值唯一，
# 外加"任何一行不许同时出现 push 与那两个名字"——只判前者会漏掉绕过变量直接写 remote 的写法。
remotes=$(grep -oP '(?<=^PB_REMOTE=")[^"]+' "$ROOT_DIR/scripts/release-common.sh" 2>/dev/null | sort -u | tr '\n' ' ')
if [ "$(echo "$remotes" | tr -d ' ')" = "github" ]; then
    pass "推送目标唯一：PB_REMOTE=\"github\""
else
    fail "release-common.sh 里 PB_REMOTE 的取值不是唯一的 github（读到：${remotes:-空}）"
fi
# 扫的是那五个脚本本身，**不能用 `scripts/release*.sh` 这个 glob**：它会把本文件也算进去，
# 而本文件这几行判据里同时写着 `push` 与 `gitee`，于是闸门第一跑就把自己判红。
# （同一类失效在 compose 那条 `${VAR:?}` 判据上出现过一次：给人读的说明文字被自己的闸门当成违规。）
bad_remote=""
for s in $RELEASE_SCRIPTS; do
    [ -f "$ROOT_DIR/scripts/$s" ] || continue
    hit=$(grep -nE 'push' "$ROOT_DIR/scripts/$s" | grep -E 'gitee|gitea' || true)
    [ -n "$hit" ] && bad_remote="$bad_remote $s:$(echo "$hit" | head -1 | cut -d: -f1)"
done
if [ -z "$bad_remote" ]; then
    pass "没有任何一行把 push 与 gitee/gitea 写在一起"
else
    fail "发布脚本里出现了往 gitee/gitea 推的写法：$bad_remote"
fi

# 默认不推：初值必须是 0，否则"跑一下看看"就等于发布。
if grep -q '^PB_PUSH=0$' "$ROOT_DIR/scripts/release-common.sh" 2>/dev/null; then
    pass "推送默认关闭（PB_PUSH=0，要显式 --push 才推）"
else
    fail "release-common.sh 里 PB_PUSH 的初值不是 0：不带 --push 也会推远端"
fi

# ========================================
# 总结
# ========================================
echo ""
echo "=========================================="
echo "📊 验证结果总结"
echo "=========================================="
echo -e "${GREEN}通过: $PASS_COUNT${NC}"
echo -e "${RED}失败: $FAIL_COUNT${NC}"
echo -e "${YELLOW}警告: $WARN_COUNT${NC}"
echo ""

if [ $FAIL_COUNT -eq 0 ]; then
    echo -e "${GREEN}🎉 所有关键检查通过！可以安全发布。${NC}"
    exit 0
else
    echo -e "${RED}⚠️  发现 $FAIL_COUNT 个失败项，请修复后再发布。${NC}"
    exit 1
fi
