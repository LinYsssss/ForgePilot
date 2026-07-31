#!/usr/bin/env bash
set -euo pipefail

# 验证 deploy/nginx.conf 的安全响应头(P1-16)。
#
# 不需要整套 compose:单独起一个挂载了本配置的 nginx,请求一条不经 upstream 的路径
# (/actuator/ 前缀直接 return 404),断言六个安全头齐全。因为配置里用了 `always`,
# 4xx 也必须带头 —— 这条断言顺带把 `always` 是否遗漏也测了。
#
# 用法: deploy/test-nginx-headers.sh   (需要 docker;CI 与本机通用)

cd "$(dirname "$0")"

NGINX_IMAGE="nginx:1.30.4-alpine"

echo "==> nginx -t 语法检查"
docker run --rm -v "$PWD/nginx.conf":/etc/nginx/conf.d/default.conf:ro "$NGINX_IMAGE" nginx -t

echo "==> 启动一次性 nginx 实例"
CID="$(docker run -d --rm -v "$PWD/nginx.conf":/etc/nginx/conf.d/default.conf:ro -p 127.0.0.1:0:80 "$NGINX_IMAGE")"
trap 'docker stop "$CID" >/dev/null 2>&1 || true' EXIT

PORT="$(docker port "$CID" 80/tcp | head -n1 | awk -F: '{print $NF}')"
for _ in $(seq 1 20); do
    # 404 也算“就绪”:不用 -f,只要 nginx 应答了 HTTP 就退出等待
    curl -sS -o /dev/null "http://127.0.0.1:$PORT/actuator/blocked" 2>/dev/null && break || sleep 0.5
done

HEADERS="$(curl -sS -o /dev/null -D - "http://127.0.0.1:$PORT/actuator/blocked")"

fail=0
expect() {
    local pattern="$1" label="$2"
    if grep -qiE "$pattern" <<<"$HEADERS"; then
        echo "  ok   $label"
    else
        echo "  MISS $label"
        fail=1
    fi
}

echo "==> 断言安全头(404 响应上,验证 always)"
grep -q "404" <<<"$HEADERS" || { echo "  MISS 期望 /actuator/* 直接 404"; fail=1; }
expect '^x-content-type-options: *nosniff' "X-Content-Type-Options: nosniff"
expect '^x-frame-options: *DENY' "X-Frame-Options: DENY"
expect '^referrer-policy: *no-referrer' "Referrer-Policy: no-referrer"
expect '^permissions-policy: .*camera=\(\)' "Permissions-Policy 禁用高危能力"
expect '^content-security-policy: .*default-src '"'"'self'"'"'' "Content-Security-Policy 默认仅同源"
expect '^content-security-policy: .*frame-ancestors '"'"'none'"'"'' "CSP frame-ancestors 'none'"
expect '^strict-transport-security: *max-age=' "Strict-Transport-Security"
if grep -qiE '^server: *nginx/' <<<"$HEADERS"; then
    echo "  MISS server_tokens off(版本号不应外露)"
    fail=1
else
    echo "  ok   server_tokens off"
fi

[ "$fail" -eq 0 ] && echo "PASS: nginx 安全头齐全" || { echo "FAIL: 安全头缺失,见上"; exit 1; }
