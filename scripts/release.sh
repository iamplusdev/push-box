#!/usr/bin/env bash
# 发布总入口：汇总三端版本、算出下一个号、按需分发到 release-<端>.sh。
#
#   bash scripts/release.sh                          # 只看汇总（不发版）
#   bash scripts/release.sh --component=server       # 发服务端，自动取 server-v* 最新标签 +1 补丁位
#   bash scripts/release.sh --component=android,windows --bump=minor
#   bash scripts/release.sh --all --dry-run          # 三端都走一遍，但不写文件、不提交、不打标签
#   bash scripts/release.sh --component=server 1.2.0 --push
#
# 三条硬口径（与 release-common.sh 一致）：
#   1. **本地测试通过才许发布** —— 测试在被分发的脚本里跑，红了就退出，版本号一个字节都没改。
#   2. **发布由人手动执行** —— 本脚本不会被任何工作流触发；推远端要显式 `--push`，且只推 github。
#   3. **版本号只从标签算** —— 现值只用来对账（"标签和仓库里写的是不是同一个号"），不参与递进，
#      否则"跑过 set-version.sh 但没打标签"这种半成品状态会把基线抬错。
#
# 标签按端分家：server-v* / android-v* / windows-v*，三端号可以不相等。
# 旧的三端同号标签 v* 仍然被三条发布工作流认，但这里既不用它算基线、也不生成它。

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/release-common.sh"

pb_usage() {
  cat <<'USAGE'
用法: bash scripts/release.sh [X.Y.Z] [选项]

选端（不给就只打印汇总，什么都不改）:
  --component=server|android|windows   逗号可多选，如 --component=android,windows
  --all, -a                            三端都发（等价 --component=server,android,windows）
  --list, -l                           只打印汇总表后退出

选项（原样转发给 release-<端>.sh）:
  --bump=patch|minor|major  不给版本号时按这个档位从该端最新标签递进（默认 patch）
  --dry-run, -n             只算版本与跑测试，不写文件、不提交、不打标签
  --yes, -y                 不再问确认（本脚本问一次，被分发的脚本就不再问）
  --push                    提交与标签真推到 github（不带就只留在本地）
  --skip-tests              跳过本地构建与测试（不推荐）
  --help, -h                本说明

注意:
  · 给了显式 X.Y.Z 又选了多端，选中的每一端都会**用同一个号**（其中某端号不够新就会在那一步被判死）。
  · --all --push 一次推出三个标签，等于同时触发 docker-publish / build-apk / build-msi 三条链。
USAGE
}

# ---- 参数 ----
# --component / --all / --list 是本脚本自己的，pb_parse_args 不认，先摘出来再把剩下的交给它。
COMPONENTS=()
LIST_ONLY=0
REST=()
while [ $# -gt 0 ]; do
  case "$1" in
    --component=*|--only=*) IFS=',' read -r -a COMPONENTS <<< "${1#*=}" ;;
    --all|-a)               COMPONENTS=(server android windows) ;;
    --list|-l)              LIST_ONLY=1 ;;
    --help|-h)              pb_usage; exit 0 ;;
    *)                      REST+=("$1") ;;
  esac
  shift
done
pb_parse_args ${REST[@]+"${REST[@]}"}

# 去重 + 认名字。名字写错要在动任何文件之前就报出来。
SELECTED=()
for c in ${COMPONENTS[@]+"${COMPONENTS[@]}"}; do
  c="$(printf '%s' "$c" | tr -d '[:space:]')"
  [ -z "$c" ] && continue
  case "$c" in
    server|android|windows) ;;
    all) SELECTED=(server android windows); continue ;;
    *) pb_fail "不认识的端：'$c'（可选 server / android / windows / all）"; exit 1 ;;
  esac
  case " ${SELECTED[*]-} " in *" $c "*) continue ;; esac
  SELECTED+=("$c")
done

# ---- 每端的辅助 ----
# 版本号从哪儿读、标签前缀长什么样，都在 release-common.sh 里（与被分发的脚本共用同一份，
# 否则"汇总表说 1.1.5、子脚本按别的号递进"这种分歧从输出上看不出来）。
pb_script_of()  { printf '%s/release-%s.sh' "$PB_SCRIPT_DIR" "$1"; }
pb_artifact_of() {
  case "$1" in
    server)  printf 'Docker Hub 镜像 iamplusdev/push-box:%s（docker-publish.yml）' "$2" ;;
    android) printf 'Releases 上的 pushbox-release-%s.apk（build-apk.yml；文件名只带版本号，不带 android-v 前缀）' "$2" ;;
    windows) printf 'Releases 上的 PushBox-Setup-%s-win-x64.exe（build-msi.yml）' "$2" ;;
  esac
}

