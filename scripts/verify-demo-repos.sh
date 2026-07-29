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

if python -m compileall -q "$DEMO/tenant-user-center/src" >/dev/null 2>&1; then
  pass "tenant-user-center: python compileall"
else
  fail "tenant-user-center: python compileall"
fi

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

exit "$FAILED"
