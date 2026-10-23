#!/usr/bin/env bash
# ============================================================================
# W15-c：把 PC 客户端编成一个可无人值守安装的 setup.exe。
#
#   bash apps/windows/installer/build-installer.sh                # publish + 编译安装包
#   bash apps/windows/installer/build-installer.sh --skip-publish # 只改 .iss 时重编一次
#
# 两步分开是有原因的：dotnet publish 出的是"269 个文件"，Inno 只是把它们压成一个 exe 并登记卸载信息。
# 版本号从 PushBox.Windows.csproj 的 <Version> 读出来传给 ISCC —— 日志里"启动 {Version}"打的是程序集版本，
# 安装包显示的是它，两处必须同一个数；在 .iss 里再抄一份就是等着有一天对不上。
#
# 前提：装了 Inno Setup 6（本机在 D:\Apps\DE\Inno Setup 6，装法见 docs/Windows开发环境安装教程.md §5）。
# 没有就只跑到 publish 那一步并说明缺什么 —— 这个脚本不打"假装成功"的包。
# ============================================================================
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
proj="$root/apps/windows/PushBox.Windows/PushBox.Windows.csproj"
publish_dir="$here/publish"
iscc="${ISCC:-D:/Apps/DE/Inno Setup 6/ISCC.exe}"
skip_publish=0

for arg in "$@"; do
    case "$arg" in
        --skip-publish) skip_publish=1 ;;
        *) echo "未知参数：$arg（可用：--skip-publish）" >&2; exit 2 ;;
    esac
done

version="$(sed -n 's:.*<Version>\(.*\)</Version>.*:\1:p' "$proj" | head -1)"
if [[ -z "$version" ]]; then
    echo "$proj 里没找到 <Version>...</Version>，安装包该带哪个版本号？" >&2
    exit 1
fi
echo "版本号（取自 csproj 的 <Version>）：$version"

if [[ "$skip_publish" == 0 ]]; then
    # self-contained 是验收"装到干净机器"的前提：目标机器上没有 .NET 8 桌面运行时也要能起。
    # 只 Release 编：Debug 的发布物里带着一堆断言与调试开关，不是要交给别人的那个东西。
    # 产物直接落在 installer/publish（.gitignore 收了），每次清一遍再 publish ——
    # 自包含发布的小版本升级会换运行时文件名，留着上一代的孤儿只会让"包里到底有什么"变成玄学。
    rm -rf "$publish_dir"
    dotnet publish "$proj" -c Release -r win-x64 --self-contained true -o "$publish_dir"
    echo "publish 完成：$(du -sh "$publish_dir" | cut -f1)，$(find "$publish_dir" -type f | wc -l) 个文件"
fi

if [[ ! -x "$iscc" && ! -f "$iscc" ]]; then
    echo "找不到 ISCC：$iscc" >&2
    echo "publish 产物已经在 $publish_dir，但安装包没编出来。装 Inno Setup 6 后重跑，" >&2
    echo "或用 ISCC=<路径> 指认它（装法见 docs/Windows开发环境安装教程.md §5）。" >&2
    exit 1
fi

# ISCC 是原生 Windows 程序，看不见 MSYS 的 /d/... 那套路径；扩展名前的反斜杠它倒是能吃，
# 但统一成正斜杠之后这行输出在任何 shell 里都能直接抄来复跑。
iscc_publish_dir="$(cygpath -w "$publish_dir" | sed 's#\\#/#g')"

# /D 是 ISPP 的命令行宏，不能交给 MSYS 当路径转换（它会把 "/DMyAppVersion=0.1.0" 变成
# "C:/Program Files/Git/DMyAppVersion=0.1.0"，然后 ISCC 报一个谁也看不懂的"未知参数"）。
if ! MSYS2_ARG_CONV_EXCL='*' "$iscc" \
        "/DMyAppVersion=$version" \
        "/DPublishDir=$iscc_publish_dir" \
        "$(cygpath -w "$here/PushBox.iss")"; then
    echo "ISCC 编译失败（上面是它原话）。安装包没编出来。" >&2
    exit 1
fi

setup="$here/Output/PushBox-Setup-$version-win-x64.exe"
if [[ ! -f "$setup" ]]; then
    echo "ISCC 说编译过了，但 $setup 不在 —— 检查上面的输出" >&2
    exit 1
fi
echo "安装包：$setup（$(du -sh "$setup" | cut -f1)）"
echo "无人值守验收：\"$(cygpath -w "$setup")\" /VERYSILENT /SUPPRESSMSGBOXES /NORESTART"
