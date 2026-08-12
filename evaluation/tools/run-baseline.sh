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
#   POST /api/projects/{pid}/reviews/tasks                     {commitId,baseCommitId} → data.taskId
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
#   EVAL_RUN_DATE       输出目录日期,默认今天(YYYY-MM-DD)
#   EVAL_OUT_ROOT       输出根,默认 <repo>/.trellis/tasks/08-03-r7-eval-corpus/baseline-runs
#   EVAL_TASK_TIMEOUT   单任务轮询上限秒,默认 900(对齐 manifest fixedRun.timeoutSeconds)
#   EVAL_INDEX_TIMEOUT  知识文档索引等待上限秒,默认 120
#   EVAL_POLL_INTERVAL  轮询间隔秒,默认 5
#
# 用法:
#   bash evaluation/tools/run-baseline.sh [--resume] [--only <id>]...
#   --resume  跳过已存在 <id>.json 的用例(失败例写 <id>.error.json,重跑会覆盖重试)
# 产物:
#   $EVAL_OUT_ROOT/$EVAL_RUN_DATE/<id>.json        成功例:原始任务+报告响应包
#   $EVAL_OUT_ROOT/$EVAL_RUN_DATE/<id>.error.json  失败例:阶段+错误信息(继续跑下一例)
# 退出码:全部成功 0,任一失败 1。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EVAL_DIR="$ROOT_DIR/evaluation"
MANIFEST="$EVAL_DIR/manifest.json"

BASE_URL="${EVAL_BASE_URL:-}"
REPOS_MOUNT="${EVAL_REPOS_MOUNT:-/eval-repos}"
WORK_DIR="${EVAL_WORK_DIR:-/tmp/reposage-eval-repos}"
RUN_DATE="${EVAL_RUN_DATE:-$(date +%F)}"
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
    --only)
      [ "$#" -ge 2 ] || { echo "--only 需要一个用例 id" >&2; exit 2; }
      ONLY_IDS+=("$2"); shift 2 ;;
    -h|--help) sed -n '2,38p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

command -v curl >/dev/null || { echo "缺少 curl" >&2; exit 1; }
command -v python3 >/dev/null || { echo "缺少 python3" >&2; exit 1; }
[ -n "$BASE_URL" ] || { echo "必须设置 EVAL_BASE_URL(隔离栈地址;故意无默认值,防止误打演示栈)" >&2; exit 1; }
[ -n "${EVAL_USERNAME:-}" ] || { echo "必须设置 EVAL_USERNAME" >&2; exit 1; }
[ -n "${EVAL_PASSWORD:-}" ] || { echo "必须设置 EVAL_PASSWORD" >&2; exit 1; }
[ -f "$MANIFEST" ] || { echo "manifest 不存在: $MANIFEST" >&2; exit 1; }
[ -f "$SHA_FILE" ] || { echo "缺少 $SHA_FILE —— 先跑 build-case-repos.sh" >&2; exit 1; }
BASE_URL="${BASE_URL%/}"

OUT_DIR="$OUT_ROOT/$RUN_DATE"
mkdir -p "$OUT_DIR"

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
  STAGE="$FAIL_STAGE" MSG="$FAIL_MSG" CASE_ID="$id" RUN_DATE="$RUN_DATE" python3 - "$BODY" > "$out" <<'PY'
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

  # 3) 知识文档(仅当用例带 knowledge/;不传 documentIds ⇒ 审查用全项目文档)
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

  # 4) 审查任务(温度走服务端配置,不在请求侧传)
  printf '{"commitId":"%s","baseCommitId":"%s"}' "$head_sha" "$base_sha" > "$TMP_DIR/req.json"
  api_post_json "/api/projects/$pid/reviews/tasks" "$TMP_DIR/req.json"
  api_ok "$BODY" || fail task "建任务失败: $(json_get "$BODY" message)" || return 1
  local task_id
  task_id="$(json_get "$BODY" data.taskId)"

  # 5) 轮询终态(SUCCESS/DEAD/CANCELED;FAILED 会被 MQ 重试,不算终态)
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

  # 6) 找报告(按 taskId 匹配)→ 取明细
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

  # 7) 组装原始响应包(score.py 的输入)
  CASE_ID="$id" SPLIT="$split" RUN_DATE="$RUN_DATE" BASE_SHA="$base_sha" HEAD_SHA="$head_sha" \
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
  rm -f "$OUT_DIR/$id.error.json"
  return 0
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
[ -z "$FAIL_LIST" ] || exit 1
