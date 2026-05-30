package com.example.codereview.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.ai", name = "embedding-provider", havingValue = "mock", matchIfMissing = true)
public class MockEmbeddingClient implements EmbeddingClient {

    private static final int DIMENSIONS = 64;

    @Override
    public List<Double> embed(String text) {
        double[] vector = new double[DIMENSIONS];
        String normalized = text == null ? "" : text.toLowerCase();
        for (String token : normalized.split("[^\\p{IsHan}\\p{Alnum}_]+")) {
            if (token.length() < 2) {
                continue;
            }
            int idx = Math.floorMod(hash(token), DIMENSIONS);
            vector[idx] += 1.0;
        }
        double norm = 0;
        for (double value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        List<Double> result = new ArrayList<>(DIMENSIONS);
        for (double value : vector) {
            result.add(norm == 0 ? 0.0 : value / norm);
        }
        return result;
    }

    private int hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return ((digest[0] & 0xff) << 24) | ((digest[1] & 0xff) << 16) | ((digest[2] & 0xff) << 8) | (digest[3] & 0xff);
        } catch (Exception ex) {
            return value.hashCode();
        }
    }
}
