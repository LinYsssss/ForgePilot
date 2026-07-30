package com.example.codereview.git;

import com.example.codereview.common.exception.BusinessException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * 出站地址白/黑名单,用于阻断 SSRF。
 *
 * <p>平台会按用户提供的地址发起出站请求(git clone/fetch、SCM 回写),因此这些地址必须先被
 * 判定为“公网可路由的 http(s) 端点”。仅校验协议是不够的:{@code http://169.254.169.254/} 或
 * {@code http://127.0.0.1:8080/actuator} 都是合法 URL,却会让服务端去打自己的内网。
 *
 * <p>解析后对**所有** A/AAAA 记录逐一判定,任一落在内网即拒绝——只查第一条会被多记录域名绕过。
 * 注意这里无法根治 DNS rebinding(校验与实际连接之间存在时间差),那需要在连接层固定 IP;
 * 本策略负责挡住绝大多数直接指向内网的情形。
 */
public final class OutboundUrlPolicy {

    private OutboundUrlPolicy() {
    }

    /**
     * @param allowLoopback 开发/测试放行本机地址(对应 app.git.allow-local-path 与
     *                      app.scm.allow-insecure-localhost),生产必须为 false
     */
    public static void requirePublicHttpUrl(String rawUrl, String field, boolean allowLoopback) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new BusinessException(400, field + "不能为空");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(400, "非法的" + field);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BusinessException(400, field + "仅支持 http/https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            // 形如 http://user@/path 或十进制主机被 URI 判为非法 authority
            throw new BusinessException(400, "非法的" + field);
        }
        // 去掉 IPv6 字面量的方括号再解析
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (isBlockedHostname(bare, allowLoopback)) {
            throw new BusinessException(400, field + "指向内网或保留地址，已拒绝");
        }
        if (isAmbiguousNumericHost(bare)) {
            throw new BusinessException(400, field + "使用了非常规的 IP 写法，已拒绝");
        }

        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(bare);
        } catch (UnknownHostException ex) {
            // 解析不了不构成危险:真正的 SSRF 用的是字面量 IP 或能解析的主机名,而后者已在下面逐条判定。
            // 若把解析失败也判为非法,DNS 抖动就会把正常仓库地址拒掉,属于误伤。后续 git 操作自会失败。
            return;
        }
        for (InetAddress address : resolved) {
            if (isBlocked(address, allowLoopback)) {
                throw new BusinessException(400, field + "指向内网或保留地址，已拒绝");
            }
        }
    }

    /**
     * 不依赖 DNS 的主机名黑名单:即使解析不到也要拒绝。
     *
     * <p>{@code localhost} 按 RFC 6761 永远指向本机,因此与字面量 {@code 127.0.0.1} 同等对待、
     * 一并交给 {@code allowLoopback} 决定;否则「放行本机」在 IP 写法下生效、在名字写法下失效,
     * 联调环境只能写 IP。{@code .local}/{@code .internal} 与云元数据域名不属于本机,任何模式下都拒绝。
     */
    static boolean isBlockedHostname(String host, boolean allowLoopback) {
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".localhost")) {
            return !allowLoopback;
        }
        return lower.endsWith(".local")
                || lower.equals("metadata.google.internal")   // GCP 元数据
                || lower.endsWith(".internal");
    }

    /**
     * 拒绝十六进制 / 八进制 / 整数等非常规 IPv4 写法,例如 {@code 0x7f000001}、
     * {@code 017700000001}、{@code 2130706433} 都指向 127.0.0.1。
     *
     * <p>之所以直接拒绝而不是解析后判定:git / curl 走 glibc 的 inet_aton,与 Java 的
     * {@link InetAddress} 解析规则并不一致,按 Java 的结果放行可能与实际连接的地址不同。
     * 正常仓库地址不会用这些写法,拒绝是安全且无损的。
     */
    static boolean isAmbiguousNumericHost(String host) {
        if (host.isEmpty() || host.contains(":")) {
            return false; // IPv6 字面量另有判定
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.startsWith("0x")) {
            return true;
        }
        String[] parts = lower.split("\\.", -1);
        boolean allNumericish = true;
        for (String part : parts) {
            if (part.isEmpty() || !part.chars().allMatch(c -> Character.digit(c, 16) >= 0 || c == 'x')) {
                allNumericish = false;
                break;
            }
        }
        if (!allNumericish) {
            return false; // 含普通字母,是主机名
        }
        for (String part : parts) {
            if (part.startsWith("0x") || (part.length() > 1 && part.startsWith("0"))) {
                return true; // 十六进制段 / 八进制段
            }
            if (!part.chars().allMatch(Character::isDigit)) {
                return true; // 纯十六进制段(如 7f)
            }
        }
        // 仅剩十进制:必须是标准的四段点分写法,单个整数(2130706433)一律拒绝
        return parts.length != 4;
    }

    static boolean isBlocked(InetAddress address, boolean allowLoopback) {
        if (address.isLoopbackAddress()) {
            return !allowLoopback;
        }
        return address.isAnyLocalAddress()          // 0.0.0.0 / ::
                || address.isLinkLocalAddress()      // 169.254.0.0/16(含云元数据)、fe80::/10
                || address.isSiteLocalAddress()      // 10/8、172.16/12、192.168/16
                || address.isMulticastAddress()
                || isUniqueLocalV6(address)          // fc00::/7
                || isCarrierGradeNat(address)        // 100.64/10
                || isReservedV4(address);            // 192.0.0/24、198.18/15、240/4 等
    }

    private static boolean isUniqueLocalV6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    private static boolean isCarrierGradeNat(InetAddress address) {
        byte[] b = address.getAddress();
        return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xFF) >= 64 && (b[1] & 0xFF) <= 127;
    }

    private static boolean isReservedV4(InetAddress address) {
        byte[] b = address.getAddress();
        if (b.length != 4) {
            return false;
        }
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        return first == 0                                   // 0.0.0.0/8
                || first == 127                             // 兜底(isLoopback 已覆盖)
                || (first == 192 && second == 0)            // 192.0.0/24 IETF 协议分配
                || (first == 198 && (second == 18 || second == 19)) // 198.18/15 基准测试
                || first >= 240;                            // 240/4 保留 + 255.255.255.255
    }
}
