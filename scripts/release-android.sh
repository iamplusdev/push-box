#!/usr/bin/env bash
# 发布 Android：GitHub Release 上挂 pushbox-release-<X.Y.Z>.apk（文件名只带版本号，不带 android-v 前缀）。
#
#   bash scripts/release-android.sh                # 自动取 android-v* 最新标签 +1 补丁位
#   bash scripts/release-android.sh 1.2.0
#   bash scripts/release-android.sh --bump=minor
#   bash scripts/release-android.sh --dry-run
#   bash scripts/release-android.sh --push         # 真推到 github（触发 build-apk.yml 出 APK 并发 Release）
#
# 顺序同样是"先本地测试，再写版本号，再打标签"。
# **versionCode 只许涨**：它由 X.Y.Z 推导（major*10000 + minor*100 + patch），写小了老用户装不上
# （INSTALL_FAILED_VERSION_DOWNGRADED），所以本脚本会拦下"不比已有标签新"的目标版本。

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/release-common.sh"

COMPONENT="android"
TAG_PREFIX="android-v"

pb_usage() {
  cat <<'USAGE'
用法: bash scripts/release-android.sh [X.Y.Z] [选项]

选项:
  --bump=patch|minor|major  不给版本号时按这个档位从最新 android-v* 标签递进（默认 patch）
  --dry-run, -n             只跑构建与算版本，不写文件、不提交、不打标签
  --yes, -y                 不再问确认
  --push                    提交与标签真推到 github（不带就只留在本地）
  --skip-tests              跳过本地构建（不推荐）
  --help, -h                本说明
USAGE
}

pb_parse_args "$@"

# ---- 1. 定版本 ----
pb_step "1/6 定版本号"
latest="$(pb_latest_tag "$TAG_PREFIX")"
intree="$(pb_intree_version "$COMPONENT" || true)"
intree_code="$(pb_intree_version_code || true)"
pb_info "最新标签 ${TAG_PREFIX}${latest:-（无）}；build.gradle.kts 现值 versionName=${intree:-（读不到）} versionCode=${intree_code:-（读不到）}"

if [ -z "$PB_TARGET" ]; then
  base="${latest:-$intree}"
  [ -z "$base" ] && { pb_fail "既没有 $TAG_PREFIX* 标签，也读不出 versionName，请显式给一个 X.Y.Z"; exit 1; }
  PB_TARGET="$(pb_bump "$base" "$PB_BUMP")"
  pb_info "按 --bump=$PB_BUMP 从 $base 递进 → $PB_TARGET"
fi
pb_parse_version "$PB_TARGET" || { pb_fail "版本号必须是严格的 X.Y.Z，收到的是 '$PB_TARGET'"; exit 1; }
pb_split_version "$PB_TARGET"
target_code=$(( PB_VMAJOR * 10000 + PB_VMINOR * 100 + PB_VPATCH ))
if [ -n "$latest" ] && ! pb_version_gt "$PB_TARGET" "$latest"; then
  pb_fail "$PB_TARGET 不比已有标签 $latest 新。versionCode 只许涨，写小了老用户装不上（INSTALL_FAILED_VERSION_DOWNGRADED）。"
  exit 1
fi
pb_ok "目标版本 $PB_TARGET（versionCode=$target_code，标签 ${TAG_PREFIX}${PB_TARGET}）"

# ---- 2. 工作区 ----
pb_step "2/6 检查工作区"
if [ "$PB_DRY_RUN" != 1 ]; then
  pb_require_clean || exit 1
  pb_ok "工作区干净（分支 $(pb_current_branch)）"
else
  n="$(pb_dirty_files)"; pb_warn "dry-run：工作区有 $n 个文件未提交，真发布时会被拦下"
fi

# ---- 3. 本地构建 ----
pb_step "3/6 本地构建"
if [ "$PB_SKIP_TESTS" = 1 ]; then
  pb_warn "已跳过（--skip-tests）"
elif [ "$PB_DRY_RUN" = 1 ]; then
  pb_info "[dry-run] 本来会跑：gradlew :app:assembleDebug 与 :app:assembleRelease，并核签名指纹"
