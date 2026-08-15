package com.example.reposage.sandbox;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** 拒绝可能触达本机或链路本地(link-local)服务的子模块 URL,防 SSRF 经 submodule 绕进内网。 */
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
