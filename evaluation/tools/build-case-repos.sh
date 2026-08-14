#!/usr/bin/env bash
# build-case-repos.sh — 把 evaluation/manifest.json 里的每个用例确定性构建成
# base→head 两提交的 git 仓库(输出到工作目录,不入库)。
#
# 布局语义(design.md D1/D2):
#   fixtureLayout=base-head : commit1 = <fixture>/base/ 树,commit2 = <fixture>/head/ 树
#   fixtureLayout=single(缺省): commit1 = README 占位,commit2 = 整个 fixture 树新增
#     (README 占位保留在 head 中,使 base→head diff 为纯新增,不出现 README 删除噪音)
#
# 不进仓库树的内容(两种布局同理):
#   - <fixture>/knowledge/    知识文档是检索夹具,由 run-baseline.sh 走 API 上传,不属于被审查代码
#   - <fixture>/expected.patch 判分工件(manifest expectedPatch.file 引用),入树会把答案泄给模型
#
# 确定性:固定作者/邮箱/提交时间戳 + 钉死 autocrlf/eol/filemode/excludesFile(手法同
# scripts/init-demo-repos.sh),同一份 fixture 在任何机器上产出相同 SHA。
#
# 用法:
#   bash evaluation/tools/build-case-repos.sh [--only <id>]... [--work-dir <dir>]
# 环境变量:
#   EVAL_WORK_DIR  输出根目录,默认 /tmp/reposage-eval-repos(--work-dir 优先)
# 产物:
#   $EVAL_WORK_DIR/<id>/            每例一个 git 仓库(main 分支两提交)
#   $EVAL_WORK_DIR/manifest-shas.txt  每行 "<id> <baseSha> <headSha>",按 id 排序;
#                                     --only 时只更新对应行,保留其余行
# 幂等:先删后建,可反复执行。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EVAL_DIR="$ROOT_DIR/evaluation"
MANIFEST="$EVAL_DIR/manifest.json"
WORK_DIR="${EVAL_WORK_DIR:-/tmp/reposage-eval-repos}"

ONLY_IDS=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --only)
      [ "$#" -ge 2 ] || { echo "--only 需要一个用例 id" >&2; exit 2; }
      ONLY_IDS+=("$2"); shift 2 ;;
    --work-dir)
      [ "$#" -ge 2 ] || { echo "--work-dir 需要一个目录" >&2; exit 2; }
      WORK_DIR="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,25p' "$0"; exit 0 ;;
    *)
      echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

command -v git >/dev/null || { echo "缺少 git" >&2; exit 1; }
command -v python3 >/dev/null || { echo "缺少 python3" >&2; exit 1; }
[ -f "$MANIFEST" ] || { echo "manifest 不存在: $MANIFEST" >&2; exit 1; }

# 确定性提交身份与时间戳(参与 SHA,不许改)
export GIT_AUTHOR_NAME="RepoSage Eval"
export GIT_AUTHOR_EMAIL="eval@reposage.local"
export GIT_COMMITTER_NAME="RepoSage Eval"
export GIT_COMMITTER_EMAIL="eval@reposage.local"
BASE_STAMP="2026-02-01T10:00:00+08:00"
HEAD_STAMP="2026-02-01T11:00:00+08:00"

commit_at() {
  local dir="$1" stamp="$2" message="$3"
  GIT_AUTHOR_DATE="$stamp" GIT_COMMITTER_DATE="$stamp" \
    git -C "$dir" commit -q --no-gpg-sign --allow-empty -m "$message"
}

init_repo() {
  local dir="$1"
  git -C "$dir" init -q -b main
  git -C "$dir" config core.autocrlf false
  git -C "$dir" config core.eol lf
  # filemode 参与 tree 哈希;excludesFile 指向必不存在的路径 = 屏蔽用户全局 ignore
  git -C "$dir" config core.filemode false
  git -C "$dir" config core.excludesFile "$dir/.git/no-global-excludes"
  git -C "$dir" config commit.gpgsign false
}

# 清空工作树(保留 .git)
clear_worktree() {
  local dir="$1"
  find "$dir" -mindepth 1 -maxdepth 1 ! -name '.git' -exec rm -rf {} +
}

copy_tree() {
  local src="$1" dest="$2"
  cp -R "$src/." "$dest/"
}

