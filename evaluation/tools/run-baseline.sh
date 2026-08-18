#!/usr/bin/env bash
# run-baseline.sh — 基线跑分驱动器:对隔离栈 API 逐用例执行
#   CSRF 引导 → 登录 → 创建项目 → 绑定 LOCAL 仓库 → (有 knowledge/)上传知识文档并等 INDEXED
#   → 建审查任务(commitId=headSha, baseCommitId=baseSha) → 轮询任务终态
#   → 取报告+issues → 存原始 API 响应到任务目录。
#
# 真实 API 路径(以 backend @RequestMapping 为准,已核对):
#   GET  /api/auth/csrf                                        CSRF Cookie 引导(XSRF-TOKEN / X-XSRF-TOKEN)
#   POST /api/auth/login                                       {username,password}
#   POST /api/projects                                         {name,...} → data.projectId
#   POST /api/projects/{pid}/repository                        {repoUrl,provider,defaultBranch}
#   POST /api/projects/{pid}/knowledge/documents?docType=...   multipart part 名 "file" → data.documentId
#   GET  /api/projects/{pid}/knowledge/documents?size=100      → data.items[].status (INDEXED/FAILED)
#   POST /api/projects/{pid}/reviews/tasks                     {commitId,baseCommitId,flags} → data.taskId
#   GET  /api/projects/{pid}/reviews/tasks/{taskId}            → data.status(终态 SUCCESS/DEAD/CANCELED)
#   GET  /api/projects/{pid}/reviews/reports?size=100          → data.items[] (按 taskId 找 reportId)
#   GET  /api/projects/{pid}/reviews/reports/{reportId}        → data.issues[](judge 的对照物)
#
# 环境变量(凭据只经环境注入,本脚本不打印、不落壳历史):
#   EVAL_BASE_URL       必填,隔离栈地址,如 http://127.0.0.1:18080 —— 故意无默认值,防误打演示栈
#   EVAL_USERNAME       必填,隔离栈账号(种子管理员即可)
#   EVAL_PASSWORD       必填
#   EVAL_REPOS_MOUNT    用例仓库在 backend 容器内的挂载前缀,默认 /eval-repos
#   EVAL_WORK_DIR       宿主机工作目录(读 manifest-shas.txt),默认 /tmp/reposage-eval-repos
#   EVAL_RUN_DATE       兼容旧日期字段,默认今天(YYYY-MM-DD)
#   EVAL_RUN_ID         输出 run id;默认 Baseline=日期,其它 arm=日期-arm 小写
#   EVAL_ARM            默认 Baseline;可由 --arm 覆盖
#   EVAL_OUT_ROOT       输出根,默认 <repo>/.trellis/tasks/08-03-r7-eval-corpus/baseline-runs
#   EVAL_TASK_TIMEOUT   单任务轮询上限秒,默认 900(对齐 manifest fixedRun.timeoutSeconds)
#   EVAL_INDEX_TIMEOUT  知识文档索引等待上限秒,默认 120
#   EVAL_POLL_INTERVAL  轮询间隔秒,默认 5
#
# 用法:
#   bash evaluation/tools/run-baseline.sh [--arm <Baseline|A|B|C|D>] [--run-id <id>] [--resume] [--only <id>]...
#   --arm     选择五臂: Baseline(diff), A(+knowledge), B(+requirement/AC), C(A+B), D(C+evidence verification)
#   --run-id  输出目录标识;缺省时 Baseline 保持旧的日期目录兼容
# 产物:
#   $EVAL_OUT_ROOT/$EVAL_RUN_ID/<id>.json        成功例:原始任务+报告响应包+arm flags
#   $EVAL_OUT_ROOT/$EVAL_RUN_ID/<id>.error.json  失败例:阶段+错误信息(继续跑下一例)
# 退出码:全部成功 0,任一失败 1。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EVAL_DIR="$ROOT_DIR/evaluation"
MANIFEST="$EVAL_DIR/manifest.json"

BASE_URL="${EVAL_BASE_URL:-}"
REPOS_MOUNT="${EVAL_REPOS_MOUNT:-/eval-repos}"
WORK_DIR="${EVAL_WORK_DIR:-/tmp/reposage-eval-repos}"
RUN_DATE="${EVAL_RUN_DATE:-$(date +%F)}"
ARM="${EVAL_ARM:-Baseline}"
RUN_ID="${EVAL_RUN_ID:-$RUN_DATE}"
if [ "$ARM" != "Baseline" ] && [ -z "${EVAL_RUN_ID:-}" ]; then
  RUN_ID="${RUN_DATE}-${ARM,,}"
