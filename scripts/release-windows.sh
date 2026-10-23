#!/usr/bin/env bash
# 发布 Windows：GitHub Release 上挂 PushBox-Setup-<版本>-win-x64.exe。
#
#   bash scripts/release-windows.sh                # 自动取 windows-v* 最新标签 +1 补丁位
#   bash scripts/release-windows.sh 1.2.0
#   bash scripts/release-windows.sh --bump=minor
#   bash scripts/release-windows.sh --dry-run
#   bash scripts/release-windows.sh --push         # 真推到 github（触发 build-msi.yml 出安装包并发 Release）
#
# 顺序同样是"先本地测试，再写版本号，再打标签"。
# 本地测试跑的是 **CI 同一条命令**（`dotnet test PushBox.Crypto.Tests -c Release`）：
# 那条命令是 `ci.yml` 里 `$CS_BASELINE = 554` 那道计数闸的输入，跑别的命令等于本机与 runner 判的不是同一件事。

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/release-common.sh"

COMPONENT="windows"
TAG_PREFIX="windows-v"

pb_usage() {
  cat <<'USAGE'
用法: bash scripts/release-windows.sh [X.Y.Z] [选项]

选项:
  --bump=patch|minor|major  不给版本号时按这个档位从最新 windows-v* 标签递进（默认 patch）
  --dry-run, -n             只跑测试与算版本，不写文件、不提交、不打标签
  --yes, -y                 不再问确认
  --push                    提交与标签真推到 github（不带就只留在本地）
  --skip-tests              跳过本地测试与安装包试编（不推荐）
  --help, -h                本说明
USAGE
}

pb_parse_args "$@"

# ---- 1. 定版本 ----
pb_step "1/6 定版本号"
latest="$(pb_latest_tag "$TAG_PREFIX")"
intree="$(pb_intree_version "$COMPONENT" || true)"
pb_info "最新标签 ${TAG_PREFIX}${latest:-（无）}；csproj 现值 ${intree:-（读不到）}"

if [ -z "$PB_TARGET" ]; then
  base="${latest:-$intree}"
  [ -z "$base" ] && { pb_fail "既没有 $TAG_PREFIX* 标签，csproj 里也读不出版本，请显式给一个 X.Y.Z"; exit 1; }
  PB_TARGET="$(pb_bump "$base" "$PB_BUMP")"
  pb_info "按 --bump=$PB_BUMP 从 $base 递进 → $PB_TARGET"
fi
pb_parse_version "$PB_TARGET" || { pb_fail "版本号必须是严格的 X.Y.Z，收到的是 '$PB_TARGET'"; exit 1; }
pb_split_version "$PB_TARGET"
if [ -n "$latest" ] && ! pb_version_gt "$PB_TARGET" "$latest"; then
  pb_fail "$PB_TARGET 不比已有标签 $latest 新。Inno 默认 AllowDowngrades=yes，装是能装上，但'回滚'与'升级'就分不出来了。"
  exit 1
fi
pb_ok "目标版本 $PB_TARGET（标签 ${TAG_PREFIX}${PB_TARGET}）"

# ---- 2. 工作区 ----
pb_step "2/6 检查工作区"
if [ "$PB_DRY_RUN" != 1 ]; then
  pb_require_clean || exit 1
  pb_ok "工作区干净（分支 $(pb_current_branch)）"
else
  n="$(pb_dirty_files)"; pb_warn "dry-run：工作区有 $n 个文件未提交，真发布时会被拦下"
fi

# ---- 3. 本地测试 ----
pb_step "3/6 本地测试"
if [ "$PB_SKIP_TESTS" = 1 ]; then
  pb_warn "已跳过（--skip-tests）"
elif [ "$PB_DRY_RUN" = 1 ]; then
  pb_info "[dry-run] 本来会跑：dotnet test PushBox.Crypto.Tests -c Release，再试编一次安装包"
