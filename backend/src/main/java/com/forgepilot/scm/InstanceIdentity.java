package com.forgepilot.scm;

import java.net.IDN;
import java.net.URI;
import java.util.Locale;

/**
 * 把 {@code api_base} 归一化成仓库稳定身份中的「实例」那一半（design.md 3.1）：
 * host 小写、IDN 转 punycode、丢掉该 scheme 的默认端口、丢掉 path 与末尾斜杠，
 * 并把 {@code api.github.com} 折叠到 {@code github.com}。
 *
 * <p>国际化域名必须以 A-label（punycode）形式给出：{@code java.net.URI} 会把
 * U-label 的 authority 当作 registry name 解析并且报告不出 host，因此
 * {@link OutboundUrlPolicy} 会直接拒绝这种 URL，而不是去猜它想表达什么。
 *
 * <p>结果是**存**在 {@code scm_repository.instance_identity} 里的，而不是读取时
 * 现算，因为它在有了第一个 PR 之后就冻结了：日后改动归一化规则会悄悄地把
 * 所有已注册仓库重新识别一遍。它折叠到面向用户的 host 而非 API host，
 * 是因为一个实例的 API host 可能随版本迁移，而站点 host 不会。
 */
public final class InstanceIdentity {

    private static final String GITHUB_API_HOST = "api.github.com";
    private static final String GITHUB_HOST = "github.com";

    private InstanceIdentity() {
    }

    public static String of(URI apiBase) {
        String host = hostOf(apiBase);
        String scheme = apiBase.getScheme().toLowerCase(Locale.ROOT);
        int port = apiBase.getPort();
        // 非默认端口属于身份的一部分：两个实例可能只在端口上不同。
        boolean defaultPort = port < 0 || port == ("https".equals(scheme) ? 443 : 80);
        String identity = defaultPort ? host : host + ":" + port;
        return GITHUB_API_HOST.equals(identity) ? GITHUB_HOST : identity;
    }

    private static String hostOf(URI apiBase) {
        String host = apiBase.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            return host; // An IPv6 literal keeps its brackets and has no IDN form.
        }
        // 能走到这个方法的 host 都已经通过了 OutboundUrlPolicy，也就意味着
        // java.net.URI 把它解析成了主机名——而对 U-label 它拒绝这么做，
        // 会直接返回没有 host。所以国际化的 api_base 必须以 A-label 形式提供，
        // 这次调用正是把这条规则写明，而不是让它成为一个巧合。
        return IDN.toASCII(host);
    }
}
