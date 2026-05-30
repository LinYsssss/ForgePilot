package com.example.codereview.ai;

public interface AiReviewClient {

    AiReviewResult review(String diffText, String ragContext);
}