else
  command -v dotnet >/dev/null 2>&1 || { pb_fail "PATH 里没有 dotnet"; exit 1; }
  pb_info "单元测试（与 ci.yml 逐字同一条命令，含 --results-directory 与 trx 文件名）"
  # 判分要把全量日志落盘再看退出码：`| tail` 会把失败压成"看着像绿"。
  log="$(mktemp)"
  rm -f "$PB_ROOT/apps/windows/TestResults/crypto.trx"   # 上一轮的 trx 留着会数出一份陈年用例数
  if ( cd "$PB_ROOT/apps/windows" && dotnet test PushBox.Crypto.Tests/PushBox.Crypto.Tests.csproj \
         -c Release --nologo --results-directory TestResults --logger "trx;LogFileName=crypto.trx" ) 2>&1 | tee "$log"; then
    :
  else
    pb_fail "测试没过，发布中止（工作区未改动）。失败明细："
    grep -E "\[FAIL\]|error " "$log" | head -20 >&2 || true
    rm -f "$log"; exit 1
  fi
  rm -f "$log"

  # 用例数从 trx 数，不解析控制台文案——那玩意的语言跟着 runner 走（ci.yml 里同一条注记）。
  # 退出码只回答"有没有红"，用例被静默裁掉（删 [Fact]、改分类过滤、整类没被 discover）照样退 0。
  trx="$PB_ROOT/apps/windows/TestResults/crypto.trx"
  [ -f "$trx" ] || { pb_fail "没生成 crypto.trx：还原或编译就断了，判不了用例数"; exit 1; }
  total="$(grep -c '<UnitTestResult ' "$trx" || true)"
  baseline="$(grep -oP '(?<=\$CS_BASELINE = )[0-9]+' "$PB_ROOT/.github/workflows/ci.yml" | head -1 || true)"
  if [ -n "$baseline" ] && [ "$total" -lt "$baseline" ]; then
    pb_fail "C# 用例数 $total 少于 ci.yml 的基线 $baseline：有用例被静默裁掉了"
    exit 1
  fi
  pb_ok "测试通过（trx 里数出 $total 条，ci.yml 基线 ${baseline:-读不到}）"

  pb_info "试编安装包（build-installer.sh；本机没装 Inno Setup 6 就只跑到 publish 并说明缺什么）"
  ( cd "$PB_ROOT" && bash apps/windows/installer/build-installer.sh ) \
    || { pb_warn "安装包这一步没过。若是缺 Inno Setup 6，CI 会装；若是 publish 失败，那就是真问题，发布中止"; exit 1; }
  pb_ok "安装包试编通过"
fi

# ---- 4. 写版本号 ----
pb_step "4/6 写版本号并收产物"
if [ "$PB_DRY_RUN" = 1 ]; then
  pb_info "[dry-run] 本来会跑：bash scripts/set-version.sh --only=windows $PB_TARGET"
else
  ( cd "$PB_ROOT" && bash scripts/set-version.sh --only=windows "$PB_TARGET" ) || { pb_fail "写版本号失败"; exit 1; }
  # csproj 与 .iss 必须同一个数：安装包显示的是 .iss 那个，程序「关于」页显示的是程序集版本。
  cs="$(pb_intree_version windows || true)"
  iss="$(grep -oP '(?<=#define MyAppVersion ")[^"]+' "$PB_ROOT/apps/windows/installer/PushBox.iss" | head -1)"
  if [ "$cs" != "$PB_TARGET" ] || [ "$iss" != "$PB_TARGET" ]; then
    pb_fail "版本号没写全：csproj=$cs，PushBox.iss=$iss，期望都是 $PB_TARGET"
    exit 1
  fi
  pb_ok "csproj 与 Inno 都是 $PB_TARGET"

  # 重出一份带新版本号的安装包收进 output/。第 3 步那一份还是旧版本；
  # 缺 Inno Setup 6 时只跑 publish、不出 exe，与第 3 步的容忍口径一致。
  pb_info "按 $PB_TARGET 重出安装包并收进 output/"
  if ( cd "$PB_ROOT" && bash apps/windows/installer/build-installer.sh ); then
    pb_collect "$(ls "$PB_ROOT"/apps/windows/installer/Output/PushBox-Setup-*-win-x64.exe 2>/dev/null | head -1)" \
      windows-exe "PushBox-Setup-$PB_TARGET-win-x64.exe"
  else
    pb_warn "安装包没重出（多半是缺 Inno Setup 6），output/ 这轮没有 exe；CI 出的是 $PB_TARGET 那版"
  fi
fi

# ---- 5. 提交与打标签 ----
pb_step "5/6 提交与打标签"
tag="${TAG_PREFIX}${PB_TARGET}"
if [ "$PB_DRY_RUN" = 1 ]; then
  pb_info "[dry-run] 本来会提交版本号改动，并打标签 $tag"
else
  pb_confirm "即将提交并打标签 $tag（本地）" || exit 1
  rc=0
  pb_release_commit "chore(release): $COMPONENT v$PB_TARGET" || rc=$?
  case $rc in
    0) pb_ok "已提交版本号改动" ;;
    1) pb_info "版本号 $PB_TARGET 已经在 HEAD 里（没有待提交的改动），标签直接打在 $(git -C "$PB_ROOT" rev-parse --short HEAD) 上" ;;
    *) pb_fail "提交失败"; exit 1 ;;
  esac
  git -C "$PB_ROOT" tag -a "$tag" -m "PushBox Windows v$PB_TARGET" || { pb_fail "打标签失败"; exit 1; }
  pb_ok "标签 $tag 已在本地"
fi

# ---- 6. 推送 ----
pb_step "6/6 推送"
pb_push "$(pb_current_branch)" "$tag"

cat <<SUMMARY

🖥️  Windows v$PB_TARGET
   标签        $tag
   触发的工作流 build-msi.yml（tag 事件 → dotnet publish + Inno 编译 + 发 GitHub Release）
   产物        PushBox-Setup-$PB_TARGET-win-x64.exe
   注意        安装包**未签名**（CODESIGN_CERT_BASE64 / CODESIGN_PASSWORD 没配时 CI 的 Sign installer 是 skipped），
               首次运行会有 SmartScreen 提示；per-user 安装，卸载只清指向 {app} 的注册表键。
   发布后复核   https://github.com/iamplusdev/push-box/releases/tag/$tag
SUMMARY
