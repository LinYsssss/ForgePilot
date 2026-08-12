package com.acme.routing;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EndpointResolver {

    private final String defaultEndpoint;

    public EndpointResolver(@Value("${acme.routing.default-endpoint}") String defaultEndpoint) {
        this.defaultEndpoint = defaultEndpoint;
    }

    public String resolve() {
        return defaultEndpoint.toLowerCase(Locale.ROOT);
    }
}
