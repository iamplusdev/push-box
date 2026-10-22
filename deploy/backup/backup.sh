#!/usr/bin/env bash
# §11.2 备份。库内是明文（加密只覆盖"服务端→客户端"一跳），所以上传异地前必须 age/gpg 加密。
# 产物：
#   pushbox-<UTC>.sql.gz.age    —— mysqldump --single-transaction（InnoDB 一致性快照，不锁表）
#   rustfs-<UTC>.tar.age        —— RustFS/S3 数据卷打包（对象/图片；服务端是唯一归档源，丢了不可恢复）
#   media-<UTC>.tar.age         —— 本地文件系统存储备份（PB_STORAGE_TYPE=local 时）
#
# 依赖：mysqldump、age（默认；用 gpg 见下方注释）。挂到 cron/systemd timer 定时跑，产物再同步异地。
#
# 用法：
#   MYSQL_HOST=<DB_HOST> PB_DB_PASSWORD=... PB_S3_ACCESS_KEY=... \
#   AGE_RECIPIENT=<age 公钥或 ssh 公钥> [RUSTFS_DATA_DIR=/path/to/data/rustfs] [MEDIA_DIR=/path/to/data/media] ./backup.sh
#
# 复用远端 RustFS（拓扑 A，没有本地卷）时不设 RUSTFS_DATA_DIR，改把"取对象"交给 mc mirror：见第 2 步备注。
# 使用本地文件系统存储（拓扑 C）时设置 MEDIA_DIR=<本地卷挂载点>，跳过 S3/RustFS 备份。
#   compose 用的是 named volume，挂载点现查、不要抄：
#     MEDIA_DIR="$(docker volume inspect pushbox_server_media -f '{{.Mountpoint}}')"
#   自己换成宿主绑定挂载（./data/media）时，MEDIA_DIR 就直接指那个目录。

set -euo pipefail

# 先查依赖，别等到管道中间才发现缺件：mysqldump 不在时 `mysqldump | gzip | age` 会留下一本
# 解得开但内容为空的档案，而 cron 只看退出码——下次真要恢复时才知道这两年没有备份。
# 这三样在最小化的服务器上都不一定装（本机 WSL 实测就是 mysqldump 与 age 都没有）。
for dep in mysqldump gzip age; do
  command -v "$dep" >/dev/null 2>&1 \
    || { echo "❌ 缺少依赖：$dep（备份机需要 mysqldump / gzip / age；改用 gpg 见文末说明）" >&2; exit 1; }
done

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
dest="${BACKUP_DIR:-"$(cd "$(dirname "$0")" && pwd)/out/$stamp"}"
recipient="${AGE_RECIPIENT:?需要 AGE_RECIPIENT（age 公钥 / ssh 公钥）}"
mkdir -p "$dest"

db_host="${MYSQL_HOST:-127.0.0.1}"
db_port="${MYSQL_PORT:-3306}"

# 1) 数据库逻辑备份
#    --single-transaction 取一致性快照不锁 InnoDB；--quick 逐行流式、不把大表灌进内存；
#    --set-gtid-purged=OFF 让 dump 能在没配 GTID 的目标实例上直接恢复。
#    --no-tablespaces：备份账号只该有 pushbox 库的权限，而 mysqldump 默认还要去读 INFORMATION_SCHEMA
#    的表空间（需要 PROCESS）。不加这一条，MySQL 8.4 上每次备份都会往 stderr 印
#    "Access denied; you need (at least one of) the PROCESS privilege(s)"——退出码仍是 0、档案仍是完整的
#    （2026-10-20 实跑量过：13 张表、恢复后行数一致），但 cron 的邮件里满屏像备份失败，
#    而"每次都有错但每次都没事"正是让人开始忽略备份告警的方式。
MYSQL_PWD="${PB_DB_PASSWORD:?需要 PB_DB_PASSWORD}" mysqldump \
  --single-transaction --quick --set-gtid-purged=OFF --no-tablespaces --routines --triggers \
  -h "$db_host" -P "$db_port" -u pushbox pushbox \
  | gzip | age -r "$recipient" -o "$dest/pushbox-$stamp.sql.gz.age"

# 2) 媒体文件备份
#    优先级：本地文件系统存储 > RustFS 卷 > S3 mc mirror

if [ -n "${MEDIA_DIR:-}" ] && [ -d "$MEDIA_DIR" ]; then
  # 拓扑 C：本地文件系统存储
  parent="$(dirname "${MEDIA_DIR%/}")"
  name="$(basename "${MEDIA_DIR%/}")"
  tar -C "$parent" -cf - "$name" | age -r "$recipient" -o "$dest/media-$stamp.tar.age"
  echo "✅ 本地媒体目录已备份: $MEDIA_DIR"
elif [ -n "${RUSTFS_DATA_DIR:-}" ] && [ -d "$RUSTFS_DATA_DIR" ]; then
  # 拓扑 B：本机 RustFS 卷
  parent="$(dirname "${RUSTFS_DATA_DIR%/}")"
  name="$(basename "${RUSTFS_DATA_DIR%/}")"
  tar -C "$parent" -cf - "$name" | age -r "$recipient" -o "$dest/rustfs-$stamp.tar.age"
  echo "✅ RustFS 卷已备份: $RUSTFS_DATA_DIR"
else
  # 拓扑 A：远端 RustFS/S3，用 mc mirror 拉一份再加密
  if command -v mc >/dev/null 2>&1; then
    mc alias set pbk "${PB_S3_ENDPOINT:?}" "${PB_S3_ACCESS_KEY:?}" "${PB_S3_SECRET_KEY:?}"
    tmp="$dest/rustfs-$stamp"
    mc mirror --overwrite "pbk/${PB_S3_BUCKET:-pushbox}" "$tmp"
    tar -C "$dest" -cf - "$(basename "$tmp")" | age -r "$recipient" -o "$dest/rustfs-$stamp.tar.age"
    rm -rf "$tmp"
    echo "✅ S3/RustFS 已通过 mc mirror 备份"
  else
    echo "⚠️  跳过对象备份：既没给 MEDIA_DIR/RUSTFS_DATA_DIR，也没装 mc。" >&2
    echo "   如果使用本地存储，请设置 MEDIA_DIR=\"\$(docker volume inspect pushbox_server_media -f '{{.Mountpoint}}')\""
  fi
fi

echo "备份完成 -> $dest"
ls -lh "$dest"

# ---- 恢复 ----
#   数据库：age -d -i ~/.age/id.key "$dump" | gunzip | mysql -h.. -u pushbox -p pushbox
#   RustFS：age -d -i ~/.age/id.key "$tarball" | tar -C ./data -xf -   # RustFS 停止时解压，再起容器
#   本地存储：age -d -i ~/.age/id.key "$tarball" | tar -C ./data -xf -
# 用 gpg 替代 age 的话，把管道里的 `age -r "$recipient"` 换成 `gpg --encrypt --recipient "$GPG_RECIPIENT"`，
# 恢复端对应 `gpg --decrypt`。
