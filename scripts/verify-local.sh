#!/usr/bin/env bash
set -uo pipefail

# RepoSage 本机统一验证入口(Linux)。
# 与 CI(.github/workflows/ci.yml)口径一致,但按本机工具链可用性降级:
# 缺 Java/Maven 时后端与 sandbox-runner 记为 SKIPPED(未验证),不冒充通过。
# 用法: scripts/verify-local.sh [--frontend-only]

cd "$(dirname "$0")/.."

declare -a RESULTS=()
FAIL=0

record() { # name status
    RESULTS+=("$1: $2")
    # 注意用 if 而非 `[ ... ] && FAIL=1`:后者在非 FAIL 时使函数返回非零,
    # 会让调用处的 `|| record FAIL` 误触发,同一项被记两次。
    if [ "$2" = "FAIL" ]; then FAIL=1; fi
}

run_frontend() {
    ( cd frontend && npm test ) && record "frontend npm test" PASS || record "frontend npm test" FAIL
    ( cd frontend && npm run build ) && record "frontend npm run build" PASS || record "frontend npm run build" FAIL
}

run_backend() {
    if command -v mvn >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
        ( cd backend && mvn -s .mvn/settings.xml verify ) && record "backend mvn verify" PASS || record "backend mvn verify" FAIL
        ( cd sandbox-runner && mvn -B test ) && record "sandbox-runner mvn test" PASS || record "sandbox-runner mvn test" FAIL
    else
        record "backend mvn verify" "SKIPPED(no java/mvn — 未验证)"
        record "sandbox-runner mvn test" "SKIPPED(no java/mvn — 未验证)"
    fi
}

if [ "${1:-}" = "--frontend-only" ]; then
    run_frontend
else
    run_backend
    run_frontend
fi

echo
echo "==== verify-local 结果汇总 ===="
for r in "${RESULTS[@]}"; do echo "  $r"; done
exit $FAIL
