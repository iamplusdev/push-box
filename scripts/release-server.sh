#!/usr/bin/env bash
# 发布服务端：docker.io/iamplusdev/push-box 镜像 + 官网版本徽章。
#
#   bash scripts/release-server.sh                 # 自动取 server-v* 最新标签 +1 补丁位，dry 之外还会问一次
#   bash scripts/release-server.sh 1.2.0           # 指定版本
#   bash scripts/release-server.sh --bump=minor    # 指定递进档位
#   bash scripts/release-server.sh --dry-run       # 只跑测试与算版本，不写文件、不提交、不打标签
#   bash scripts/release-server.sh --push          # 打标签之后真推到 github（不可逆：会触发 docker-publish.yml）
#
# 顺序是刻意的：**先跑本地测试，再写版本号，再打标签**。测试红了就退出，工作区一个字节都没改。
# 标签 `server-vX.Y.Z` 一推，`docker-publish.yml` 就往 Docker Hub 真推镜像，所以推送要显式 --push。

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/release-common.sh"

COMPONENT="server"
TAG_PREFIX="server-v"

pb_usage() {
  cat <<'USAGE'
用法: bash scripts/release-server.sh [X.Y.Z] [选项]

选项:
  --bump=patch|minor|major  不给版本号时按这个档位从最新 server-v* 标签递进（默认 patch）
  --dry-run, -n             只跑测试与算版本，不写文件、不提交、不打标签
  --yes, -y                 不再问确认
  --push                    提交与标签真推到 github（不带就只留在本地）
  --skip-tests              跳过本地构建与测试（不推荐）
  --help, -h                本说明
USAGE
}

pb_parse_args "$@"

# ---- 1. 定版本 ----
pb_step "1/6 定版本号"
latest="$(pb_latest_tag "$TAG_PREFIX")"
intree="$(pb_intree_version "$COMPONENT" || true)"
pb_info "最新标签 ${TAG_PREFIX}${latest:-（无）}；仓库里 pom 现值 ${intree:-（读不到）}"

if [ -z "$PB_TARGET" ]; then
  base="${latest:-$intree}"
  [ -z "$base" ] && { pb_fail "既没有 $TAG_PREFIX* 标签，pom 里也读不出版本，请显式给一个 X.Y.Z"; exit 1; }
  PB_TARGET="$(pb_bump "$base" "$PB_BUMP")"
  pb_info "按 --bump=$PB_BUMP 从 $base 递进 → $PB_TARGET"
fi
pb_parse_version "$PB_TARGET" || { pb_fail "版本号必须是严格的 X.Y.Z，收到的是 '$PB_TARGET'"; exit 1; }
pb_split_version "$PB_TARGET"
if [ -n "$latest" ] && ! pb_version_gt "$PB_TARGET" "$latest"; then
  pb_fail "$PB_TARGET 不比已有标签 $latest 新。版本只许涨——写小了 Android 那边会装不上，镜像标签也会覆盖掉别人已经拉过的东西。"
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

# ---- 3. 本地构建与测试 ----
pb_step "3/6 本地构建与测试"
if [ "$PB_SKIP_TESTS" = 1 ]; then
  pb_warn "已跳过（--skip-tests）"
elif [ "$PB_DRY_RUN" = 1 ]; then
  pb_info "[dry-run] 本来会跑：mvn -B -pl server -am test、web-admin 构建、mvn package + 安装页冒烟、release-verify.sh"
