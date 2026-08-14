#!/usr/bin/env bash
set -euo pipefail

# 本机(无 Docker)模拟验证 deploy/observability/alertmanager-entrypoint.sh 的
# 配置生成逻辑:把入口脚本的容器路径改写到临时沙盒、剥掉最后的 exec 行,
# 用真实 sh 跑三条路径(未设 URL / 空 URL / 带 & 查询参数的 URL),
# 并用 python 校验生成产物是合法 YAML 且 URL 原样(& 未被 sed 吞掉)。
#
# 为什么必须落盘执行而不是内联敲命令:Windows 侧的命令传递层会把命令串里的
# `\\` 折叠成 `\`(2026-08-14 实测,printf '[%s]' 'a\\b' 输出 [a\b]),内联
# 复述脚本里的 sed 表达式会被改写而得出假阴性;从磁盘文件执行不经过该层。
#
# 用法:bash .trellis/tasks/08-13-production-hardening/research/verify-alertmanager-entrypoint-local.sh

cd "$(dirname "$0")/../../../.."

WORK=".tmp-am-verify"
rm -rf "$WORK"
mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT

cp deploy/observability/alertmanager.yml "$WORK/src.yml"

# 改写容器绝对路径到沙盒;剥掉 exec(本机没有 /bin/alertmanager)。
# 注意 sed 只替换路径字面量,不触碰脚本里的引号与反斜杠。
grep -v '^exec /bin/alertmanager' deploy/observability/alertmanager-entrypoint.sh \
  | sed -e "s|/etc/alertmanager/alertmanager.yml|$WORK/src.yml|g" \
        -e "s|/alertmanager/alertmanager.generated.yml|$WORK/generated.yml|g" \
  > "$WORK/entry.sh"

echo "==> 用例 1:ALERT_WEBHOOK_URL 未设置 -> 不生成运行时配置,直接用原始文件"
env -u ALERT_WEBHOOK_URL sh "$WORK/entry.sh"
[ ! -f "$WORK/generated.yml" ] || { echo "FAIL: 未设 URL 不该生成配置"; exit 1; }
echo "    ok"

echo "==> 用例 2:ALERT_WEBHOOK_URL 为空串 -> 同样不生成"
ALERT_WEBHOOK_URL= sh "$WORK/entry.sh"
[ ! -f "$WORK/generated.yml" ] || { echo "FAIL: 空 URL 不该生成配置"; exit 1; }
echo "    ok"

echo "==> 用例 3:带 & 查询参数的 URL -> 生成合法 YAML,URL 原样注入"
ALERT_WEBHOOK_URL='https://oapi.example.com/robot/send?access_token=abc&x=1' sh "$WORK/entry.sh"
[ -f "$WORK/generated.yml" ] || { echo "FAIL: 应生成运行时配置"; exit 1; }
# python 经 stdin 读文件:Windows python 打不开 msys 虚拟路径,重定向由 bash 承担。
PYTHONUTF8=1 python -c '
import sys, yaml
d = yaml.safe_load(sys.stdin.buffer.read().decode("utf-8"))
wc = d["receivers"][0]["webhook_configs"]
expected = "https://oapi.example.com/robot/send?access_token=abc&x=1"
assert wc == [{"url": expected}], f"URL 被改写: {wc}"
assert d["route"]["receiver"] == "default"
print("    ok  URL 原样 =", wc[0]["url"])
' < "$WORK/generated.yml"

echo "PASS: alertmanager-entrypoint 三条路径本机模拟全部通过"
