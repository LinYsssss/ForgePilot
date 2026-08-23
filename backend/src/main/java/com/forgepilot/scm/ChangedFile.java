package com.forgepilot.scm;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * PR 变更文件清单中的一条。
 *
 * <p>当 provider 没提供 patch 时 {@code patch} 为 null——二进制文件，或超出
 * provider 自身 diff 上限的文件。「缺席」与「空」不是一回事，两者也绝不能哈希
 * 成同一个值，因此 {@link ReviewInputFingerprint} 显式编码了这个差别。
 * {@code changeType} 原样保存 provider 报告的值；在这里做归一化会改变
 * 指纹所覆盖的内容。
 */
public record ChangedFile(String path, String changeType, String patch) {

    /**
     * 整份清单作为**一个** JSONB 值存在 pull request 行上（D015.7），
     * 因此它必须始终装得下一行。超过这个字符数时，入库会显式失败，
     * 而不是静默截断——否则日后 Review 会被告知它“看过”一份其实不完整的清单（D002）。
     */
    public static final int MAX_TOTAL_CHARS = 4_000_000;

    /**
     * 按路径的 UTF-8 字节做无符号排序。provider 的分页顺序是惯例而非契约——
     * 一个 300 文件的 PR 会跨好几页——否则指纹就会依赖分页方式。
     * 用字节序而不是 locale 序，且路径保持大小写敏感。
     */
    public static List<ChangedFile> canonicalOrder(List<ChangedFile> files) {
        return files.stream()
                .sorted(Comparator.comparing(file -> file.path().getBytes(UTF_8), Arrays::compareUnsigned))
                .toList();
    }
}
