package com.forgepilot.scm;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.forgepilot.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 判定本部署是否允许向调用方提供的 URL 发起出站 HTTP 调用。
 *
 * <p>{@code scm_repository.api_base} 由项目 LEADER 配置，随后由服务端解引用，
 * 这使它成为一个 SSRF 入口：指向 {@code 169.254.169.254} 就能读云元数据，
 * 指向内网地址就把本系统变成跳板机。因此本策略拒绝回环地址、私有网段、
 * 链路本地、唯一本地地址，以及一切非 http(s) 的协议。
 *
 * <p>唯一的放行途径是 {@code forgepilot.scm.allowed-hosts} 里的显式主机白名单，
 * 而它在<strong>生产环境中为空</strong>——因为该配置项根本没有在任何地方声明。
 * 集成测试往里加 {@code 127.0.0.1}，让桩 provider 可达的同时策略本身仍然开启；
 * {@code OutboundUrlPolicyTest} 则用空白名单构造策略并逐条钉死拒绝行为，
 * 因此“默认拒绝”是独立于任何测试接缝被证明的。一条从未真正执行过的策略，
 * 比没有策略更糟。
 */
@Component
public class OutboundUrlPolicy {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final Set<String> allowedHosts;

    OutboundUrlPolicy(@Value("${forgepilot.scm.allowed-hosts:}") String allowedHosts) {
        this.allowedHosts = Arrays.stream(allowedHosts.split(","))
                .map(host -> host.trim().toLowerCase(Locale.ROOT))
                .filter(host -> !host.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 若本部署允许调用该 URL 则返回其 URI，否则以**同一个** 422 拒绝所有情况：
     * 调用方不得通过比较拒绝方式来推断哪个内网地址存在。
     */
    public URI requireAllowed(String url) {
        URI uri = parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw denied();
        }
        String host = hostOf(uri);
        if (host.isEmpty()) {
            throw denied();
        }
        if (allowedHosts.contains(host)) {
            return uri;
        }
        for (InetAddress address : resolve(host)) {
            if (isInternal(address)) {
                throw denied();
            }
        }
        return uri;
    }

    private static URI parse(String url) {
        if (url == null) {
            throw denied();
        }
        try {
            return new URI(url);
        } catch (URISyntaxException malformed) {
            throw denied();
        }
    }

    /** {@code URI.getHost()} 会保留 IPv6 字面量的方括号；此外没人想要它们。 */
    private static String hostOf(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            return "";
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        return host.toLowerCase(Locale.ROOT);
    }

    /**
     * 域名先解析再判定，否则 {@code localhost} 或任何攻击者控制的名字都能
     * 径直绕过针对字面量的检查。解析不出来的名字直接拒绝，而不是尝试连接。
     */
    private static InetAddress[] resolve(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException unknown) {
            throw denied();
        }
    }

    private static boolean isInternal(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isUniqueLocal(address);
    }

    /** fc00::/7。{@code isSiteLocalAddress} 只覆盖已废弃的 fec0::/10。 */
    private static boolean isUniqueLocal(InetAddress address) {
        return address instanceof Inet6Address && (address.getAddress()[0] & 0xFE) == 0xFC;
    }

    private static ApiException denied() {
        return ApiException.unprocessable("That address is not reachable from this deployment.");
    }
}