# ---- 汇总 ----
pb_step "版本汇总（标签是唯一基线，现值只用来对账）"
# 表头手写空格：printf 的 %-Ns 按**字节**补位，中文一个字算 3 字节，用 printf 排中文表头一定歪。
printf '  端       最新标签         仓库现值   下一个 patch/minor/major 对账\n'
printf '  %-8s %-16s %-10s %-24s %s\n' "----" "--------" "--------" "------------------------" "----"

declare -A LATEST INTREE NEXT_P NEXT_N NEXT_M
for c in server android windows; do
  pfx="$(pb_tag_prefix "$c")"
  LATEST[$c]="$(pb_latest_tag "$pfx")"
  INTREE[$c]="$(pb_intree_version "$c" || true)"
  base="${LATEST[$c]:-${INTREE[$c]}}"
  if [ -z "$base" ]; then
    NEXT_P[$c]="?"; NEXT_N[$c]="?"; NEXT_M[$c]="?"
    note="既没有 ${pfx}* 标签也读不出现值，得显式给版本号"
  else
    NEXT_P[$c]="$(pb_bump "$base" patch)"
    NEXT_N[$c]="$(pb_bump "$base" minor)"
    NEXT_M[$c]="$(pb_bump "$base" major)"
    if [ -z "${LATEST[$c]}" ]; then
      note="从未打过 ${pfx}* 标签（基线取自现值）"
    elif [ "${INTREE[$c]}" = "${LATEST[$c]}" ]; then
      note="一致"
    elif pb_version_gt "${INTREE[$c]}" "${LATEST[$c]}"; then
      note="⚠ 现值领先标签：写过版本号但没打标签"
    else
      note="⚠ 现值落后标签：有人只打了标签没写版本号"
    fi
  fi
  printf '  %-8s %-16s %-10s %-24s %s\n' \
    "$c" "$(pb_tag_disp "$pfx" "${LATEST[$c]}")" "${INTREE[$c]:-（读不到）}" \
    "${NEXT_P[$c]} / ${NEXT_N[$c]} / ${NEXT_M[$c]}" "$note"
done

dirty="$(pb_dirty_files)"
if [ "$dirty" = "0" ]; then
  printf '\n  工作区干净（分支 %s）\n' "$(pb_current_branch)"
else
  printf '\n  ⚠ 工作区有 %s 个文件未提交 —— 真发布时会被拦下（标签指向提交，不指向工作区）\n' "$dirty"
fi

branch="$(pb_current_branch)"
if [ "$branch" != "main" ]; then
  printf '  ⚠ 当前分支是 %s 而不是 main：标签会打在这个分支的提交上，而 site-deploy.yml 只认 main 的推送（官网不会跟着更新）\n' "$branch"
fi

[ "$LIST_ONLY" = 1 ] && exit 0

