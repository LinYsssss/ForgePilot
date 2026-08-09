#!/usr/bin/env bash
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEMO="$ROOT_DIR/demo-repos"
FAILED=0

fail() { echo "FAIL: $*" >&2; FAILED=1; }
pass() { echo "ok  : $*"; }

check_java() {
  local repo="$1"
  local out
  out="$(mktemp -d)"
  local sources
  sources="$(find "$DEMO/$repo/src" -name '*.java' 2>/dev/null)"
  if [ -z "$sources" ]; then
    fail "$repo: no java sources found"
    rm -rf "$out"
    return
  fi
  # shellcheck disable=SC2086
  if javac -encoding UTF-8 -d "$out" $sources >"$out/log" 2>&1; then
    pass "$repo: javac"
  else
    fail "$repo: javac"
    head -20 "$out/log" >&2
  fi
  rm -rf "$out"
}

check_build_descriptor() {
  local repo="$1" file="$2"
  if [ -f "$DEMO/$repo/$file" ]; then
    pass "$repo: $file present"
  else
    fail "$repo: $file missing"
  fi
}

echo "--- 编译与语法 ---"
check_java mall-order-service
check_java payment-settlement-service
check_build_descriptor mall-order-service pom.xml
check_build_descriptor payment-settlement-service pom.xml
check_build_descriptor tenant-user-center pyproject.toml
check_build_descriptor tenant-user-center package.json

# 语法检查走内存编译：compileall 会在源码树里写下 __pycache__，而
# PYTHONDONTWRITEBYTECODE 对它无效（compileall 直接调用 py_compile，
# 不经过 import 系统）。Task 6 的确定性重建要求工作区无副产物，
# 故改用不落盘的 compile()；无源码文件时同样判失败。
PY_SYNTAX_CHECK='import pathlib, sys
paths = sorted(pathlib.Path(sys.argv[1]).rglob("*.py"))
if not paths:
    sys.exit("no python sources found")
for p in paths:
    compile(p.read_bytes(), str(p), "exec")'

# python3 而非裸 python：Ubuntu 22.04+ 等系统默认无 python 别名，
# 裸 python 会让本检查恒定误报 FAIL（与 verify-local.sh 口径统一）。
py_log="$(mktemp)"
if python3 -c "$PY_SYNTAX_CHECK" "$DEMO/tenant-user-center/src" >"$py_log" 2>&1; then
  pass "tenant-user-center: python syntax"
else
  fail "tenant-user-center: python syntax"
  head -20 "$py_log" >&2
fi
rm -f "$py_log"

for js in "$DEMO"/tenant-user-center/web/*.js; do
  [ -e "$js" ] || continue
  if node --check "$js" >/dev/null 2>&1; then
    pass "tenant-user-center: node --check $(basename "$js")"
  else
    fail "tenant-user-center: node --check $(basename "$js")"
  fi
done

echo "--- 重复类 ---"
if [ -d "$DEMO/mall-order-service/src/main/java/com/example/mallorder" ]; then
  fail "mall-order-service: duplicate package com.example.mallorder still present"
else
  pass "mall-order-service: no duplicate package"
fi

echo "--- SHA 确定性 ---"
if [ -f "$ROOT_DIR/scripts/demo-repos-expected-sha.txt" ]; then
  while read -r repo ref sha; do
    [ -z "${repo:-}" ] && continue
    if [ ! -d "$DEMO/$repo/.git" ]; then
      fail "$repo: not initialized, run scripts/init-demo-repos.sh"
      continue
    fi
    actual="$(git -C "$DEMO/$repo" rev-parse "$ref" 2>/dev/null || echo missing)"
    if [ "$actual" = "$sha" ]; then
      pass "$repo $ref"
    else
      fail "$repo $ref expected $sha but got $actual"
    fi
  done < "$ROOT_DIR/scripts/demo-repos-expected-sha.txt"
else
  fail "scripts/demo-repos-expected-sha.txt missing"
fi

exit "$FAILED"
