package com.example.codereview.scm;

/**
 * 支持的代码托管 provider。
 *
 * <p>用作 {@link ScmInstallation}、{@link WebhookDelivery} 的判别字段,以及适配器选择依据。
 * provider 的<em>行为</em>(验签、归一化、回写)都藏在 {@code ScmProvider} 适配器接口之后;
 * 本枚举只负责标识托管方家族,好让每个 installation 能定位到唯一的一份密钥与 API base。
 */
public enum ScmProviderType {
    GITHUB,
    GITLAB
}
