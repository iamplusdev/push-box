#!/bin/bash
# 版本号注入脚本（分端）：从 git tag 或参数取版本，只写这一端自己的构建配置。
# 用法:
#   bash scripts/set-version.sh [--only=server|android|windows|site] [VERSION]
#   不带 --only 等于全写（三端一起发时用）。
#   不传 VERSION 时自动从 GITHUB_REF_NAME 或 git describe 取。
#
# 为什么按端分开：服务端、APK、MSI 已经是三条独立的发布链（server-v* / android-v* / windows-v*
# 三种标签），三端版本号各自递增。只改服务端时不该把 Android/Windows 的版本号也刷一遍，
# 更不该让 tag 事件连带重建两个客户端。
#
# 归属：
#   server  —— Maven 三份 pom、web-admin package.json、两份 compose 的镜像标签、WSL 速查里的本地镜像名、
#              官网 site/*.html 的徽章与 jar 文件名（站点展示的是"服务端/产品"版本）。
#   android —— build.gradle.kts 的 versionName 与 versionCode。
#   windows —— csproj 的 <Version> 与 Inno 的 MyAppVersion。
#   site    —— 只改官网那几处（不想动服务端版本时单独用）。
#
# 约束：只有严格 X.Y.Z 的正式版本才会被写入，并据此推导单调递增的 versionCode：
#   versionCode = major*10000 + minor*100 + patch（1.0.0 -> 10000，1.2.3 -> 12203）。
#   无法解析成 X.Y.Z 的版本一律报错退出、不改动任何已提交文件，避免写入更小的 versionCode
#   导致升级失败 (INSTALL_FAILED_VERSION_DOWNGRADED)。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

SCOPE="all"
if [ $# -gt 0 ] && [[ "$1" == --only=* ]]; then
    SCOPE="${1#--only=}"
    shift
fi
case "$SCOPE" in
    all|server|android|windows|site) ;;
    *)
        echo "❌ 未知的 --only=$SCOPE（可选 server / android / windows / site，不写就是全写）" >&2
        exit 1
        ;;
esac

want() {
    [ "$SCOPE" = "all" ] && return 0
    [ "$SCOPE" = "$1" ] && return 0
    # site 的徽章与 jar 文件名属于"服务端/产品"那一档
    [ "$1" = "site" ] && [ "$SCOPE" = "server" ] && return 0
    return 1
}