fi
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
GIT_COMMIT_SHA="${GIT_COMMIT_SHA:-$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || echo unknown)}"
OUT_ROOT="${EVAL_OUT_ROOT:-$ROOT_DIR/.trellis/tasks/08-03-r7-eval-corpus/baseline-runs}"
TASK_TIMEOUT="${EVAL_TASK_TIMEOUT:-900}"
INDEX_TIMEOUT="${EVAL_INDEX_TIMEOUT:-120}"
POLL_INTERVAL="${EVAL_POLL_INTERVAL:-5}"
SHA_FILE="$WORK_DIR/manifest-shas.txt"

RESUME=0
ONLY_IDS=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --resume) RESUME=1; shift ;;
    --arm)
      [ "$#" -ge 2 ] || { echo "--arm 需要 Baseline|A|B|C|D" >&2; exit 2; }
      ARM="$2"; shift 2 ;;
    --run-id)
      [ "$#" -ge 2 ] || { echo "--run-id 需要一个标识" >&2; exit 2; }
      RUN_ID="$2"; shift 2 ;;
    --only)
      [ "$#" -ge 2 ] || { echo "--only 需要一个用例 id" >&2; exit 2; }
      ONLY_IDS+=("$2"); shift 2 ;;
    -h|--help) sed -n '2,38p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

command -v curl >/dev/null || { echo "缺少 curl" >&2; exit 1; }
command -v python3 >/dev/null || { echo "缺少 python3" >&2; exit 1; }
case "$ARM" in
  Baseline|A|B|C|D) ;;
  *) echo "未知 arm: $ARM(允许 Baseline|A|B|C|D)" >&2; exit 2 ;;
esac
if [ -z "${EVAL_RUN_ID:-}" ] && [ "$RUN_ID" = "$RUN_DATE" ] && [ "$ARM" != "Baseline" ]; then
  RUN_ID="${RUN_DATE}-${ARM,,}"
fi
[ -n "$BASE_URL" ] || { echo "必须设置 EVAL_BASE_URL(隔离栈地址;故意无默认值,防止误打演示栈)" >&2; exit 1; }
[ -n "${EVAL_USERNAME:-}" ] || { echo "必须设置 EVAL_USERNAME" >&2; exit 1; }
[ -n "${EVAL_PASSWORD:-}" ] || { echo "必须设置 EVAL_PASSWORD" >&2; exit 1; }
[ -f "$MANIFEST" ] || { echo "manifest 不存在: $MANIFEST" >&2; exit 1; }
[ -f "$SHA_FILE" ] || { echo "缺少 $SHA_FILE —— 先跑 build-case-repos.sh" >&2; exit 1; }
BASE_URL="${BASE_URL%/}"

OUT_DIR="$OUT_ROOT/$RUN_ID"
AI_LOG_FILE="$OUT_DIR/ai-call-log.json"
METADATA_FILE="$OUT_DIR/run-metadata.json"
mkdir -p "$OUT_DIR"
[ -f "$AI_LOG_FILE" ] || printf "{\"rows\":[]}\n" > "$AI_LOG_FILE"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
JAR="$TMP_DIR/cookies.txt"
BODY="$TMP_DIR/body.json"

# ---------- JSON 小工具(python3 标准库,替代 jq) ----------

# json_get <file> <点路径>  取字段(缺失/空 → 空串);数组下标写数字段,如 data.items.0.status
json_get() {
  python3 - "$1" "$2" <<'PY'
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as fh:
        node = json.load(fh)
except Exception:
    sys.exit(0)
for key in sys.argv[2].split("."):
    try:
        node = node[int(key)] if isinstance(node, list) else node[key]
    except Exception:
        sys.exit(0)
if node is None:
    sys.exit(0)
print(node if not isinstance(node, (dict, list)) else json.dumps(node, ensure_ascii=False))
PY
}

# arm_flags_json → 生产 ReviewFeatureFlags 的唯一五臂映射
arm_flags_json() {
  case "$ARM" in
    Baseline) printf '{"knowledge":false,"requirementContext":false,"evidenceVerification":false}' ;;
    A)        printf '{"knowledge":true,"requirementContext":false,"evidenceVerification":false}' ;;
    B)        printf '{"knowledge":false,"requirementContext":true,"evidenceVerification":false}' ;;
    C)        printf '{"knowledge":true,"requirementContext":true,"evidenceVerification":false}' ;;
    D)        printf '{"knowledge":true,"requirementContext":true,"evidenceVerification":true}' ;;
  esac
}

