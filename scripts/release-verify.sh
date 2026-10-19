#!/bin/bash
# PushBox 发布前自动化验证脚本
# 用法: bash scripts/release-verify.sh [--skip-build]

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
# 1. 版本号一致性检查
# ========================================
section "检查版本号一致性"

ANDROID_VERSION=$(grep 'versionName' "$ROOT_DIR/android/app/build.gradle.kts" | grep -oP '"[^"]+"' | tr -d '"')
WINDOWS_VERSION=$(grep '<Version>' "$ROOT_DIR/windows/PushBox.Windows/PushBox.Windows.csproj" | grep -oP '[0-9]+\.[0-9]+\.[0-9]+')
DOCKER_TAG=$(grep -E 'image: (docker\.io/iamplusdev/push-box|pushbox/server):' "$ROOT_DIR/deploy/docker-compose.yml" | head -1 | grep -oP '[0-9]+\.[0-9]+\.[0-9]+')

echo "Android: $ANDROID_VERSION"
echo "Windows: $WINDOWS_VERSION"
echo "Docker:  $DOCKER_TAG"

if [ "$ANDROID_VERSION" = "$WINDOWS_VERSION" ] && [ "$WINDOWS_VERSION" = "$DOCKER_TAG" ]; then
    pass "三端版本一致: $ANDROID_VERSION"
else
    fail "版本不一致! Android=$ANDROID_VERSION, Windows=$WINDOWS_VERSION, Docker=$DOCKER_TAG"
fi

# Maven 是第四个自称拥有版本号的地方：jar 名、Dockerfile 的通配、run-dev.sh 都跟着它走
MAVEN_VERSION=$(grep -m1 '<version>' "$ROOT_DIR/pom.xml" | grep -oP '[0-9]+\.[0-9]+\.[0-9]+')
if [ "$MAVEN_VERSION" = "$ANDROID_VERSION" ]; then
    pass "Maven 版本与三端一致: $MAVEN_VERSION"
else
    fail "pom.xml 版本=$MAVEN_VERSION 与三端=$ANDROID_VERSION 不一致（jar 名会跟着 pom 走）"
fi
if grep -q 'pushbox-server-\*\.jar' "$ROOT_DIR/deploy/Dockerfile" \
    && grep -q 'pushbox-server-\*\.jar' "$ROOT_DIR/deploy/run-dev.sh"; then
    pass "Dockerfile 与 run-dev.sh 都按通配取 jar，不吃版本号"
else
    fail "部署脚本里写死了 jar 文件名，版本号一改就找不到（应使用 pushbox-server-*.jar）"
fi

