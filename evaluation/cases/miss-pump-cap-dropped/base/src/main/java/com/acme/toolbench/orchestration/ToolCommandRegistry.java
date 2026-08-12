package com.acme.toolbench.orchestration;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具命令注册表：工具名 → 命令行模板。仅允许注册白名单内的可执行名，
 * 参数不做 shell 展开（ProcessBuilder 直接传参，无注入面）。
 */
public final class ToolCommandRegistry {

    private static final Set<String> ALLOWED_BINARIES = Set.of(
            "acme-lint", "acme-sbom", "acme-depscan");

    private ToolCommandRegistry() {
    }

    /** 校验并归一注册表；不在白名单的可执行名直接拒绝。 */
    public static Map<String, List<String>> validated(Map<String, List<String>> raw) {
        for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
            List<String> command = entry.getValue();
            if (command == null || command.isEmpty()) {
                throw new IllegalArgumentException("工具命令为空: " + entry.getKey());
            }
            if (!ALLOWED_BINARIES.contains(command.get(0))) {
                throw new IllegalArgumentException(
                        "可执行名不在白名单: " + command.get(0));
            }
        }
        return Map.copyOf(raw);
    }
}