# api_ok <file>  响应信封 code==0 才算成功
api_ok() {
  [ "$(json_get "$1" code)" = "0" ]
}

csrf_token() {
  # Netscape cookie jar:第 6 列 name,第 7 列 value;取最后一次写入的 XSRF-TOKEN
  awk '$6 == "XSRF-TOKEN" { v = $7 } END { if (v != "") print v }' "$JAR"
}

# ---------- HTTP 封装(全部带 cookie jar;写请求带 X-XSRF-TOKEN) ----------

api_get() { # <path> → 响应体写入 $BODY
  curl -sS -o "$BODY" -b "$JAR" -c "$JAR" "$BASE_URL$1"
}

api_post_json() { # <path> <json-body-file> → $BODY
  curl -sS -o "$BODY" -b "$JAR" -c "$JAR" \
    -H "Content-Type: application/json" \
    -H "X-XSRF-TOKEN: $(csrf_token)" \
    --data-binary @"$2" "$BASE_URL$1"
}

api_post_multipart() { # <path> <file> → $BODY
  curl -sS -o "$BODY" -b "$JAR" -c "$JAR" \
    -H "X-XSRF-TOKEN: $(csrf_token)" \
    -F "file=@$2" "$BASE_URL$1"
}

login() {
  api_get "/api/auth/csrf"
  api_ok "$BODY" || { echo "CSRF 引导失败: $BASE_URL/api/auth/csrf" >&2; return 1; }
  # 凭据经 python3 组 JSON(转义安全),只落在 mktemp 私有目录,不经命令行参数
  python3 -c 'import json, os, sys; sys.stdout.write(json.dumps({"username": os.environ["EVAL_USERNAME"], "password": os.environ["EVAL_PASSWORD"]}))' \
    > "$TMP_DIR/login.json"
  api_post_json "/api/auth/login" "$TMP_DIR/login.json"
  rm -f "$TMP_DIR/login.json"
  api_ok "$BODY" || { echo "登录失败(账号/密码/栈地址?)" >&2; return 1; }
  echo "登录成功: $(json_get "$BODY" data.username) @ $BASE_URL"
}

# ---------- 单用例流程 ----------

FAIL_STAGE=""
FAIL_MSG=""

fail() { # <stage> <msg>
  FAIL_STAGE="$1"
  FAIL_MSG="$2"
  return 1
}

write_error() { # <id> <out-file>
  local id="$1" out="$2"
  STAGE="$FAIL_STAGE" MSG="$FAIL_MSG" CASE_ID="$id" RUN_DATE="$RUN_DATE" RUN_ID="$RUN_ID" ARM="$ARM" python3 - "$BODY" > "$out" <<'PY'
import json, os, sys, datetime
raw = ""
try:
    with open(sys.argv[1], encoding="utf-8") as fh:
        raw = fh.read()[:4000]
except Exception:
    pass
print(json.dumps({
    "caseId": os.environ["CASE_ID"],
    "runDate": os.environ["RUN_DATE"],
    "runId": os.environ["RUN_ID"],
    "arm": os.environ["ARM"],
    "capturedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "error": {"stage": os.environ["STAGE"], "message": os.environ["MSG"], "lastResponse": raw},
}, ensure_ascii=False, indent=2))
PY
}

