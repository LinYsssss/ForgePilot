package com.acme.routing;

import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class EndpointResolver {

    private final Map<String, RegionRoute> routesByRegion;

    public EndpointResolver(RegionRouteCatalog catalog) {
        Map<String, RegionRoute> loaded = catalog.load();
        if (loaded.isEmpty()) {
            throw new IllegalStateException("region route catalog must not be empty");
        }
        this.routesByRegion = Map.copyOf(loaded);
    }

    public String resolve(String tenantRegion) {
        String normalized = tenantRegion == null ? "" : tenantRegion.trim();
        return routesByRegion.get(normalized).primaryEndpoint().toLowerCase(Locale.ROOT);
    }
}
