#!/usr/bin/env bash
set -euo pipefail

# 镜像漏洞扫描(P1-24)。HIGH/CRITICAL 且上游已发布修复版本的漏洞即判失败;
# 无修复版本的暂不拦截(拦了也只能干瞪眼),由镜像升级例行消化。
#
# 用法: deploy/scan-images.sh [镜像...]
#   缺省扫描 compose 构建出的四个业务镜像(本机演示环境的真实部署物)。
#   CI 里传入刚构建的 :ci 标签即可复用同一门禁。

IMAGES=("$@")
if [ ${#IMAGES[@]} -eq 0 ]; then
    IMAGES=(deploy-backend:latest deploy-frontend:latest deploy-sandbox-runner:latest deploy-model-service:latest)
fi

# 固定扫描器版本保证结果可复现;漏洞库仍是联网拉最新的,结论会随时间演进,这是预期行为。
TRIVY_IMAGE="aquasec/trivy:0.58.2"

fail=0
for img in "${IMAGES[@]}"; do
    echo "==> 扫描 $img"
    docker run --rm \
        -v /var/run/docker.sock:/var/run/docker.sock \
        -v reposage_trivy_cache:/root/.cache \
        "$TRIVY_IMAGE" image \
        --scanners vuln \
        --severity HIGH,CRITICAL \
        --ignore-unfixed \
        --exit-code 1 \
        "$img" || fail=1
done

if [ "$fail" -ne 0 ]; then
    echo "FAIL: 存在已有修复版本的 HIGH/CRITICAL 漏洞,升级依赖或基础镜像后重扫"
    exit 1
fi
echo "PASS: 镜像无已修复可得的 HIGH/CRITICAL 漏洞"