build_case() {
  local id="$1" fixture="$2" layout="$3"
  local src="$EVAL_DIR/$fixture"
  local dest="$WORK_DIR/$id"

  [ -d "$src" ] || { echo "FAIL $id: fixture 目录不存在 $src" >&2; return 1; }
  if [ "$layout" = "base-head" ]; then
    [ -d "$src/base" ] || { echo "FAIL $id: base-head 布局缺 base/ ($src)" >&2; return 1; }
    [ -d "$src/head" ] || { echo "FAIL $id: base-head 布局缺 head/ ($src)" >&2; return 1; }
  fi

  rm -rf "$dest"
  mkdir -p "$dest"
  init_repo "$dest"

  if [ "$layout" = "base-head" ]; then
    copy_tree "$src/base" "$dest"
    git -C "$dest" add -A
    commit_at "$dest" "$BASE_STAMP" "eval: base state"
    local base_sha
    base_sha="$(git -C "$dest" rev-parse HEAD)"

    clear_worktree "$dest"
    copy_tree "$src/head" "$dest"
    git -C "$dest" add -A
    commit_at "$dest" "$HEAD_STAMP" "eval: head state"
  else
    printf '%s\n' "# evaluation fixture placeholder" > "$dest/README.md"
    git -C "$dest" add -A
    commit_at "$dest" "$BASE_STAMP" "eval: base state"
    local base_sha
    base_sha="$(git -C "$dest" rev-parse HEAD)"

    copy_tree "$src" "$dest"
    rm -rf "$dest/knowledge" "$dest/expected.patch"
    git -C "$dest" add -A
    commit_at "$dest" "$HEAD_STAMP" "eval: head state"
  fi

  local head_sha
  head_sha="$(git -C "$dest" rev-parse HEAD)"
  printf '%s %s %s\n' "$id" "$base_sha" "$head_sha" >> "$BUILT_TMP"
  echo "built: $id  base=$base_sha  head=$head_sha"
}

mkdir -p "$WORK_DIR"
BUILT_TMP="$(mktemp)"
trap 'rm -f "$BUILT_TMP"' EXIT

# manifest → "id<TAB>fixture<TAB>layout" 清单(fixtureLayout 缺省 single,与 Java schema 一致)
CASE_LIST="$(python3 - "$MANIFEST" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as fh:
    manifest = json.load(fh)
for case in manifest.get("cases", []):
    layout = case.get("fixtureLayout") or "single"
    print("%s\t%s\t%s" % (case["id"], case["fixture"], layout))
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

BUILT=0
FAILED=0
SEEN_ONLY=0
while IFS="$(printf '\t')" read -r id fixture layout; do
  # Windows Python writes CRLF to Git Bash stdout; strip the trailing CR so
  # base-head fixtures are not silently treated as legacy single-layout cases.
  layout="${layout%$'\r'}"
  [ -n "$id" ] || continue
  wanted "$id" || continue
  SEEN_ONLY=$((SEEN_ONLY + 1))
  if build_case "$id" "$fixture" "$layout"; then
    BUILT=$((BUILT + 1))
  else
    FAILED=$((FAILED + 1))
  fi
done <<EOF
$CASE_LIST
EOF

if [ "${#ONLY_IDS[@]}" -gt 0 ] && [ "$SEEN_ONLY" -ne "${#ONLY_IDS[@]}" ]; then
  echo "警告: --only 中存在 manifest 里没有的用例 id" >&2
fi
if [ "$BUILT" -eq 0 ]; then
  echo "没有构建任何用例(检查 --only 拼写 / manifest cases)" >&2
  exit 1
fi

# 合并 SHA 清单:先剔除本次重建的 id 的旧行,再并入新行,按 id 排序保证输出稳定
SHA_FILE="$WORK_DIR/manifest-shas.txt"
MERGED_TMP="$(mktemp)"
if [ -f "$SHA_FILE" ]; then
  awk 'NR==FNR { built[$1] = 1; next } !($1 in built)' "$BUILT_TMP" "$SHA_FILE" >> "$MERGED_TMP"
fi
cat "$BUILT_TMP" >> "$MERGED_TMP"
sort -k1,1 "$MERGED_TMP" > "$SHA_FILE"
rm -f "$MERGED_TMP"

echo "---"
echo "built=$BUILT failed=$FAILED  sha 清单: $SHA_FILE"
[ "$FAILED" -eq 0 ] || exit 1
