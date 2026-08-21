package com.forgepilot.scm;

import java.net.IDN;
import java.net.URI;
import java.util.Locale;

/**
 * Normalizes an {@code api_base} into the instance half of a repository's stable
 * identity (design.md 3.1): lowercase host, IDN as punycode, the default port for
 * the scheme dropped, path and trailing slash dropped, and {@code api.github.com}
 * folded onto {@code github.com}.
 *
 * <p>An internationalized host has to be given in its A-label (punycode) form:
 * {@code java.net.URI} parses a U-label authority as a registry name and reports no
 * host for it, so {@link OutboundUrlPolicy} refuses such a URL outright rather than
 * guessing what it meant.
 *
 * <p>The result is <em>stored</em> in {@code scm_repository.instance_identity}
 * rather than derived on read, because it freezes as soon as a pull request
 * exists: a normalization rule that changed later would silently re-identify every
 * repository already registered. It folds onto the user facing host rather than
 * the API host because an instance's API host can move between versions while its
 * site host does not.
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
        // A non-default port is part of the identity: two instances can differ by
        // nothing else.
        boolean defaultPort = port < 0 || port == ("https".equals(scheme) ? 443 : 80);
        String identity = defaultPort ? host : host + ":" + port;
        return GITHUB_API_HOST.equals(identity) ? GITHUB_HOST : identity;
    }

    private static String hostOf(URI apiBase) {
        String host = apiBase.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            return host; // An IPv6 literal keeps its brackets and has no IDN form.
        }
        // A host reaches this method having already passed OutboundUrlPolicy, which
        // means java.net.URI parsed it as a hostname — and it refuses to do that for
        // a U-label, returning no host at all. So an internationalized api_base has
        // to be supplied in its A-label form, and this call is what makes the rule
        // explicit rather than a coincidence.
        return IDN.toASCII(host);
    }
}
