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
 * Decides whether this deployment may make an outbound HTTP call to a caller
 * supplied URL (design.md 3.5).
 *
 * <p>{@code scm_repository.api_base} is configured by a project LEADER and is then
 * dereferenced by the server, which makes it an SSRF entry point: pointed at
 * {@code 169.254.169.254} it reads cloud metadata, pointed at an internal address
 * it turns this system into a jump host. So the policy denies loopback, the
 * private ranges, link-local, unique-local and everything that is not http(s).
 *
 * <p>The only way through is the explicit host allowlist in
 * {@code forgepilot.scm.allowed-hosts}, which is <strong>empty in production</strong>
 * because the property is declared nowhere. Integration tests add {@code 127.0.0.1}
 * to it so their stub provider is reachable while the policy itself stays switched
 * on; {@code OutboundUrlPolicyTest} constructs the policy with an empty allowlist
 * and pins each denial, so "deny by default" is proven independently of any test
 * seam. A policy that ships having never executed would be worse than none.
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
     * Returns the URL as a URI if this deployment may call it, and refuses with the
     * same 422 for every reason: a caller must not learn which internal address
     * exists by comparing rejections.
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

    /** {@code URI.getHost()} keeps the brackets of an IPv6 literal; nothing else wants them. */
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
     * A name is resolved before it is judged, otherwise {@code localhost} or any
     * name an attacker controls walks straight past the literal checks. A name that
     * does not resolve is refused rather than attempted.
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

    /** fc00::/7. {@code isSiteLocalAddress} only covers the deprecated fec0::/10. */
    private static boolean isUniqueLocal(InetAddress address) {
        return address instanceof Inet6Address && (address.getAddress()[0] & 0xFE) == 0xFC;
    }

    private static ApiException denied() {
        return ApiException.unprocessable("That address is not reachable from this deployment.");
    }
}
