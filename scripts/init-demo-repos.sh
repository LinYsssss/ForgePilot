#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEMO="$ROOT_DIR/demo-repos"
PATCHES="$DEMO/patches"
EXPECTED="$ROOT_DIR/scripts/demo-repos-expected-sha.txt"
VERIFY=0
[ "${1:-}" = "--verify" ] && VERIFY=1

export GIT_AUTHOR_NAME="RepoSage Demo"
export GIT_AUTHOR_EMAIL="demo@reposage.local"
export GIT_COMMITTER_NAME="RepoSage Demo"
export GIT_COMMITTER_EMAIL="demo@reposage.local"

# repo|feature-branch|patch-file
REPOS=(
  "mall-order-service|feature/promotion-batch-ship|feature-promotion-batch-ship.patch"
  "payment-settlement-service|feature/instant-settlement|feature-instant-settlement.patch"
  "tenant-user-center|feature/ops-console|feature-ops-console.patch"
)

commit_at() {
  local stamp="$1" message="$2"
  GIT_AUTHOR_DATE="$stamp" GIT_COMMITTER_DATE="$stamp" \
    git commit -q --no-gpg-sign -m "$message"
}

build_repo() {
  local repo="$1" branch="$2" patch="$3"
  local dir="$DEMO/$repo"

  [ -d "$dir" ] || { echo "missing demo repo: $dir" >&2; exit 1; }
  [ -f "$PATCHES/$repo/$patch" ] || { echo "missing patch: $PATCHES/$repo/$patch" >&2; exit 1; }

  if [ -e "$dir/.git" ]; then
    echo "already initialized, skipping: $repo"
    return 0
  fi

  git -C "$dir" init -q -b main
  git -C "$dir" config core.autocrlf false
  git -C "$dir" config core.eol lf
  # 索引里的 mode 位参与 tree 哈希。若某人在 Linux 上给文件加了执行位，
  # 不钉死 filemode 会让 Windows 与 Linux 产出不同的 tree。
  git -C "$dir" config core.filemode false
  # `git add -A` 会套用用户的全局 ignore（core.excludesFile 或
  # ~/.config/git/ignore）。别人机器上一条 `target/` 会漏加文件、
  # macOS 的 `.DS_Store` 会多加，两个方向都会毁掉 SHA。仓库级
  # core.excludesFile 会覆盖全局与 XDG 两条来源；指向一个保证不存在
  # 的路径即等于「无全局排除」，且 git 对缺失的排除文件静默略过。
  git -C "$dir" config core.excludesFile "$dir/.git/no-global-excludes"
  git -C "$dir" config commit.gpgsign false

  # commit 1: 源码与构建描述
  git -C "$dir" add -A ':!docs' ':!README.md'
  ( cd "$dir" && commit_at "2026-01-15T10:00:00+08:00" "feat: initial service implementation" )

  # commit 2: 知识文档
  git -C "$dir" add -A
  ( cd "$dir" && commit_at "2026-01-15T11:00:00+08:00" "docs: add knowledge base for review context" )

  # feature 分支
  git -C "$dir" switch -q -c "$branch"
  git -C "$dir" apply "$PATCHES/$repo/$patch"
  git -C "$dir" add -A
  ( cd "$dir" && commit_at "2026-01-15T14:00:00+08:00" "feat: implement the new feature for review" )
  git -C "$dir" switch -q main

  echo "initialized: $repo"
}

for entry in "${REPOS[@]}"; do
  IFS='|' read -r repo branch patch <<< "$entry"
  build_repo "$repo" "$branch" "$patch"
done

if [ "$VERIFY" = "1" ]; then
  [ -f "$EXPECTED" ] || { echo "expected sha list not found: $EXPECTED" >&2; exit 1; }
  status=0
  while read -r repo ref sha; do
    [ -z "${repo:-}" ] && continue
    actual="$(git -C "$DEMO/$repo" rev-parse "$ref")"
    if [ "$actual" = "$sha" ]; then
      echo "ok  : $repo $ref $sha"
    else
      echo "FAIL: $repo $ref expected $sha but got $actual" >&2
      status=1
    fi
  done < "$EXPECTED"
  exit "$status"
fi
