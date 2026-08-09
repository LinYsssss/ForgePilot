package com.example.codereview.common;

import java.util.regex.Pattern;

/**
 * 镜像按 digest 固定({@code image@sha256:...})的格式校验,单一事实源。
 *
 * <p>两档严格度服务不同场景,集中在一个文件里避免正则在多处漂移:
 *
 * <ul>
 *   <li>{@link #isPinned}:形状校验。评测语料 manifest 是版本化 fixture,其 toolImage 使用
 *       缩写的假 digest,只需断言"确实按 digest 固定"这一策略形状
 *       (沿用 EvaluationCorpusService 的历史行为,不能因配置校验收紧而破坏冻结语料);</li>
 *   <li>{@link #isStrictlyPinned}:部署校验。生产环境必须是完整 64 位小写 hex 的真实
 *       digest,容器运行时才能直接解析拉取。</li>
 * </ul>
 */
public final class PinnedImageDigests {

    private static final Pattern PINNED = Pattern.compile(".+@sha256:[a-fA-F0-9]+");
    private static final Pattern STRICTLY_PINNED = Pattern.compile(".+@sha256:[0-9a-f]{64}");

    private PinnedImageDigests() {
    }

    /** 形状校验:是否按 digest 固定(允许缩写 digest,评测语料 fixture 专用)。 */
    public static boolean isPinned(String imageRef) {
        return imageRef != null && PINNED.matcher(imageRef).matches();
    }

    /** 部署校验:是否为完整 64 位小写 hex 的真实 digest 固定引用。 */
    public static boolean isStrictlyPinned(String imageRef) {
        return imageRef != null && STRICTLY_PINNED.matcher(imageRef).matches();
    }
}
