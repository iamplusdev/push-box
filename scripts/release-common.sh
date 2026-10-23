#!/usr/bin/env bash
# 发布脚本的公共部分。不单独执行，由 release-server.sh / release-android.sh / release-windows.sh source。
#
# 口径来自维护者的两条硬要求：
#   1. **本地测试通过才许发布** —— 所以顺序是"先跑构建与测试，再写版本号、再打标签"，
#      测试红了就退出，工作区一个字节都没改。
#   2. **发布由人手动执行** —— 这些脚本不会自己被触发，也不会自动推远端；推送要显式 `--push`。
#
# 标签按端分家（server-v* / android-v* / windows-v*），三端版本号各自递增、不要求相等。
# 旧的三端同号标签 v* 仍然被 docker-publish / build-apk / build-msi 认，但这里不生成它。

# ---- 路径 ----
PB_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PB_ROOT="$(cd "$PB_SCRIPT_DIR/.." && pwd)"

# ---- 输出 ----
pb_info()  { printf '\033[36m▸\033[0m %s\n' "$*"; }
pb_ok()    { printf '\033[32m✅\033[0m %s\n' "$*"; }
pb_warn()  { printf '\033[33m⚠️  %s\033[0m\n' "$*" >&2; }
pb_fail()  { printf '\033[31m❌ %s\033[0m\n' "$*" >&2; }
pb_step()  { printf '\n\033[1m── %s ──\033[0m\n' "$*"; }

# ---- 版本号 ----
# 只认严格 X.Y.Z。解析不出来一律报错退出、不改任何文件——写进更小的 versionCode
# 会让 Android 老用户装不上（INSTALL_FAILED_VERSION_DOWNGRADED）。
pb_parse_version() {
  [[ "$1" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]
}

# 把 X.Y.Z 拆成三个全局变量 PB_VMAJOR / PB_VMINOR / PB_VPATCH
pb_split_version() {
  [[ "$1" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || return 1
  PB_VMAJOR="${BASH_REMATCH[1]}"; PB_VMINOR="${BASH_REMATCH[2]}"; PB_VPATCH="${BASH_REMATCH[3]}"
}

# pb_bump <当前版本> <patch|minor|major> → stdout 是下一个版本
pb_bump() {
  pb_split_version "$1" || { pb_fail "当前版本 '$1' 不是 X.Y.Z，算不出下一个版本"; return 1; }
  case "$2" in
    patch) printf '%s.%s.%s\n' "$PB_VMAJOR" "$PB_VMINOR" $((PB_VPATCH + 1)) ;;
    minor) printf '%s.%s.0\n' "$PB_VMAJOR" $((PB_VMINOR + 1)) ;;
    major) printf '%s.0.0\n' $((PB_VMAJOR + 1)) ;;
    *) pb_fail "未知的递进档位 '$2'（可选 patch / minor / major）"; return 1 ;;
  esac
}

# pb_version_gt A B → A 严格大于 B 时返回 0。用 sort -V 判，别自己写逐段比较。
pb_version_gt() {
  [ "$1" = "$2" ] && return 1
  [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1)" = "$1" ]
}

# pb_latest_tag <标签前缀，如 server-v> → stdout 是最新的版本号（不带前缀与 v），没有标签时输出空
pb_latest_tag() {
  local t
  t="$(git -C "$PB_ROOT" tag --list "$1*" --sort=-v:refname | head -1)"
  [ -z "$t" ] && return 0
  t="${t#"$1"}"; printf '%s\n' "${t#v}"
}

# ---- 仓库里的现值 ----
# 这三条读取表达式只在这里写一遍：release.sh 的汇总表与 release-<端>.sh 的定版本步骤必须读到
# 同一个数，否则"汇总说现值 1.1.5、子脚本按别的号递进"这种分歧从输出上看不出来。
# 挪文件时改这里，同时改 scripts/set-version.sh 里写入的那一处（两边是一对）。
pb_intree_version() {
  case "$1" in
    server)  grep -m1 -oP '(?<=<version>)[^<]+'       "$PB_ROOT/pom.xml" ;;
    android) grep -m1 -oP '(?<=versionName = ")[^"]+' "$PB_ROOT/apps/android/app/build.gradle.kts" ;;
    windows) grep -m1 -oP '(?<=<Version>)[^<]+'       "$PB_ROOT/windows/PushBox.Windows/PushBox.Windows.csproj" ;;
    *) pb_fail "pb_intree_version：不认识的端 '$1'"; return 1 ;;
  esac
}

# Android 另有一个 versionCode：它只许涨，写小了老用户会撞上 INSTALL_FAILED_VERSION_DOWNGRADED
pb_intree_version_code() {
  grep -m1 -oP '(?<=versionCode = )[0-9]+' "$PB_ROOT/apps/android/app/build.gradle.kts"
}

