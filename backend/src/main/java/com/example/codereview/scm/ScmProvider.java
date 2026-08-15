package com.example.codereview.scm;

import java.util.Map;
import java.util.Optional;

/**
 * provider 适配器:把某一家托管方的 webhook 方言翻译成共享的 SCM 领域模型。
 *
 * <p>每个适配器(GitHub、GitLab……)返回的都是<em>同一套</em>归一化记录,平台其余部分因此与
 * provider 无关。方法顺序刻意对齐安全边界:先从载荷里取身份 → 用身份选出该 installation 的密钥
 * → 针对**原始字节**验签 → 验过之后才做归一化。
 *
 * <p>传进来的 header map 约定由调用方保证大小写不敏感(或已统一转小写)。
 */
public interface ScmProvider {

    /** 本适配器负责的 provider 家族。 */
    ScmProviderType type();

    /**
     * 本次投递携带的 provider 侧 installation/project 标识,用来定位 {@link ScmInstallation}
     * 以及它的密钥。**只**从载荷的身份字段读取——绝不读密钥、host 等任何承载信任的值。
     *
     * @return 该标识;若投递里没有可解析的身份则返回 {@code null}
     */
    String resolveInstallationRef(byte[] rawBody, Map<String, String> headers);

    /**
     * 用解析出的 installation 密钥、针对**原始请求字节**验证投递,发生在任何 JSON 归一化之前。
     * 涉及 MAC 的比较一律走常量时间。
     */
    boolean verifySignature(byte[] rawBody, Map<String, String> headers, String secret);

    /**
     * 把一次可审查的 pull/merge request 投递归一化成共享模型。
     *
     * @return 归一化后的事件;对于我们有意忽略的投递(非 PR 事件、无关 action)返回
     *         {@link Optional#empty()}
     */
    Optional<NormalizedPullRequestEvent> normalize(byte[] rawBody, Map<String, String> headers);
}
