package com.acme.routing;

/**
 * One routing entry per tenant region, loaded from configuration at startup.
 */
public record RegionRoute(String region, String primaryEndpoint, String fallbackEndpoint) {
}
