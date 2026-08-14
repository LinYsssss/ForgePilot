#!/usr/bin/env bash
set -euo pipefail

# RepoSage TLS 自签证书生成(演示/内网部署开箱用;公网正式部署换真实证书,见 README.md)。
#
# 用法(在仓库任意位置执行均可):
#   deploy/tls/gen-self-signed.sh [--force] [额外主机 ...]
# 示例:
#   deploy/tls/gen-self-signed.sh                       # SAN=localhost+127.0.0.1
#   deploy/tls/gen-self-signed.sh 192.0.2.10 demo.example.com
#
# 幂等约定:certs/ 下已有证书对时直接成功退出,反复执行不会覆盖正在使用的证书
# (nginx 挂载的是同名文件,悄悄重签会让运行中容器与磁盘内容不一致);
# 确要重签,传 --force。

cd "$(dirname "$0")"

FORCE=0
HOSTS=()
for arg in "$@"; do
    case "$arg" in
        --force) FORCE=1 ;;
        *) HOSTS+=("$arg") ;;
    esac
done

CERT_DIR=./certs
CRT="$CERT_DIR/server.crt"
KEY="$CERT_DIR/server.key"

if [[ -f "$CRT" && -f "$KEY" && "$FORCE" -ne 1 ]]; then
    echo "OK: 证书已存在($CRT),跳过生成;需要重签请加 --force。"
    exit 0
fi

mkdir -p "$CERT_DIR"

# SAN 基线固定含 localhost 与回环地址;额外主机按形态自动归入 IP/DNS 条目
# (现代浏览器只看 SAN,不看 CN,漏配 SAN 的证书会被直接判不可信)。
SAN="DNS:localhost,IP:127.0.0.1"
for h in ${HOSTS[@]+"${HOSTS[@]}"}; do
    if [[ "$h" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        SAN="$SAN,IP:$h"
    else
        SAN="$SAN,DNS:$h"
    fi
done

# -nodes:私钥不加口令 —— nginx 容器无人值守启动,带口令的私钥起不来。
# -addext 需要 openssl 1.1.1+(目标服务器 Ubuntu 22.04+ 自带 3.x,满足)。
openssl req -x509 -newkey rsa:2048 -sha256 -days 365 -nodes \
    -keyout "$KEY" -out "$CRT" \
    -subj "/CN=RepoSage Demo" \
    -addext "subjectAltName=$SAN"

# 私钥收紧到属主可读写:容器侧是只读挂载,宿主上不该有其他读者。
chmod 600 "$KEY"

echo "OK: 已生成 $CRT / $KEY(SAN=$SAN,有效期 365 天)"