run_case() { # <id> <split> <fixture>
  local id="$1" split="$2" fixture="$3"
  local base_sha head_sha
  base_sha="$(awk -v id="$id" '$1 == id { print $2 }' "$SHA_FILE")"
  head_sha="$(awk -v id="$id" '$1 == id { print $3 }' "$SHA_FILE")"
  [ -n "$base_sha" ] && [ -n "$head_sha" ] \
    || fail shas "manifest-shas.txt 里没有 $id(先跑 build-case-repos.sh)" || return 1

  # 1) 项目(名字含日期+用例 id,避免撞审查任务幂等键)
  printf '{"name":"eval-%s-%s","description":"r7 baseline run"}' "$RUN_DATE" "$id" > "$TMP_DIR/req.json"
  api_post_json "/api/projects" "$TMP_DIR/req.json"
  api_ok "$BODY" || fail project "创建项目失败: $(json_get "$BODY" message)" || return 1
  local pid
  pid="$(json_get "$BODY" data.projectId)"

  # 2) 绑定 LOCAL 仓库(容器内路径;需要栈内 GIT_ALLOW_LOCAL_PATH=true)
  printf '{"repoUrl":"%s/%s","provider":"LOCAL","defaultBranch":"main"}' "$REPOS_MOUNT" "$id" > "$TMP_DIR/req.json"
  api_post_json "/api/projects/$pid/repository" "$TMP_DIR/req.json"
  api_ok "$BODY" || fail repository "绑定仓库失败: $(json_get "$BODY" message)" || return 1

  # 3) Requirement + AC:从唯一 manifest 事实源创建,并用 main 分支关联供 B/C/D coverage judge 解析
  python3 - "$MANIFEST" "$id" > "$TMP_DIR/requirement.json" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as fh:
    manifest = json.load(fh)
case_id = sys.argv[2]
case = next(item for item in manifest.get("cases", []) if item.get("id") == case_id)
requirement = case["requirement"]
print(json.dumps({
    "title": requirement["title"],
    "background": requirement.get("background", ""),
    "description": requirement.get("description", ""),
    "priority": "P1",
    "acceptanceCriteria": [{"text": item["text"]} for item in case.get("acceptanceCriteria", [])],
}, ensure_ascii=False))
PY
  api_post_json "/api/projects/$pid/requirements" "$TMP_DIR/requirement.json"
  api_ok "$BODY" || fail requirement "创建 Requirement 失败: $(json_get "$BODY" message)" || return 1
  local requirement_id
  requirement_id="$(json_get "$BODY" data.requirementId)"
  printf '{"type":"BRANCH","ref":"main"}' > "$TMP_DIR/link.json"
  api_post_json "/api/projects/$pid/requirements/$requirement_id/links" "$TMP_DIR/link.json"
  api_ok "$BODY" || fail requirement "关联 Requirement 到 main 分支失败: $(json_get "$BODY" message)" || return 1

  # 4) 知识文档(仅当用例带 knowledge/;不传 documentIds ⇒ 审查用全项目文档)
  local kdir="$EVAL_DIR/$fixture/knowledge"
  if [ -d "$kdir" ]; then
    local kfile doc_id
    for kfile in "$kdir"/*.md; do
      [ -e "$kfile" ] || continue
      api_post_multipart "/api/projects/$pid/knowledge/documents?docType=README" "$kfile"
      api_ok "$BODY" || fail knowledge "上传失败 $(basename "$kfile"): $(json_get "$BODY" message)" || return 1
      doc_id="$(json_get "$BODY" data.documentId)"
      local waited=0 status=""
      while :; do
        api_get "/api/projects/$pid/knowledge/documents?size=100"
        status="$(DOC_ID="$doc_id" python3 - "$BODY" <<'PY'
import json, os, sys
with open(sys.argv[1], encoding="utf-8") as fh:
    data = json.load(fh)
target = int(os.environ["DOC_ID"])
for item in (data.get("data") or {}).get("items", []):
    if item.get("documentId") == target:
        print(item.get("status") or "")
PY
)"
        if [ "$status" = "INDEXED" ]; then
          break
        fi
        if [ "$status" = "FAILED" ]; then
          fail knowledge "文档索引 FAILED: $(basename "$kfile")" || return 1
        fi
        if [ "$waited" -ge "$INDEX_TIMEOUT" ]; then
          fail knowledge "等 INDEXED 超时(${INDEX_TIMEOUT}s): $(basename "$kfile")" || return 1
        fi
        sleep "$POLL_INTERVAL"; waited=$((waited + POLL_INTERVAL))
      done
    done
  fi

  # 5) 审查任务:温度走服务端配置,五臂只通过生产 flags 下发
  flags_json="$(arm_flags_json)"
  python3 - "$head_sha" "$base_sha" "$flags_json" > "$TMP_DIR/req.json" <<'PY'
import json, sys
print(json.dumps({
    "commitId": sys.argv[1],
    "baseCommitId": sys.argv[2],
    "branch": "main",
    "flags": json.loads(sys.argv[3]),
}, ensure_ascii=False))
PY
  api_post_json "/api/projects/$pid/reviews/tasks" "$TMP_DIR/req.json"
  api_ok "$BODY" || fail task "建任务失败: $(json_get "$BODY" message)" || return 1
  local task_id
  task_id="$(json_get "$BODY" data.taskId)"

  # 6) 轮询终态(SUCCESS/DEAD/CANCELED;FAILED 会被 MQ 重试,不算终态)
  local waited=0 status=""
  while :; do
    api_get "/api/projects/$pid/reviews/tasks/$task_id"
    status="$(json_get "$BODY" data.status)"
    case "$status" in
      SUCCESS) break ;;
      DEAD|CANCELED) fail review "任务终态 $status: $(json_get "$BODY" data.errorMessage)" || return 1 ;;
    esac
    if [ "$waited" -ge "$TASK_TIMEOUT" ]; then
      fail review "任务超时(${TASK_TIMEOUT}s),末态 $status" || return 1
    fi
    sleep "$POLL_INTERVAL"; waited=$((waited + POLL_INTERVAL))
  done
  cp "$BODY" "$TMP_DIR/task.json"

  # 7) 找报告(按 taskId 匹配)→ 取明细
  api_get "/api/projects/$pid/reviews/reports?size=100"
  local report_id
  report_id="$(TASK_ID="$task_id" python3 - "$BODY" <<'PY'
import json, os, sys
with open(sys.argv[1], encoding="utf-8") as fh:
    data = json.load(fh)
target = int(os.environ["TASK_ID"])
for item in (data.get("data") or {}).get("items", []):
    if item.get("taskId") == target:
        print(item.get("reportId"))
        break
PY
)"
  [ -n "$report_id" ] || fail report "SUCCESS 任务找不到报告(taskId=$task_id)" || return 1
  api_get "/api/projects/$pid/reviews/reports/$report_id"
  api_ok "$BODY" || fail report "取报告失败: $(json_get "$BODY" message)" || return 1

  # 8) 组装原始响应包(score.py 的输入)
  CASE_ID="$id" SPLIT="$split" RUN_DATE="$RUN_DATE" RUN_ID="$RUN_ID" ARM="$ARM" \
    REQUIREMENT_ID="$requirement_id" BASE_SHA="$base_sha" HEAD_SHA="$head_sha" FLAGS_JSON="$flags_json" \
    PID="$pid" TASK_ID="$task_id" REPORT_ID="$report_id" \
    python3 - "$TMP_DIR/task.json" "$BODY" > "$OUT_DIR/$id.json" <<'PY'
import json, os, sys, datetime
with open(sys.argv[1], encoding="utf-8") as fh:
    task = json.load(fh).get("data")
with open(sys.argv[2], encoding="utf-8") as fh:
    report = json.load(fh).get("data")
print(json.dumps({
    "caseId": os.environ["CASE_ID"],
    "split": os.environ["SPLIT"],
    "runDate": os.environ["RUN_DATE"],
    "runId": os.environ["RUN_ID"],
    "arm": os.environ["ARM"],
    "requirementId": int(os.environ["REQUIREMENT_ID"]),
    "flags": json.loads(os.environ["FLAGS_JSON"]),
    "capturedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "baseSha": os.environ["BASE_SHA"],
    "headSha": os.environ["HEAD_SHA"],
    "projectId": int(os.environ["PID"]),
    "taskId": int(os.environ["TASK_ID"]),
    "reportId": int(os.environ["REPORT_ID"]),
    "task": task,
    "report": report,
}, ensure_ascii=False, indent=2))
PY
  # 9) 导出本项目 AI 日志并附 caseId,缺日志由 scorer 如实标记。
  api_get "/api/ai/logs?projectId=$pid&limit=1000"
  if api_ok "$BODY"; then
    CASE_ID="$id" TASK_ID="$task_id" python3 - "$BODY" "$AI_LOG_FILE" <<'PY'
import json, os, sys
from pathlib import Path
body_path, out_path = sys.argv[1], sys.argv[2]
try:
    payload = json.loads(Path(body_path).read_text(encoding="utf-8"))
except Exception:
    payload = {}
rows = payload.get("data", []) if isinstance(payload, dict) else []
if isinstance(rows, dict):
    rows = rows.get("items", rows.get("rows", []))
if not isinstance(rows, list):
    rows = []
try:
    existing = json.loads(Path(out_path).read_text(encoding="utf-8"))
except Exception:
    existing = {"rows": []}
all_rows = existing.get("rows", []) if isinstance(existing, dict) else []
for row in rows:
    if isinstance(row, dict):
        row = dict(row)
        row["caseId"] = os.environ["CASE_ID"]
        row.setdefault("taskId", int(os.environ["TASK_ID"]))
        all_rows.append(row)
Path(out_path).write_text(json.dumps({"rows": all_rows}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY
  fi
  rm -f "$OUT_DIR/$id.error.json"
  return 0
}

write_metadata() {
  OK_COUNT="$(echo "$OK_LIST" | wc -w | tr -d ' ')"
  FAIL_COUNT="$(echo "$FAIL_LIST" | wc -w | tr -d ' ')"
  SKIP_COUNT="$(echo "$SKIP_LIST" | wc -w | tr -d ' ')"
  FINISHED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  RUN_ID="$RUN_ID" ARM="$ARM" RUN_DATE="$RUN_DATE" GIT_COMMIT_SHA="$GIT_COMMIT_SHA" STARTED_AT="$STARTED_AT" FINISHED_AT="$FINISHED_AT" \
    OK_COUNT="$OK_COUNT" FAIL_COUNT="$FAIL_COUNT" SKIP_COUNT="$SKIP_COUNT" python3 - "$MANIFEST" "$METADATA_FILE" <<'PY'
import json, os, sys
from pathlib import Path
manifest = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
fixed = manifest.get("fixedRun", {})
metadata = {
    "runId": os.environ["RUN_ID"],
    "arm": os.environ["ARM"],
    "startedAt": os.environ["STARTED_AT"],
    "finishedAt": os.environ["FINISHED_AT"],
    "commitSha": os.environ.get("GIT_COMMIT_SHA", ""),
    "corpusVersion": manifest.get("corpusVersion"),
    "schemaVersion": manifest.get("schemaVersion"),
    "model": fixed.get("model"),
    "temperature": fixed.get("temperature"),
    "toolImage": fixed.get("toolImage"),
    "promptVersion": fixed.get("promptVersion"),
    "findingSchemaVersion": fixed.get("findingSchemaVersion"),
    "scoredCases": int(os.environ["OK_COUNT"]) + int(os.environ["SKIP_COUNT"]),
    "notRunCases": int(os.environ["FAIL_COUNT"]),
    "fixedRun": fixed,
}
Path(sys.argv[2]).write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY
}

# ---------- 主流程 ----------

login || exit 1

CASE_LIST="$(python3 - "$MANIFEST" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as fh:
    manifest = json.load(fh)
for case in manifest.get("cases", []):
    print("%s\t%s\t%s" % (case["id"], case.get("split", ""), case["fixture"]))
PY
)"

wanted() {
  local id="$1"
  [ "${#ONLY_IDS[@]}" -eq 0 ] && return 0
  local w
  for w in "${ONLY_IDS[@]}"; do
    [ "$w" = "$id" ] && return 0
  done
  return 1
}

OK_LIST=""
FAIL_LIST=""
SKIP_LIST=""
while IFS="$(printf '\t')" read -r id split fixture; do
  # Windows Python writes CRLF to Git Bash stdout; strip the trailing CR so
  # the fixture path (last field) does not silently break knowledge/ lookup.
  fixture="${fixture%$'\r'}"
  [ -n "$id" ] || continue
  wanted "$id" || continue
  if [ "$RESUME" = "1" ] && [ -f "$OUT_DIR/$id.json" ]; then
    SKIP_LIST="$SKIP_LIST $id"
    echo "skip(resume): $id"
    continue
  fi
  echo "=== $id ==="
  FAIL_STAGE=""; FAIL_MSG=""
  if run_case "$id" "$split" "$fixture"; then
    OK_LIST="$OK_LIST $id"
    echo "ok  : $id"
  else
    write_error "$id" "$OUT_DIR/$id.error.json"
    FAIL_LIST="$FAIL_LIST $id"
    echo "FAIL: $id [$FAIL_STAGE] $FAIL_MSG" >&2
  fi
done <<EOF
$CASE_LIST
EOF

echo "---"
echo "输出目录: $OUT_DIR"
echo "成功:$(echo "$OK_LIST" | wc -w)${OK_LIST:+ →$OK_LIST}"
echo "跳过:$(echo "$SKIP_LIST" | wc -w)${SKIP_LIST:+ →$SKIP_LIST}"
echo "失败:$(echo "$FAIL_LIST" | wc -w)${FAIL_LIST:+ →$FAIL_LIST}"
write_metadata
[ -z "$FAIL_LIST" ] || exit 1
