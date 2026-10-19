#!/bin/bash
# 统一版本号注入脚本：从 git tag 或环境变量提取版本，同步到三端构建配置
# 用法: bash scripts/set-version.sh [VERSION]
#   如果不传 VERSION，则自动从 GITHUB_REF_NAME 或 git describe 获取
#
# 约束：只有严格 X.Y.Z 的正式版本才会被写入构建配置，并据此推导单调递增的 versionCode：
#   versionCode = major*10000 + minor*100 + patch（1.0.0 -> 10000，1.2.3 -> 12203）。
#   无法解析成 X.Y.Z 的版本（例如本地 git describe 兜底的 0.1.0-dev、脏树后缀）一律报错退出、
#   不改动任何已提交文件，避免写入更小的 versionCode 导致升级失败
#   (INSTALL_FAILED_VERSION_DOWNGRADED)。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# 1. 确定版本号
if [ $# -gt 0 ]; then
    VERSION="$1"
elif [ -n "${GITHUB_REF_NAME:-}" ]; then
    # GitHub Actions 环境：从 tag 提取（v1.25 -> 1.25）
    VERSION="${GITHUB_REF_NAME#v}"
else
    # 本地开发：从 git describe 获取
    VERSION=$(git describe --tags --always --dirty 2>/dev/null || echo "0.1.0-dev")
    VERSION="${VERSION#v}"  # 去掉可能的 v 前缀
fi

# 2. 校验版本号必须是严格 X.Y.Z，并据此推导单调 versionCode
if [[ ! "$VERSION" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    echo "❌ 版本号无法解析为 X.Y.Z: '$VERSION'" >&2
    echo "   仅正式版本（如 1.0.0、1.2.3）才能注入 versionCode/versionName。" >&2
    echo "   已保持已提交的构建配置不变（拒绝写入破坏性 versionCode）。" >&2
    exit 1
fi
MAJOR="${BASH_REMATCH[1]}"
MINOR="${BASH_REMATCH[2]}"
PATCH="${BASH_REMATCH[3]}"
# versionCode = major*10000 + minor*100 + patch（单调递增，不再依赖 GITHUB_RUN_NUMBER/时间戳）
VERSION_CODE=$(( MAJOR * 10000 + MINOR * 100 + PATCH ))

echo "📦 设置版本号: $VERSION (versionCode=$VERSION_CODE)"

# 3. 更新 Android versionName 和 versionCode
ANDROID_BUILD_GRADLE="$ROOT_DIR/android/app/build.gradle.kts"
if [ -f "$ANDROID_BUILD_GRADLE" ]; then
    sed -i.bak "s/versionCode = [0-9]*/versionCode = $VERSION_CODE/" "$ANDROID_BUILD_GRADLE"
    sed -i.bak "s/versionName = \"[^\"]*\"/versionName = \"$VERSION\"/" "$ANDROID_BUILD_GRADLE"
    rm -f "${ANDROID_BUILD_GRADLE}.bak"
    echo "✅ Android: versionCode=$VERSION_CODE, versionName=$VERSION"
fi

# 4. 更新 Windows Version
WINDOWS_CSPROJ="$ROOT_DIR/windows/PushBox.Windows/PushBox.Windows.csproj"
if [ -f "$WINDOWS_CSPROJ" ]; then
    sed -i.bak "s/<Version>[^<]*<\/Version>/<Version>$VERSION<\/Version>/" "$WINDOWS_CSPROJ"
    rm -f "${WINDOWS_CSPROJ}.bak"
    echo "✅ Windows: Version=$VERSION"

    # 同时更新 Inno Setup 脚本中的默认版本（与 Windows/csproj 使用同一字符串）
    INNO_SCRIPT="$ROOT_DIR/windows/installer/PushBox.iss"
    if [ -f "$INNO_SCRIPT" ]; then
        sed -i.bak "s/#define MyAppVersion \"[^\"]*\"/#define MyAppVersion \"$VERSION\"/" "$INNO_SCRIPT"
        rm -f "${INNO_SCRIPT}.bak"
        echo "✅ Inno Setup: MyAppVersion=$VERSION"
    fi
fi

# 5. 更新 Maven 反应堆版本（root 自身 version + core/server 的 <parent><version>）
#    三处必须一起改：server/pom.xml 依赖的 pushbox-parent 版本与根 pom 不一致时，
#    整个反应堆 Non-resolvable parent POM，服务端与 Docker 镜像直接构建不了。
#    改 <artifactId>pushbox-parent</artifactId> 之后三行内的第一个 <version>（覆盖自身版本
#    与 <parent> 块两种上下文），用 gawk 而非 sed——BRE 的反向引用不支持重复。
set_pom_parent_version() {
    local pom="$1"
    [ -f "$pom" ] || return 0
    gawk -v v="$VERSION" '
        state == 1 && matched == 0 && $0 ~ /<version>[^<]*</ { sub(/<version>[^<]*</, "<version>" v "<"); matched = 1 }
        { print }
        state == 0 && $0 ~ /<artifactId>pushbox-parent</ { state = 1; look = 0 }
        state == 1 && matched == 0 { look++; if (look > 3) state = 2 }
    ' "$pom" > "$pom.tmp" && mv "$pom.tmp" "$pom"
    echo "✅ Maven $(basename "$(dirname "$pom")")/pom.xml: version=$VERSION"
}
set_pom_parent_version "$ROOT_DIR/pom.xml"
set_pom_parent_version "$ROOT_DIR/core/pom.xml"
set_pom_parent_version "$ROOT_DIR/server/pom.xml"

# 6. 更新 Web 管理台 package.json（版本只出现在 version 字段，vite build 产物不带版本注入）
WEB_ADMIN_PKG="$ROOT_DIR/web-admin/package.json"
if [ -f "$WEB_ADMIN_PKG" ]; then
    sed -i.bak "s|\"version\": \"[^\"]*\"|\"version\": \"$VERSION\"|" "$WEB_ADMIN_PKG"
    rm -f "${WEB_ADMIN_PKG}.bak"
    echo "✅ web-admin: version=$VERSION"
fi

# 7. 更新 Docker Compose 镜像标签（生产与 WSL 测试两份都要同步，否则 tag 漂移）
for COMPOSE in "$ROOT_DIR/deploy/docker-compose.yml" "$ROOT_DIR/deploy/docker-compose-wsl.yml"; do
    if [ -f "$COMPOSE" ]; then
        sed -i.bak -E "s#(image: (docker\.io/iamplusdev/push-box|pushbox/server)):[^ ]*#\1:$VERSION#" "$COMPOSE"
        rm -f "${COMPOSE}.bak"
        echo "✅ $(basename "$COMPOSE"): image tag=$VERSION"
    fi
done

# 8. 更新官网 site/ 页面里写死的版本
#    站点的顶栏徽章、产物文件名都是手写的 X.Y.Z，不归本脚本管时每次发版都会留一处假数字
#    （1.1.0 这轮就漂成了：构建配置全对，site 七个页面还挂着 1.0.0）。
echo "🌐 官网 site/"
for HTML in "$ROOT_DIR"/site/*.html; do
    [ -f "$HTML" ] || continue
    sed -i.bak -E \
        -e "s#[0-9]+\.[0-9]+\.[0-9]+( · 自托管推送)#$VERSION\1#" \
        -e "s#(<span class=\"ver\">)[^<]*(</span>)#\1$VERSION\2#" \
        -e "s#pushbox-server-[0-9]+\.[0-9]+\.[0-9]+\.jar#pushbox-server-$VERSION.jar#" \
        "$HTML"
    rm -f "${HTML}.bak"
done
echo "✅ site/*.html: 徽章与 jar 文件名 = $VERSION"

# 9. 输出总结
echo ""
echo "🎉 版本号已同步到所有平台: $VERSION"
echo "   - Android: versionCode=$VERSION_CODE, versionName=$VERSION"
echo "   - Windows: Version=$VERSION"
echo "   - Maven: pushbox-parent=$VERSION（root/core/server）"
echo "   - Docker: docker.io/iamplusdev/push-box:$VERSION（生产 compose）/ pushbox/server:$VERSION（WSL 本地构建）"
echo "   - 官网: site/*.html 徽章与产物文件名"
