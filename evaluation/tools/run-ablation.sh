#!/usr/bin/env bash
# run-ablation.sh — P8 五臂编排器
# 在同一 manifest、同一服务配置和同一隔离栈上依次运行 Baseline/A/B/C/D。
# 运行器本身负责真实 API、flags、原始响应、ai_call_log 和 metadata；本包装器只编排。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUNNER="$ROOT_DIR/evaluation/tools/run-baseline.sh"
OUT_ROOT="${EVAL_OUT_ROOT:-$ROOT_DIR/.trellis/tasks/08-17-p8-experiment-defense/eval-runs}"
RUN_PREFIX="${EVAL_RUN_PREFIX:-$(date +%F)}"

if [ "$#" -gt 0 ] && { [ "$1" = "-h" ] || [ "$1" = "--help" ]; }; then
  cat <<'HELP'
用法:
  bash evaluation/tools/run-ablation.sh [--resume] [--only <case-id>]...

环境:
  EVAL_BASE_URL / EVAL_USERNAME / EVAL_PASSWORD 真实隔离栈凭据
  EVAL_OUT_ROOT   输出根,默认 P8 task/eval-runs
  EVAL_RUN_PREFIX 输出前缀,默认 YYYY-MM-DD

每臂目录:
  <EVAL_OUT_ROOT>/<prefix>-baseline/
  <EVAL_OUT_ROOT>/<prefix>-a/
  <EVAL_OUT_ROOT>/<prefix>-b/
  <EVAL_OUT_ROOT>/<prefix>-c/
  <EVAL_OUT_ROOT>/<prefix>-d/
HELP
  exit 0
fi

for arm in Baseline A B C D; do
  lower="$(printf '%s' "$arm" | tr '[:upper:]' '[:lower:]')"
  run_id="${RUN_PREFIX}-${lower}"
  echo "=== P8 arm $arm ($run_id) ==="
  EVAL_OUT_ROOT="$OUT_ROOT" EVAL_RUN_ID="$run_id" EVAL_ARM="$arm" \
    bash "$RUNNER" --arm "$arm" --run-id "$run_id" "$@"
  python3 "$ROOT_DIR/evaluation/tools/score.py" \
    --runs "$OUT_ROOT/$run_id" \
    --out-dir "$OUT_ROOT/$run_id" \
    --label "$arm" || {
      echo "判分失败: $arm ($run_id)" >&2
      exit 1
    }
done

python3 - "$OUT_ROOT" "$RUN_PREFIX" <<'PY'
import json, sys
from pathlib import Path
root = Path(sys.argv[1])
prefix = sys.argv[2]
arms = {}
for arm in ("Baseline", "A", "B", "C", "D"):
    lower = arm.lower()
    run_id = f"{prefix}-{lower}"
    run_dir = root / run_id
    score = run_dir / f"scores-{arm}.json"
    metadata = run_dir / "run-metadata.json"
    arms[arm] = {
        "runId": run_id,
        "scores": str(score),
        "metadata": str(metadata),
        "scorePresent": score.is_file(),
        "metadataPresent": metadata.is_file(),
    }
(root / f"{prefix}-matrix.json").write_text(
    json.dumps({"runPrefix": prefix, "arms": arms}, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
PY