pb_tag_prefix() { printf '%s-v' "$1"; }
pb_tag_of()     { printf '%s-v%s' "$1" "$2"; }

# ---- 仓库状态 ----
pb_dirty_files() {
  git -C "$PB_ROOT" status --porcelain | wc -l | tr -d ' '
}

# 打标签前必须干净：标签指向的是一个提交，工作区里没提交的东西不会跟着走，
# 于是"我本地测过的那份代码"和"标签指的那份代码"会是两个东西——这类假绿最难查。
pb_require_clean() {
  local n; n="$(pb_dirty_files)"
  if [ "$n" != "0" ]; then
    pb_fail "工作区有 $n 个文件未提交。先提交或 stash 再发布："
    git -C "$PB_ROOT" status --porcelain | head -20 >&2
    pb_warn "（标签指向提交，不指向工作区。带着未提交的改动发版，等于发布一份你没测过的代码。）"
    return 1
  fi
}

pb_current_branch() { git -C "$PB_ROOT" rev-parse --abbrev-ref HEAD; }

# ---- 通用参数解析 ----
# 认这些：X.Y.Z（目标版本）、--bump=patch|minor|major、--dry-run、--yes、--push、--skip-tests
PB_TARGET=""
PB_BUMP="patch"
PB_DRY_RUN=0
PB_ASSUME_YES=0
PB_PUSH=0
PB_SKIP_TESTS=0

pb_parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --bump=*)     PB_BUMP="${1#--bump=}" ;;
      --dry-run|-n) PB_DRY_RUN=1 ;;
      --yes|-y)     PB_ASSUME_YES=1 ;;
      --push)       PB_PUSH=1 ;;
      --skip-tests) PB_SKIP_TESTS=1; pb_warn "--skip-tests：跳过本地测试。这与'测过才发'的口径相悖，只在 CI 已经绿过、本机工具缺失时用" ;;
      --help|-h)    pb_usage; exit 0 ;;
      -*)           pb_fail "不认识的参数：$1"; pb_usage; return 1 ;;
      *)            if [ -z "$PB_TARGET" ]; then PB_TARGET="$1"; else pb_fail "多余的参数：$1"; return 1; fi ;;
    esac
    shift
  done
}

pb_confirm() {
  [ "$PB_ASSUME_YES" = 1 ] && return 0
  [ "$PB_DRY_RUN" = 1 ] && return 0
  printf '\n\033[1m%s\033[0m 确认继续？[y/N] ' "$1"
  local ans; read -r ans
  case "$ans" in y|Y|yes|YES) return 0 ;; *) pb_info "已取消"; return 1 ;; esac
}

# ---- 产物 ----
# 各端编译结果统一收进 output/（在 .gitignore 里）。调用点都在版本号写完之后：
# 第 3 步那个包是拿来冒烟测试的，名字里还是上一版，直接拷会让文件名与内容对不上。
pb_collect() {
  local src="$1" sub="$2" name="$3"
  if [ ! -f "$src" ]; then
    pb_warn "没有产物可收：$src"
    return 1
  fi
  mkdir -p "$PB_ROOT/output/$sub"
  cp -f "$src" "$PB_ROOT/output/$sub/$name"
  pb_ok "产物已收进 output/$sub/$name（$(du -h "$PB_ROOT/output/$sub/$name" | cut -f1)）"
}

# ---- 远端 ----
# 只推 github：gitee 与 gitea 不在授权范围内（维护者明确说过），要推自己手动来。
PB_REMOTE="github"

pb_push() {
  local branch="$1" tag="$2"
  if [ "$PB_PUSH" != 1 ]; then
    pb_info "没有 --push，提交与标签都只在本地。要发出去："
    echo "    git push $PB_REMOTE $branch && git push $PB_REMOTE $tag"
    return 0
  fi
  if [ "$PB_DRY_RUN" = 1 ]; then
    pb_info "[dry-run] 本来会执行：git push $PB_REMOTE $branch && git push $PB_REMOTE $tag"
    return 0
  fi
  pb_info "推送 $branch 与 $tag 到 $PB_REMOTE（这一步不可逆：标签一推，三条工作流就开始出制品）"
  git -C "$PB_ROOT" push "$PB_REMOTE" "$branch" || { pb_fail "推分支失败"; return 1; }
  git -C "$PB_ROOT" push "$PB_REMOTE" "$tag"   || { pb_fail "推标签失败（分支已经推上去了，别重复提交，手动补推标签）"; return 1; }
  pb_ok "已推送：$PB_REMOTE/$branch 与 $tag"
}
