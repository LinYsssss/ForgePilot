#!/usr/bin/env bash
# eval-stack.sh — 评测隔离栈生命周期一键驱动(r7 design D4 执行形态;r8 各轮评测复用)。
#
# 子命令:
#   up            起栈(--build backend,依赖自动拉起)+ 等 actuator 健康
#   run [args]    跑分:SEED_ADMIN_* 从 deploy/.env 在子壳内映射为 EVAL_USERNAME/PASSWORD
#                 后调 run-baseline.sh(透传其余参数,如 --resume / --only <id>)
#   calls <dir>   导出 ai_call_log 实数(汇总+逐行 CSV)到 <dir> —— 必须在 down 前执行
#   down          down -v 即弃(独立卷随项目名一起清除,无残留)
#
# 凭据纪律:deploy/.env 只在 run 的子壳内 source,变量不打印、不进参数列表(ps 不可见)、
# 不落任何文件;本脚本自身不产生含凭据的输出。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE=(docker compose -p reposage-eval
  -f "$ROOT_DIR/deploy/docker-compose.yml"
  -f "$ROOT_DIR/evaluation/tools/eval-stack.override.yml")
BASE_URL="http://127.0.0.1:18080"
PG_CONTAINER="reposage-eval-postgres-1"

cmd="${1:-}"; shift || true
case "$cmd" in
  up)
    "${COMPOSE[@]}" up -d --build backend
    echo "等待 backend 健康($BASE_URL/actuator/health)..."
    for _ in $(seq 1 60); do
      if curl -fsS "$BASE_URL/actuator/health" 2>/dev/null | grep -q '"UP"'; then
        echo "backend UP"; exit 0
      fi
      sleep 5
    done
    echo "backend 300s 内未健康,查日志:${COMPOSE[*]} logs backend" >&2; exit 1
    ;;
  run)
    [ -f "$ROOT_DIR/deploy/.env" ] || { echo "缺少 deploy/.env(SEED_ADMIN_* 来源)" >&2; exit 1; }
    (
      set -a; . "$ROOT_DIR/deploy/.env"; set +a
      : "${SEED_ADMIN_USERNAME:?deploy/.env 缺 SEED_ADMIN_USERNAME}"
      : "${SEED_ADMIN_PASSWORD:?deploy/.env 缺 SEED_ADMIN_PASSWORD}"
      EVAL_BASE_URL="$BASE_URL" \
      EVAL_USERNAME="$SEED_ADMIN_USERNAME" \
      EVAL_PASSWORD="$SEED_ADMIN_PASSWORD" \
        exec bash "$ROOT_DIR/evaluation/tools/run-baseline.sh" "$@"
    )
    ;;
  calls)
    out="${1:?用法: eval-stack.sh calls <输出目录>}"; mkdir -p "$out"
    docker exec "$PG_CONTAINER" psql -U code_review -d code_review -c \
      "\copy (select request_type, status, count(*) as calls, sum(prompt_tokens) as prompt_tokens, sum(completion_tokens) as completion_tokens, sum(total_tokens) as total_tokens, round(avg(latency_ms)) as avg_latency_ms from ai_call_log group by 1,2 order by 1,2) to stdout with csv header" \
      > "$out/ai-call-log-summary.csv"
    docker exec "$PG_CONTAINER" psql -U code_review -d code_review -c \
      "\copy (select id, project_id, task_id, request_type, provider, model, prompt_tokens, completion_tokens, total_tokens, latency_ms, status, created_at from ai_call_log order by id) to stdout with csv header" \
      > "$out/ai-call-log-rows.csv"
    echo "已导出:$out/ai-call-log-{summary,rows}.csv"
    ;;
  down)
    "${COMPOSE[@]}" down -v
    ;;
  *)
    sed -n '2,13p' "$0"; exit 2
    ;;
esac
