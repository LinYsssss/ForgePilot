package com.example.codereview.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 解析请求的真实客户端 IP。
 *
 * <p>{@code X-Forwarded-For} 是**请求方可以任意伪造的普通请求头**。无条件采信它,等于让
 * 任何人每次请求换一个假 IP,就能拿到一个全新的限流桶——限流也就名存实亡。
 *
 * <p>因此只有当**直连对端**本身是受信代理时才读这个头;否则一律用 {@code remoteAddr}。
 * 读的时候从右往左跳过所有受信代理,取第一个非受信地址:最右侧是离我们最近的一跳追加的,
 * 越往左越接近客户端、也越可能是客户端自己伪造塞进去的。
 */
public final class ClientIpResolver {

    private final List<CidrRange> trusted;

    public ClientIpResolver(String trustedProxiesCsv) {
        this.trusted = parse(trustedProxiesCsv);
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (remote == null || remote.isBlank()) {
            return "unknown";
        }
        if (!isTrusted(remote)) {
            return remote; // 对端不受信,它给的转发头一律不采信
        }
        String header = request.getHeader("X-Forwarded-For");
        if (header == null || header.isBlank()) {
            return remote;
        }
        String[] hops = header.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String candidate = hops[i].trim();
            if (candidate.isEmpty()) {
                continue;
            }
            if (!isTrusted(candidate)) {
                return candidate;
            }
        }
        // 整条链都是受信代理,退回直连对端
        return remote;
    }

    boolean isTrusted(String ip) {
        for (CidrRange range : trusted) {
            if (range.contains(ip)) {
                return true;
            }
        }
        return false;
    }

    private static List<CidrRange> parse(String csv) {
        List<CidrRange> ranges = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return ranges;
        }
        for (String raw : csv.split(",")) {
            String entry = raw.trim();
            if (!entry.isEmpty()) {
                ranges.add(CidrRange.of(entry));
            }
        }
        return ranges;
    }

    /** 仅支持 IPv4 CIDR 与单个字面量;IPv6 走字面量精确匹配。 */
    private record CidrRange(String literal, long network, long mask, boolean cidr) {

        static CidrRange of(String entry) {
            int slash = entry.indexOf('/');
            if (slash < 0) {
                Long single = toLong(entry);
                return single == null
                        ? new CidrRange(entry.toLowerCase(Locale.ROOT), 0, 0, false)
                        : new CidrRange(entry, single, 0xFFFFFFFFL, true);
            }
            Long base = toLong(entry.substring(0, slash));
            if (base == null) {
                return new CidrRange(entry.toLowerCase(Locale.ROOT), 0, 0, false);
            }
            int bits = Integer.parseInt(entry.substring(slash + 1));
            long mask = bits == 0 ? 0 : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
            return new CidrRange(entry, base & mask, mask, true);
        }

        boolean contains(String ip) {
            if (!cidr) {
                return literal.equalsIgnoreCase(ip);
            }
            Long value = toLong(ip);
            return value != null && (value & mask) == network;
        }

        private static Long toLong(String ip) {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) {
                return null;
            }
            long value = 0;
            for (String part : parts) {
                if (part.isEmpty() || !part.chars().allMatch(Character::isDigit)) {
                    return null;
                }
                int octet = Integer.parseInt(part);
                if (octet < 0 || octet > 255) {
                    return null;
                }
                value = (value << 8) | octet;
            }
            return value;
        }
    }

    static List<String> defaults() {
        // Docker 网桥网段 + 本机:生产部署里 nginx 与后端同处 compose 网络
        return Arrays.asList("127.0.0.1", "::1", "172.16.0.0/12", "10.0.0.0/8", "192.168.0.0/16");
    }
}
