#!/bin/sh
# =============================================================================
# Alertmanager 入口包装:把 ALERT_WEBHOOK_URL 环境变量注入配置后再启动。
#
# 为什么存在:Alertmanager 镜像/配置格式不支持环境变量插值,而外发 webhook
# 地址属部署机密,不能写死进仓库配置。约定(与 alertmanager.yml 头注对应):
#   ALERT_WEBHOOK_URL 为空  → 直接用只读挂载的原始配置(空接收器,安全落地);
#   ALERT_WEBHOOK_URL 非空 → 把 #__WEBHOOK_SLOT__ 占位行替换为 webhook_configs
#                            段,生成运行时配置再启动。
# 占位行是合法 YAML 注释,因此原始文件永远可被直接解析/校验。
# 生成物写到 /alertmanager(镜像里 nobody 用户唯一保证可写的目录),不碰只读挂载。
# 只依赖 busybox sh/sed:官方镜像基于 prometheus busybox,没有 bash/envsubst。
# =============================================================================
set -eu

SRC=/etc/alertmanager/alertmanager.yml
CFG="$SRC"

if [ -n "${ALERT_WEBHOOK_URL:-}" ]; then
    CFG=/alertmanager/alertmanager.generated.yml
    # sed 替换文本里 & 与 \ 有特殊含义(& 会展开成整个匹配),URL 带查询参数时
    # 必须先转义,否则地址会被悄悄改写。分隔符选 |:RFC 3986 要求 URL 里的竖线
    # 必须百分号编码,合法 URL 撞不上;真撞上 sed 会报错退出,不会静默出错配置。
    escaped=$(printf '%s' "$ALERT_WEBHOOK_URL" | sed 's/[&\\]/\\&/g')
    sed "s|^\\( *\\)#__WEBHOOK_SLOT__.*|\\1webhook_configs: [{ url: \"${escaped}\" }]|" "$SRC" > "$CFG"
fi

exec /bin/alertmanager --config.file="$CFG" --storage.path=/alertmanager
