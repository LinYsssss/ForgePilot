package com.example.codereview.rag;

import java.util.List;

public interface EmbeddingClient {

    List<Double> embed(String text);
}
