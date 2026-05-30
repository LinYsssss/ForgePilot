package com.example.codereview.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmbeddingJsonTest {

    private final EmbeddingJson embeddingJson = new EmbeddingJson(new ObjectMapper());

    @Test
    void serializeReadAndConvertVector() {
        List<Double> values = List.of(0.1, 0.2, 0.3);

        String json = embeddingJson.write(values);

        assertThat(embeddingJson.read(json)).containsExactly(0.1, 0.2, 0.3);
        assertThat(embeddingJson.toPgVector(values)).isEqualTo("[0.1,0.2,0.3]");
    }

    @Test
    void invalidJsonReturnsEmptyEmbedding() {
        assertThat(embeddingJson.read("not-json")).isEmpty();
    }
}
