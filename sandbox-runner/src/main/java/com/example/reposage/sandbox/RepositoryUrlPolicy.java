package com.example.reposage.sandbox;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Rejects submodule URLs that could reach local or link-local services. */
public final class RepositoryUrlPolicy {

    public void validateSubmoduleUrl(String value) {
        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException ex) {
            throw new SecurityException("invalid submodule URL", ex);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new SecurityException("submodule URL scheme is not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank() || host.equalsIgnoreCase("localhost") || host.endsWith(".local")) {
            throw new SecurityException("submodule URL host is not allowed");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new SecurityException("submodule URL resolves to a private or local address");
                }
            }
        } catch (SecurityException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new SecurityException("submodule URL host cannot be resolved safely", ex);
        }
    }
}
