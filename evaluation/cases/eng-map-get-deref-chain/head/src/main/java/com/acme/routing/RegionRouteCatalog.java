package com.acme.routing;

import java.util.Map;

/**
 * Loads the region-to-route table once at application startup.
 */
public interface RegionRouteCatalog {

    Map<String, RegionRoute> load();
}