else
  # release 签名优先取 CI 的四个 ANDROID_KEYSTORE_*，本机 fallback 到 android/dev/pushbox-release.jks
  # 配 android/local.properties 里的口令。两者都没有时 assembleRelease 会被 requireReleaseSigning 拦下——
  # 那是好事：未签名的 release 包混进 Releases，客户端「检查更新」也装不上。
  if [ ! -f "$PB_ROOT/android/dev/pushbox-release.jks" ] && [ -z "${ANDROID_KEYSTORE_BASE64:-}" ]; then
    pb_fail "没有 release 签名材料：android/dev/pushbox-release.jks 不在，ANDROID_KEYSTORE_BASE64 也没设。"
    exit 1
  fi
  pb_info "debug 构建"
  ( cd "$PB_ROOT/android" && ./gradlew :app:assembleDebug --no-daemon ) \
    || { pb_fail "debug 构建没过，发布中止（工作区未改动）"; exit 1; }
  pb_info "release 构建"
  ( cd "$PB_ROOT/android" && ./gradlew :app:assembleRelease --no-daemon ) \
    || { pb_fail "release 构建没过，发布中止"; exit 1; }

  apk="$PB_ROOT/android/app/build/outputs/apk/release/app-release.apk"
  [ -f "$apk" ] || { pb_fail "找不到 release APK：$apk"; exit 1; }
  pb_ok "release APK $(du -h "$apk" | cut -f1)"

  # 换签名 key 等于让全部老用户卸载重装，所以指纹每次都对一遍基线。
  # 判据与 build-apk.yml 里那一步逐字同源：锚 `certificate SHA-256 digest:` 整串
  # （--verbose 还会打 public key SHA-256，那是另一个数），取**每一个** signer 的指纹，
  # 全部小写后要求基线在其中出现。
  baseline="$PB_ROOT/android/release-cert.sha256"
  if [ -f "$baseline" ]; then
    signer="$(ls "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
    [ -z "$signer" ] && signer="$(ls /d/Apps/DE/Android/sdk/build-tools/*/apksigner* 2>/dev/null | sort -V | tail -1 || true)"
    if [ -n "$signer" ]; then
      certs="$("$signer" verify --print-certs "$apk" 2>/dev/null || true)"
      if printf '%s' "$certs" | grep -qi 'android debug'; then
        pb_fail "release APK 用的是 debug 签名，不能作为正式产物发布"
        exit 1
      fi
      expected="$(tr -d '[:space:]' < "$baseline")"
      digests="$(printf '%s\n' "$certs" | tr -d '\r' | sed -n 's/.*certificate SHA-256 digest: *//p' | tr 'A-F' 'a-f')"
      if [ -z "$digests" ]; then
        pb_fail "apksigner 没打印出任何证书 SHA-256，判据本身坏了（别当成通过）"
        exit 1
      fi
      if printf '%s\n' "$digests" | grep -qx "$expected"; then
        pb_ok "签名指纹与基线一致（$expected）"
      else
        pb_fail "签名证书与基线不符，这等于换 key：老用户要卸载重装、历史消息全丢。"
        echo "   基线：$expected" >&2
        echo "   实际：" >&2; printf '%s\n' "$digests" | sed 's/^/     /' >&2
        exit 1
      fi
    else
      pb_warn "没找到 apksigner，跳过指纹比对（ANDROID_HOME=${ANDROID_HOME:-未设}）。CI 那一步会兜底，但别把'CI 会拦'当成本机可以不判"
    fi
  else
    pb_fail "缺 android/release-cert.sha256，没法核对证书指纹"
    exit 1
  fi
fi

# ---- 4. 写版本号 ----
pb_step "4/6 写版本号"
if [ "$PB_DRY_RUN" = 1 ]; then
  pb_info "[dry-run] 本来会跑：bash scripts/set-version.sh --only=android $PB_TARGET"
else
  ( cd "$PB_ROOT" && bash scripts/set-version.sh --only=android "$PB_TARGET" ) || { pb_fail "写版本号失败"; exit 1; }
  actual_code="$(pb_intree_version_code || true)"
  [ "$actual_code" = "$target_code" ] || { pb_fail "versionCode 写成了 $actual_code，期望 $target_code"; exit 1; }
  pb_ok "versionName=$PB_TARGET versionCode=$actual_code"
fi

# ---- 5. 提交与打标签 ----
pb_step "5/6 提交与打标签"
tag="${TAG_PREFIX}${PB_TARGET}"
if [ "$PB_DRY_RUN" = 1 ]; then
  pb_info "[dry-run] 本来会提交版本号改动，并打标签 $tag"
else
  pb_confirm "即将提交并打标签 $tag（本地）" || exit 1
  git -C "$PB_ROOT" add -A
  git -C "$PB_ROOT" commit -m "chore(release): $COMPONENT v$PB_TARGET" || { pb_fail "提交失败"; exit 1; }
  git -C "$PB_ROOT" tag -a "$tag" -m "PushBox Android v$PB_TARGET (versionCode $target_code)" || { pb_fail "打标签失败"; exit 1; }
  pb_ok "已提交并打标签 $tag"
fi

# ---- 6. 推送 ----
pb_step "6/6 推送"
pb_push "$(pb_current_branch)" "$tag"

cat <<SUMMARY

📱 Android v$PB_TARGET（versionCode $target_code）
   标签        $tag
   触发的工作流 build-apk.yml（tag 事件 → 构建 release APK、apksigner 验签、发 GitHub Release）
   产物        pushbox-release-$PB_TARGET.apk（文件名只带版本号，不带 android-v 前缀，见 build-apk.yml「Collect APKs」）
   要顺手做的   备份 android/app/build/outputs/mapping/release/mapping.txt 到 Git 之外
               （实测 205 MB，不备线上崩溃栈还原不了；禁止提交进仓库）
   发布后复核   https://github.com/iamplusdev/push-box/releases/tag/$tag
SUMMARY
