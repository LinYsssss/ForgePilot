#!/usr/bin/env bash
set -euo pipefail

# RepoSage 数据备份:PostgreSQL 逻辑备份 + 保留策略。
#
# 数据事实上只有一份需要备份:业务数据全部在 postgres(pgvector)里;
# RabbitMQ 队列是瞬态的,sandbox 的 work/cache 卷可重建,镜像可重构。
# 注意:数据库里的 SCM 凭据/webhook 密钥是用 deploy/.env 的 TOKEN_ENCRYPT_KEY
# 加密的 —— 只有 dump 没有 .env 恢复不出可用系统。.env 含明文密钥,
# 必须走单独的安全渠道保管,绝不放进本备份目录或任何仓库。
#
# 用法:
#   deploy/backup.sh [备份目录]     # 缺省 deploy/backups/
#   BACKUP_KEEP=30 deploy/backup.sh # 保留最近 30 份(缺省 14)
# 恢复步骤见 docs/12_服务器部署与演示手册.md 「备份与恢复」。

cd "$(dirname "$0")"

BACKUP_DIR="${1:-./backups}"
KEEP="${BACKUP_KEEP:-14}"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$BACKUP_DIR/code_review-$STAMP.dump"

mkdir -p "$BACKUP_DIR"

# pg_dump 用容器里自带的客户端,与服务端版本天然一致;
# 自定义格式(-Fc)压缩存储,且 pg_restore 可选择性恢复单表。
docker compose exec -T postgres pg_dump -U code_review -d code_review --format=custom > "$OUT"

# 完整性初检:自定义格式以 PGDMP 魔数开头,空文件/错误输出立刻暴露。
if ! head -c 5 "$OUT" | grep -q "PGDMP"; then
    echo "FAIL: $OUT 不是有效的 pg_dump 自定义格式输出" >&2
    rm -f "$OUT"
    exit 1
fi

# 保留最近 KEEP 份,更早的滚动删除。
ls -1t "$BACKUP_DIR"/code_review-*.dump 2>/dev/null | tail -n +"$((KEEP + 1))" | xargs -r rm -f --

echo "OK: $OUT ($(du -h "$OUT" | cut -f1)),当前保留 $(ls -1 "$BACKUP_DIR"/code_review-*.dump | wc -l) 份"