if [ ${#SELECTED[@]} -eq 0 ]; then
  cat <<'HINT'

没有选端，到此为止（什么都没改）。要发版：
  bash scripts/release.sh --component=server            # 单端
  bash scripts/release.sh --component=android,windows   # 多端
  bash scripts/release.sh --all --dry-run               # 三端先空跑一遍
HINT
  exit 0
fi

# ---- 分发前的确认 ----
pb_step "即将发布：${SELECTED[*]}"
for c in "${SELECTED[@]}"; do
  [ -x "$(pb_script_of "$c")" ] || [ -f "$(pb_script_of "$c")" ] \
    || { pb_fail "找不到 $(pb_script_of "$c")"; exit 1; }
done

if [ -n "$PB_TARGET" ] && [ ${#SELECTED[@]} -gt 1 ]; then
  pb_warn "给了显式版本号 $PB_TARGET 又选了 ${#SELECTED[@]} 端：这几端都会用同一个号，哪一端号不够新就会在那一步被判死"
fi

CHILD_ARGS=()
[ -n "$PB_TARGET" ]       && CHILD_ARGS+=("$PB_TARGET")
CHILD_ARGS+=("--bump=$PB_BUMP")
[ "$PB_DRY_RUN" = 1 ]     && CHILD_ARGS+=("--dry-run")
[ "$PB_SKIP_TESTS" = 1 ]  && CHILD_ARGS+=("--skip-tests")

NEED_CONFIRM=1
[ "$PB_ASSUME_YES" = 1 ] && NEED_CONFIRM=0
[ "$PB_DRY_RUN" = 1 ]    && NEED_CONFIRM=0
if [ "$NEED_CONFIRM" = 1 ]; then
  msg="发布 ${SELECTED[*]}：跑本地测试 → 写版本号 → 提交 → 打标签"
  [ "$PB_PUSH" = 1 ] && msg="$msg → 推 github（不可逆，标签一推就开始出制品）"
  pb_confirm "$msg" || exit 1
  CHILD_ARGS+=("--yes")   # 这里已经问过了，别再让每个子脚本问一遍
else
  [ "$PB_ASSUME_YES" = 1 ] && CHILD_ARGS+=("--yes")
fi
[ "$PB_PUSH" = 1 ] && CHILD_ARGS+=("--push")

# ---- 逐个发 ----
# 不并行：三端都要写文件、都要提交，并行会把彼此的工作区搅在一起。
# 某一端失败就停下——后面的端会建在一个"发了一半"的仓库上，继续只会更难收拾。
# 结果不用关联数组存：bash 里 `declare -A X` 之后即使一个键都没写过，`${#X[@]}` 在 set -u 下
# 也会报 unbound variable（实测 bash 5.3），而"一个端都没发成"恰好就是要走的那条分支。
RESULTS=""      # 每行一条：端|上一个标签|本次标签|推送
DONE_COUNT=0
FAILED=""
for c in "${SELECTED[@]}"; do
  pb_step "发布 $c"
  pb_info "bash $(pb_script_of "$c") ${CHILD_ARGS[*]}"
  from="${LATEST[$c]:-（无）}"
  [ "$from" = "（无）" ] || from="$(pb_tag_of "$c" "$from")"
  if ! bash "$(pb_script_of "$c")" ${CHILD_ARGS[@]+"${CHILD_ARGS[@]}"}; then
    FAILED="$c"
    pb_fail "$c 发布失败，后面的端不继续（已完成的端保留其提交与本地标签）"
    break
  fi
  if [ "$PB_DRY_RUN" = 1 ]; then
    # 子脚本在 dry-run 下什么都没写，这会儿去读标签读到的还是发版之前那个；照实标出来，
    # 否则统计里会出现"上一个标签 == 本次标签"这种像是发了个寂寞的行。
    now="（dry-run，未打标签）"
  else
    now="$(git -C "$PB_ROOT" tag --list "$(pb_tag_prefix "$c")*" --sort=-v:refname | head -1)"
  fi
  push_note="$( [ "$PB_PUSH" = 1 ] && echo "已推 github" || echo "仅本地" )"
  RESULTS+="$(printf '%s|%s|%s|%s' "$c" "$from" "$now" "$push_note")"$'\n'
  DONE_COUNT=$((DONE_COUNT + 1))
  pb_ok "$c 完成"
done

# ---- 统计 ----
# 这里不做表格：本次标签那一栏在 dry-run 下是中文说明、推送那一栏也是中文，
# printf 的 %-Ns 按字节补位，排出来必歪。逐端一段反而读得清。
pb_step "本次发布统计"
if [ "$DONE_COUNT" = 0 ]; then
  pb_warn "没有任何一端走完（第一个就失败了）"
else
  printf '%s' "$RESULTS" | sort | while IFS='|' read -r c from now push_note; do
    [ -z "$c" ] && continue
    printf '  %-8s %s → %s\n' "$c" "$from" "$now"
    if [ "$PB_DRY_RUN" = 1 ]; then
      printf '           dry-run：没写文件、没提交、没标签、没制品\n'
    else
      printf '           制品   %s\n' "$(pb_artifact_of "$c" "${now#"$c"-v}")"
      printf '           推送   %s\n' "$push_note"
    fi
  done
  printf '\n'
fi

if [ -n "$FAILED" ]; then
  # 把"剩下的端"算出来给人抄：--bump 那条路重跑原命令会给已完成的端再发一个号，
  # 而"该跑哪条命令"这种话在失败当下最没人愿意自己想。
  remaining=""
  seen_failed=0
  for c in "${SELECTED[@]}"; do
    if [ "$c" = "$FAILED" ]; then seen_failed=1; continue; fi
    [ "$seen_failed" = 1 ] && remaining="${remaining:+$remaining,}$c"
  done
  cat <<EOF
❌ 停在 $FAILED。工作区状态：
$(git -C "$PB_ROOT" status --porcelain | head -10)
   重跑之前先想清楚已经发出去的那一端：
     · 显式给了版本号 → 直接重跑同一条命令即可，已完成的端会因为"版本号不比自己的新标签新"被判死，不会重复发。
     · 用的是 --bump → **别直接重跑**：基线已经抬上去了，重跑会给已完成的端再发一个号。
       改成只发剩下的端：--component=$FAILED${remaining:+,$remaining}
EOF
  exit 1
fi

if [ "$PB_DRY_RUN" = 1 ]; then
  pb_ok "dry-run 走完：没有写文件、没有提交、没有标签、没有推送"
  exit 0
fi

pb_info "本次新增的提交："
git -C "$PB_ROOT" log --oneline -"$DONE_COUNT" | sed 's/^/     /'
cat <<EOF

📊 收尾
   推送状态     $( [ "$PB_PUSH" = 1 ] && echo "已推 github（工作流已经开始跑）" || echo "只在本地：git push github $(pb_current_branch) && git push github <标签>" )
   工作流复核   https://github.com/iamplusdev/push-box/actions
   发布后请照 docs/release/发布检查清单.md 的「发布后监控」逐条走一遍
   本地产物     output/ 下按端存放（jar / apk / exe），全是这一轮版本号重出的那一份
EOF