else
  # mvn 必须跑在 JDK 21 上（server/pom.xml 要 release=21）。本机默认 JAVA_HOME 指向 JDK 8，
  # 不覆盖的话会在 pushbox-core 直接炸 "class file version 61.0 ... only recognizes up to 52.0"。
  if [ -d /d/Apps/DE/Java/jdk-21.0.8 ]; then
    export JAVA_HOME=/d/Apps/DE/Java/jdk-21.0.8
    export PATH="$JAVA_HOME/bin:$PATH"
    pb_info "JAVA_HOME=$JAVA_HOME"
  else
    pb_warn "没找到 D:/Apps/DE/Java/jdk-21.0.8，用当前 JAVA_HOME=${JAVA_HOME:-（未设）}；需要 JDK 21"
  fi
  java -version 2>&1 | head -1

  pb_info "服务端测试（mvn -B -pl server -am test）"
  # 先删上一轮的 surefire 报告：mvn test 不清 target，而被删掉的测试类（比如随配置重构一起
  # 没了的 DevCredentialsGuardTest）留下的孤儿报告会一直把用例总数抬上去——那样"数一遍报告"
  # 这条闸判的是历史，不是这一轮。删报告比 mvn clean 便宜，且不影响增量编译。
  rm -rf "$PB_ROOT"/server/target/surefire-reports "$PB_ROOT"/core/target/surefire-reports
  ( cd "$PB_ROOT" && mvn -B -pl server -am test ) || { pb_fail "服务端测试没过，发布中止（工作区未改动）"; exit 1; }
  pb_ok "服务端测试通过"

  # 退出码只回答"有没有红"：删了 @Test、改了 include 规则、整类没被 discover，它照样退 0。
  # 所以再数一遍报告。判据与 ci.yml 同源，基线也从 ci.yml 的 `if tests < N` 那一行读——
  # 唯一真值只有一处，两边不会各说一套。
  tests=0; skipped=0; files=0
  for f in "$PB_ROOT"/server/target/surefire-reports/TEST-*.xml "$PB_ROOT"/core/target/surefire-reports/TEST-*.xml; do
    [ -e "$f" ] || continue
    files=$((files + 1))
    t="$(grep -oP '<testsuite[^>]*\btests="\K[0-9]+'   "$f" | head -1)"; tests=$((tests + ${t:-0}))
    s="$(grep -oP '<testsuite[^>]*\bskipped="\K[0-9]+' "$f" | head -1)"; skipped=$((skipped + ${s:-0}))
  done
  [ "$files" = 0 ] && { pb_fail "一份 surefire 报告都没有：测试根本没跑（判据本身坏了，别当成通过）"; exit 1; }
  java_baseline="$(grep -oP '(?<=if tests < )[0-9]+' "$PB_ROOT/.github/workflows/ci.yml" | head -1 || true)"
  pb_info "surefire 汇总：用例 $tests，跳过 $skipped，执行 $((tests - skipped))（ci.yml 基线 ${java_baseline:-读不到}）"
  if [ -n "$java_baseline" ] && [ "$tests" -lt "$java_baseline" ]; then
    pb_fail "用例总数 $tests 低于 ci.yml 基线 $java_baseline：测试被静默裁剪了"
    exit 1
  fi
  if [ "$skipped" != 0 ]; then
    # ci.yml 那边判 skipped == 0，因为它把 DB/S3 凭据喂进了 runner；本机通常没有，于是那 30 来条
    # TestConfig#databaseReady / #objectStorageReady 会被跳过。这不是"本机可以不判"，而是
    # "这条只有 CI 判得动"——所以出声但不拦，别把它写成通过。
    pb_warn "有 $skipped 条被跳过（本机没喂 DB/S3 凭据）。全绿不代表测过：skipped == 0 这条只有 ci.yml 判得动"
  fi

  pb_info "管理台构建（npm ci && npm run build）"
  ( cd "$PB_ROOT/web-admin" && npm ci --no-audit --no-fund && npm run build ) \
    || { pb_fail "管理台构建没过，发布中止"; exit 1; }
  [ -s "$PB_ROOT/web-admin/dist/index.html" ] || { pb_fail "dist/index.html 是空的"; exit 1; }
  pb_ok "管理台构建通过"

  # 出包再真跑一次安装页。有些坑只有 fat jar 里才出现：DriverManager 初始化时用**系统类加载器**
  # 跑 ServiceLoader，而 Spring Boot 把 mysql-connector 放在 BOOT-INF/lib/ 下，于是安装页
  # （它在 Spring 之前跑）直接 getConnection 会报 "No suitable driver found"。上面那 300 来条
  # 单测跑的全是 exploded classpath，那条路系统类加载器本来就看得见驱动——全绿也照不出来。
  pb_info "出包并真跑一次安装页（fat jar 独有的类加载坑）"
  ( cd "$PB_ROOT" && mvn -B -pl server -am -DskipTests package ) || { pb_fail "打包失败，发布中止"; exit 1; }
  pb_info "安装页冒烟（scripts/install-page-smoke.sh）"
  ( cd "$PB_ROOT" && bash scripts/install-page-smoke.sh ) || { pb_fail "安装页冒烟没过，发布中止"; exit 1; }
  pb_ok "安装页在 fat jar 里真跑得起来"

  pb_info "发布闸门（scripts/release-verify.sh）"
  ( cd "$PB_ROOT" && bash scripts/release-verify.sh ) || { pb_fail "release-verify 有 FAIL，发布中止"; exit 1; }
  pb_ok "发布闸门零 FAIL"
fi

# ---- 4. 写版本号 ----
pb_step "4/6 写版本号"
if [ "$PB_DRY_RUN" = 1 ]; then
  pb_info "[dry-run] 本来会跑：bash scripts/set-version.sh --only=server $PB_TARGET"
else
  ( cd "$PB_ROOT" && bash scripts/set-version.sh --only=server "$PB_TARGET" ) || { pb_fail "写版本号失败"; exit 1; }
fi

# ---- 5. 提交与打标签 ----
pb_step "5/6 提交与打标签"
tag="${TAG_PREFIX}${PB_TARGET}"
if [ "$PB_DRY_RUN" = 1 ]; then
  pb_info "[dry-run] 本来会提交版本号改动，并打标签 $tag"
else
  # 写完版本号之后闸门要再跑一次：release-verify 里有"pom == 两份 compose == 站点徽章"那几条自洽断言，
  # 它们只有在版本号真写进去之后才判得动。
  pb_info "复核发布闸门（版本号已写入）"
  ( cd "$PB_ROOT" && bash scripts/release-verify.sh ) || { pb_fail "写入版本号后闸门 FAIL；改动还在工作区，修完再跑一次本脚本"; exit 1; }

  pb_confirm "即将提交并打标签 $tag（本地）" || exit 1
  git -C "$PB_ROOT" add -A
  git -C "$PB_ROOT" commit -m "chore(release): $COMPONENT v$PB_TARGET" || { pb_fail "提交失败"; exit 1; }
  git -C "$PB_ROOT" tag -a "$tag" -m "PushBox 服务端 v$PB_TARGET" || { pb_fail "打标签失败"; exit 1; }
  pb_ok "已提交并打标签 $tag"
fi

# ---- 6. 推送 ----
pb_step "6/6 推送"
pb_push "$(pb_current_branch)" "$tag"

cat <<SUMMARY

📦 服务端 v$PB_TARGET
   标签        $tag
   镜像        docker.io/iamplusdev/push-box:$PB_TARGET（另有 $PB_VMAJOR.$PB_VMINOR 与 latest）
   触发的工作流 docker-publish.yml（tag 事件 → 真推 Hub）、ci.yml、site-deploy.yml（site/ 变过才发）
   发布后复核   https://github.com/iamplusdev/push-box/actions
               curl -sS 'https://hub.docker.com/v2/repositories/iamplusdev/push-box/tags?page_size=50'
               （Hub 从 Windows 宿主连不通，先在 WSL 里跑）
SUMMARY