# 官网徽章是第七个自称拥有版本号的地方，而且是人手写的：set-version.sh 一开始不覆盖它，
# 结果 1.1.0 这轮构建配置全对、site/ 七个页面却挂着 1.0.0 —— 公开仓里这种数字不会自己回头修。
BADGE_PATTERN='([0-9]+\.[0-9]+\.[0-9]+ · 自托管推送|class="ver">[0-9]+\.[0-9]+\.[0-9]+)'
BADGE_VERSIONS=$(grep -hoE "$BADGE_PATTERN" "$ROOT_DIR"/site/*.html | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | sort -u)
BADGE_HITS=$(grep -hoE "$BADGE_PATTERN" "$ROOT_DIR"/site/*.html | wc -l)
if [ "$BADGE_HITS" -lt 6 ]; then
    fail "只认出 $BADGE_HITS 处站点版本徽章（预期至少 6 处）：徽章写法改了，这条判据得跟着改，别删"
elif [ "$BADGE_VERSIONS" = "$MAVEN_VERSION" ]; then
    pass "官网版本徽章与 pom 一致: $BADGE_VERSIONS（$BADGE_HITS 处）"
else
    fail "官网徽章里的版本是 $(echo "$BADGE_VERSIONS" | tr '\n' ' ')，与 pom 的 $MAVEN_VERSION 不一致（跑 bash scripts/set-version.sh）"
fi

# ========================================
# 2. 密钥配置检查
# ========================================
section "检查生产环境密钥配置"

# 检查 docker-compose.yml 中的强制验证
if grep -q 'PB_ADMIN_SECRET:.*:?' "$ROOT_DIR/deploy/docker-compose.yml"; then
    pass "PB_ADMIN_SECRET 已设置为必填项"
else
    fail "PB_ADMIN_SECRET 未强制要求"
fi

# 登录口令与 JWT 签名钥拆成两把，两把都必须在 compose 解析阶段就要到人
if grep -q 'PB_ADMIN_JWT_SECRET:.*:?' "$ROOT_DIR/deploy/docker-compose.yml"; then
    pass "PB_ADMIN_JWT_SECRET 已设置为必填项"
else
    fail "PB_ADMIN_JWT_SECRET 未强制要求（管理台会拿它签 JWT，缺了只能等到登录才炸）"
fi

if grep -q 'PB_ADMIN_JWT_SECRET=<必填' "$ROOT_DIR/deploy/env/prod.env.example"; then
    pass "prod.env.example 含 PB_ADMIN_JWT_SECRET 必填占位"
else
    fail "prod.env.example 缺 PB_ADMIN_JWT_SECRET"
fi

# 对象存储明文不再只是"建议加密"：prod 里 object-encrypt=none 要进程拒绝启动
if grep -q 'PB_ALLOW_PLAINTEXT_MEDIA' "$ROOT_DIR/deploy/docker-compose.yml"; then
    pass "明文对象存储的逃生阀已接进 compose"
else
    fail "compose 缺 PB_ALLOW_PLAINTEXT_MEDIA，ProdPlaintextMediaGuard 那条判据没有对应开关"
fi

# 检查 entrypoint.sh 是否存在
if [ -f "$ROOT_DIR/deploy/Dockerfile" ] && grep -q "entrypoint.sh" "$ROOT_DIR/deploy/Dockerfile"; then
    pass "Docker 入口点验证脚本已配置"
else
    fail "Docker 入口点验证脚本缺失"
fi

# 入口脚本里绝不打印密钥本身（哪怕只是前几位）：容器日志会被采集走
if grep -qE '\$\{?[A-Z_]*KEY[A-Z_]*:0:[0-9]+' "$ROOT_DIR/deploy/Dockerfile"; then
    fail "Dockerfile 的入口脚本截了密钥片段往外印"
else
    pass "入口脚本不会打印密钥片段"
fi

# 检查 prod.env.example 是否更新
if grep -q 'PB_ADMIN_SECRET=<必填' "$ROOT_DIR/deploy/env/prod.env.example"; then
    pass "prod.env.example 已更新为必填提示"
else
    warn "prod.env.example 可能需要更新"
fi

# ---- 部署变量必须"有人消费" ----
# prod.env.example 顶部写了口径：加一个变量必须同时改 application-prod.yml，否则它被静默忽略。
# 下面三条就是那句话的执行版本，都是实跑撞出来的：
#   PB_SERVER_PORT 曾经只有端口映射、healthcheck、Caddy 上游三处读它，进程只认 base yml 的 7404；
#   PB_STORAGE_TYPE 曾经在 prod 完全没有映射，README 的"拓扑 C 本地存储"是个死开关。
# 这类错的症状都不在配置里：容器"起来了"，端口却没人听，或者图片一路 404。
if grep -q 'PB_SERVER_PORT: \${PB_SERVER_PORT' "$ROOT_DIR/deploy/docker-compose.yml" \
    && grep -q 'port: \${PB_SERVER_PORT' "$ROOT_DIR/server/src/main/resources/application-prod.yml"; then
    pass "PB_SERVER_PORT 一路透传到进程（compose 与 application-prod.yml 都在）"
else
    fail "PB_SERVER_PORT 没有透传给进程：改端口只会得到\"映射指向一个没人监听的端口\"，探活永远失败"
fi

if grep -q 'type: \${PB_STORAGE_TYPE' "$ROOT_DIR/server/src/main/resources/application-prod.yml"; then
    pass "PB_STORAGE_TYPE 在 prod profile 有映射（拓扑 C 本地存储可用）"
else
    fail "PB_STORAGE_TYPE 没人消费：文档里的 PB_STORAGE_TYPE=local 是死开关，进程照旧走 S3"
fi

if grep -q 'PB_SECURITY_INTERNAL_CIDRS: \${PB_SECURITY_INTERNAL_CIDRS:-}' "$ROOT_DIR/deploy/docker-compose.yml"; then
    pass "运维端点准入默认关闭（compose 不再给 172.16.0.0/12 兜底）"
else
    fail "compose 又给 PB_SECURITY_INTERNAL_CIDRS 加了网段默认值：\${VAR:-x} 在空串时也会用上它，等于\"按文档留空却放进一整段\""
fi

# compose 的 ${VAR:?} 插值作用于整本文件，跟 profile 有没有激活无关：
# 监控用的必填项留在主文件里，等于"不开 Grafana 也必须有 Grafana 口令"，up -d 直接解析失败。
# 只认插值形态（\${GRAFANA_ADMIN_PASSWORD...），主文件里那段解释为什么挪走的注释不算命中。
if grep -q '\${GRAFANA_ADMIN_PASSWORD' "$ROOT_DIR/deploy/docker-compose.yml"; then
    fail "GRAFANA_ADMIN_PASSWORD 还在主 compose 文件里：只起服务端的部署会在解析阶段就被它卡死"
elif [ -f "$ROOT_DIR/deploy/docker-compose.monitoring.yml" ] \
  && grep -q '\${GRAFANA_ADMIN_PASSWORD' "$ROOT_DIR/deploy/docker-compose.monitoring.yml"; then
    pass "监控栈独立成一本文件，主文件的必填项只剩真必填的那几个"
else
    fail "找不到 deploy/docker-compose.monitoring.yml（或它没有接手 GRAFANA_ADMIN_PASSWORD）：prometheus/grafana 没有落脚的地方"
fi

# 凭据明文只会落在三个地方：deploy/env/local.env、deploy/.env、.idea/（IDEA 把运行配置的
# Environment variables 写进 workspace.xml）。三者都靠 .gitignore 挡，所以这条"忽略规则还在"
# 本身就是发布判据——少一行，下一次谁在 IDEA 里填了口令就等于把它提交上去了。
for pat in 'deploy/env/local.env' '^\.env$' '^\.idea/'; do
    if grep -qE "$pat" "$ROOT_DIR/.gitignore"; then
        pass ".gitignore 仍然排除 $pat"
    else
        fail ".gitignore 不再排除 $pat —— 这个位置会出现凭据明文，必须让它进不了提交"
    fi
done

# 模板、文档、脚本与站点里都不该出现真实主机地址（那本身就是可拼出拓扑的情报）。
# 允许 CIDR 前缀形态（100.64.0.0、192.168.0.0 这类写在网络段说明里的），逐条排除。
SCAN_TARGETS=(
    "$ROOT_DIR/deploy/env/prod.env.example"
    "$ROOT_DIR/deploy/QUICK-START.md"
    "$ROOT_DIR/deploy/docker-compose.yml"
    "$ROOT_DIR/deploy/Caddyfile"
    "$ROOT_DIR/deploy/backup/backup.sh"
    "$ROOT_DIR/deploy/ca/gen-ca.sh"
    "$ROOT_DIR/deploy/probe"
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
    "$ROOT_DIR/android/app/src" "$ROOT_DIR/server/src" "$ROOT_DIR/windows/PushBox.Windows" \
    "$ROOT_DIR/windows/PushBox.Crypto.Tests" "$ROOT_DIR/web-admin/src" 2>/dev/null \
    | sort -u || true)
if [ -n "$LEAKY_CODE" ]; then
    fail "代码/测试里出现真实地址：$(echo "$LEAKY_CODE" | tr '\n' ' ')"
else
    pass "代码与测试夹具不含真实地址"
fi

# 反向代理：主机名只从 .env 来，缺证书时的现象已经在 Caddyfile 顶部写清
if grep -q '\$PB_CADDY_HOST' "$ROOT_DIR/deploy/Caddyfile" \
    && grep -q 'PB_CADDY_HOST: ' "$ROOT_DIR/deploy/docker-compose.yml" \
    && grep -q '^PB_CADDY_HOST=' "$ROOT_DIR/deploy/env/prod.env.example"; then
    pass "Caddy 主机名参数化到 PB_CADDY_HOST（改一处即可）"
else
    fail "Caddy 主机名仍需在 Caddyfile 里多处手改，或 .env/compose 没接上 PB_CADDY_HOST"
fi

# 7404 只能绑环回：公网流量必须走 Caddy（TLS、/actuator 屏蔽、请求体上限都在那层）
# 支持自定义端口：检查是否使用了 ${PB_SERVER_PORT:-7404} 变量形式
if grep -q '"127.0.0.1:${PB_SERVER_PORT:-7404}:${PB_SERVER_PORT:-7404}"' "$ROOT_DIR/deploy/docker-compose.yml" || \
   grep -q '"127.0.0.1:7404:7404"' "$ROOT_DIR/deploy/docker-compose.yml"; then
    pass "compose 只把后端端口绑在环回（文档里的 curl localhost:7404/healthz 因此成立）"
else
    fail "compose 没有把后端端口限制在环回；要么显式发布给内网，要么按 127.0.0.1:\${PB_SERVER_PORT:-7404}:\${PB_SERVER_PORT:-7404} 收紧"
fi

# ========================================
# 3. Android 构建配置检查
# ========================================
section "检查 Android 构建配置"

# versionCode 必须由 versionName 推出来（scripts/set-version.sh 同一套算法），
# 写死一个更大的数会让正式版 tag 注入后反而变小，用户端 INSTALL_FAILED_VERSION_DOWNGRADED。
ANDROID_CODE=$(grep 'versionCode' "$ROOT_DIR/android/app/build.gradle.kts" | grep -oE '[0-9]+' | head -1)
IFS=. read -r MAJOR MINOR PATCH <<<"$ANDROID_VERSION"
EXPECTED_CODE=$(( MAJOR * 10000 + MINOR * 100 + PATCH ))
if [ "$ANDROID_CODE" = "$EXPECTED_CODE" ]; then
    pass "versionCode=$ANDROID_CODE 与 versionName=$ANDROID_VERSION 对得上"
else
    fail "versionCode=$ANDROID_CODE 应为 $EXPECTED_CODE（versionName $ANDROID_VERSION）"
fi

# release 必须由环境变量注入的 keystore 签名：缺签名要构建失败，
# 而不是安静产出一个 debug 签名的包——用户换版时签名对不上只能卸载重装，历史全丢。
if grep -q 'ANDROID_KEYSTORE_BASE64' "$ROOT_DIR/android/app/build.gradle.kts"; then
    pass "Android release 签名由环境变量驱动"
else
    fail "Android release 没有签名配置"
fi

if grep -rq 'pushbox-debug-' "$ROOT_DIR/.github/workflows/build-apk.yml" \
    "$ROOT_DIR/android/app/src/main/java/cn/iamplus/pushbox/net/UpdateApi.kt"; then
    fail "仍在收集或认可 debug APK 资产"
else
    pass "发布链路与客户端都只认签名 release 包"
fi

# 机器专属绝对路径不该进仓库（换一台机器就构建不了）
if grep -q 'installations.paths' "$ROOT_DIR/android/gradle.properties"; then
    fail "android/gradle.properties 写死了本机 JDK 路径"
else
    pass "gradle.properties 不含机器专属路径"
fi

# 检查 R8 是否启用
if grep -q 'isMinifyEnabled = true' "$ROOT_DIR/android/app/build.gradle.kts"; then
    pass "R8 混淆已启用"
else
    fail "R8 混淆未启用"
fi

# 检查 proguard-rules.pro 是否存在
if [ -f "$ROOT_DIR/android/app/proguard-rules.pro" ]; then
    RULES_COUNT=$(wc -l < "$ROOT_DIR/android/app/proguard-rules.pro")
    pass "proguard-rules.pro 存在 ($RULES_COUNT 行规则)"
else
    fail "proguard-rules.pro 缺失"
fi

# 检查 Gradle wrapper URL
WRAPPER_URL=$(grep 'distributionUrl' "$ROOT_DIR/android/gradle/wrapper/gradle-wrapper.properties" | cut -d= -f2)
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

if [ -f "$ROOT_DIR/docs/CODESIGN-GUIDE.md" ]; then
    pass "代码签名指南文档存在"
else
    warn "代码签名指南文档缺失"
fi

# ========================================
# 5. CI/CD 工作流检查
# ========================================
section "检查 CI/CD 工作流"

# 出制品的三个必须由 tag 注入版本号；ci.yml 反过来——它是门禁，跑的就是仓库里那份代码，
# 给它加版本注入等于让它去测一个不是要发出去的东西。
WORKFLOWS=("build-apk.yml" "build-msi.yml" "docker-publish.yml")
for wf in "${WORKFLOWS[@]}"; do
    if [ -f "$ROOT_DIR/.github/workflows/$wf" ]; then
        # 检查是否包含版本注入步骤
        if grep -q 'set-version.sh' "$ROOT_DIR/.github/workflows/$wf"; then
            pass "$wf 包含版本管理脚本"
        else
            warn "$wf 可能缺少版本注入步骤"
        fi
    else
        fail "$wf 工作流文件缺失"
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

if grep -q 'set-version.sh' "$ROOT_DIR/.github/workflows/ci.yml" 2>/dev/null; then
    fail "ci.yml 里做了版本注入：门禁应当测仓库里那份代码，改过版本号就不是同一件事了"
else
    pass "ci.yml 不改版本号，测的就是提交里的那份"
fi

# 四个 workflow 只在 main / v* 上触发，所以 main 这个分支名本身也是发布链的一部分。
if grep -q 'branches: \[main\]' "$ROOT_DIR/.github/workflows/ci.yml"; then
    pass "CI 触发分支与主分支口径一致（main）"
else
    fail "CI 的触发分支不是 main，推上去不会有任何流水线运行"
fi

# ========================================
# 6. 文档完整性检查
# ========================================
section "检查文档完整性"

REQUIRED_DOCS=(
    "README.md"
    "LICENSE"
    "CHANGELOG.md"
    "RELEASE-CHECKLIST.md"
    "docs/UX-Improvements-Summary.md"
    "docs/R8-CONFIGURATION.md"
    "docs/CODESIGN-GUIDE.md"
)

for doc in "${REQUIRED_DOCS[@]}"; do
    if [ -f "$ROOT_DIR/$doc" ]; then
        SIZE=$(du -h "$ROOT_DIR/$doc" | cut -f1)
        pass "$doc 存在 ($SIZE)"
    else
        fail "$doc 缺失"
    fi
done

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

    if git tag --list | grep -q '^v'; then
        LATEST_TAG=$(git tag --sort=-v:refname | head -1)
        pass "最新标签: $LATEST_TAG"
    else
        warn "未发现版本标签"
    fi
else
    warn "当前目录还不是 git 仓库：工作区是否干净、有没有打过 v1.0.0 这两条要等 git init 之后再验"
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
