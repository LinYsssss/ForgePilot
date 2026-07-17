package com.example.codereview.rag;

import java.util.List;
import java.util.Objects;

public interface EmbeddingClient {

    EmbeddingDescriptor descriptor();

    EmbeddingResult embed(String text);

    record EmbeddingDescriptor(String provider, String model, String version, int dimension) {
        public EmbeddingDescriptor {
            provider = requireText(provider, "provider");
            model = requireText(model, "model");
            version = requireText(version, "version");
            if (dimension < 0) {
                throw new IllegalArgumentException("embedding descriptor dimension must not be negative");
            }
        }

        public boolean matches(String storedProvider, String storedModel, String storedVersion, Integer storedDimension) {
            return provider.equals(storedProvider)
                    && model.equals(storedModel)
                    && version.equals(storedVersion)
                    && storedDimension != null
                    && storedDimension > 0
                    && (dimension == 0 || dimension == storedDimension);
        }

        private static String requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
            return value;
        }
    }

    record EmbeddingResult(
            String provider,
            String model,
            String version,
            int dimension,
            List<Double> vector
    ) {
        public EmbeddingResult {
            provider = requireText(provider, "provider");
            model = requireText(model, "model");
            version = requireText(version, "version");
            vector = List.copyOf(Objects.requireNonNull(vector, "vector"));
            if (dimension <= 0 || vector.size() != dimension) {
                throw new IllegalArgumentException("embedding dimension must match vector size");
            }
            if (vector.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
                throw new IllegalArgumentException("embedding values must be finite");
            }
        }

        private static String requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
            return value;
        }
    }
}