# 1. 确定版本号
if [ $# -gt 0 ]; then
    VERSION="$1"
elif [ -n "${GITHUB_REF_NAME:-}" ]; then
    # GitHub Actions 环境：v1.2.3 / android-v1.2.3 / windows-v1.2.3 / server-v1.2.3 都取最后一段
    VERSION="${GITHUB_REF_NAME##*-v}"
    VERSION="${VERSION#v}"
else
    # 本地开发：从 git describe 获取
    VERSION=$(git describe --tags --always --dirty 2>/dev/null || echo "0.1.0-dev")
    VERSION="${VERSION##*-v}"
    VERSION="${VERSION#v}"
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
VERSION_CODE=$(( MAJOR * 10000 + MINOR * 100 + PATCH ))

echo "📦 设置版本号: $VERSION (scope=$SCOPE, versionCode=$VERSION_CODE)"

# 3. Android
if want android; then
    ANDROID_BUILD_GRADLE="$ROOT_DIR/android/app/build.gradle.kts"
    if [ -f "$ANDROID_BUILD_GRADLE" ]; then
        sed -i.bak "s/versionCode = [0-9]*/versionCode = $VERSION_CODE/" "$ANDROID_BUILD_GRADLE"
        sed -i.bak "s/versionName = \"[^\"]*\"/versionName = \"$VERSION\"/" "$ANDROID_BUILD_GRADLE"
        rm -f "${ANDROID_BUILD_GRADLE}.bak"
        echo "✅ Android: versionCode=$VERSION_CODE, versionName=$VERSION"
    fi
fi

# 4. Windows
if want windows; then
    WINDOWS_CSPROJ="$ROOT_DIR/windows/PushBox.Windows/PushBox.Windows.csproj"
    if [ -f "$WINDOWS_CSPROJ" ]; then
        sed -i.bak "s/<Version>[^<]*<\/Version>/<Version>$VERSION<\/Version>/" "$WINDOWS_CSPROJ"
        rm -f "${WINDOWS_CSPROJ}.bak"
        echo "✅ Windows: Version=$VERSION"

        INNO_SCRIPT="$ROOT_DIR/windows/installer/PushBox.iss"
        if [ -f "$INNO_SCRIPT" ]; then
            sed -i.bak "s/#define MyAppVersion \"[^\"]*\"/#define MyAppVersion \"$VERSION\"/" "$INNO_SCRIPT"
            rm -f "${INNO_SCRIPT}.bak"
            echo "✅ Inno Setup: MyAppVersion=$VERSION"
        fi
    fi
fi

# 5. Maven 反应堆版本（root 自身 version + core/server 的 <parent><version>）
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
if want server; then
    set_pom_parent_version "$ROOT_DIR/pom.xml"
    set_pom_parent_version "$ROOT_DIR/core/pom.xml"
    set_pom_parent_version "$ROOT_DIR/server/pom.xml"

    # 6. Web 管理台 package.json（版本只出现在 version 字段，vite build 产物不带版本注入）
    WEB_ADMIN_PKG="$ROOT_DIR/web-admin/package.json"
    if [ -f "$WEB_ADMIN_PKG" ]; then
        sed -i.bak "s|\"version\": \"[^\"]*\"|\"version\": \"$VERSION\"|" "$WEB_ADMIN_PKG"
        rm -f "${WEB_ADMIN_PKG}.bak"
        echo "✅ web-admin: version=$VERSION"
    fi

    # 7. Docker Compose 镜像标签（生产与 WSL 测试两份都要同步，否则 tag 漂移）
    for COMPOSE in "$ROOT_DIR/infra/docker-compose.yml" "$ROOT_DIR/infra/docker-compose-wsl.yml"; do
        if [ -f "$COMPOSE" ]; then
            sed -i.bak -E "s#(image: (docker\.io/iamplusdev/push-box|pushbox/server)):[^ ]*#\1:$VERSION#" "$COMPOSE"
            rm -f "${COMPOSE}.bak"
            echo "✅ $(basename "$COMPOSE"): image tag=$VERSION"
        fi
    done

    # 同一份本地镜像标签还抄在排障文档里（那条 `buildx --load` 命令必须产出 compose-wsl 期望的同一个 tag，
    # 否则照文档构建完 compose 还是找不到镜像）。它不归 compose 那个循环管，所以单独覆盖这一种写法。
    for DOC in "$ROOT_DIR/infra/WSL测试速查.md"; do
        if [ -f "$DOC" ]; then
            sed -i.bak -E "s#pushbox/server:[0-9]+\.[0-9]+\.[0-9]+#pushbox/server:$VERSION#g" "$DOC"
            rm -f "${DOC}.bak"
            echo "✅ $(basename "$DOC"): pushbox/server:$VERSION"
        fi
    done
fi

# 8. 官网 site/ 页面里写死的版本
#    站点的顶栏徽章、产物文件名都是手写的 X.Y.Z，不归本脚本管时每次发版都会留一处假数字
#    （1.1.0 这轮就漂成了：构建配置全对，site 七个页面还挂着 1.0.0）。
#    站点展示的是服务端/产品版本，所以 --only=server 与 --only=site 都会改这里。
if want site; then
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
fi

# 9. 输出总结
echo ""
echo "🎉 版本号已写入（scope=$SCOPE）: $VERSION"
if want android; then echo "   - Android: versionCode=$VERSION_CODE, versionName=$VERSION"; fi
if want windows; then echo "   - Windows: Version=$VERSION（csproj + Inno）"; fi
if want server; then
    echo "   - Maven: pushbox-parent=$VERSION（root/core/server）"
    echo "   - Docker: docker.io/iamplusdev/push-box:$VERSION（生产 compose）/ pushbox/server:$VERSION（WSL）"
fi
if want site; then echo "   - 官网: site/*.html 徽章与产物文件名"; fi
exit 0
