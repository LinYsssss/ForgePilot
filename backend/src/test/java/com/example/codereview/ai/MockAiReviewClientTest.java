package com.example.codereview.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MockAiReviewClientTest {

    private final MockAiReviewClient client = new MockAiReviewClient();

    @Test
    void detectsAdminEndpointWithoutAuthorization() {
        String diff = """
                diff --git a/src/main/java/AdminOrderController.java b/src/main/java/AdminOrderController.java
                +++ b/src/main/java/AdminOrderController.java
                @@
                +@PostMapping("/admin/orders/{id}/force-ship")
                +public void forceShip(@PathVariable Long id) {
                +    orderService.forceShip(id);
                +}
                """;

        AiReviewResult result = client.review(diff, "source=security-policy.md\n管理员接口必须鉴权");

        assertThat(result.overallRisk()).isEqualTo("HIGH");
        assertThat(result.issues()).extracting(AiReviewResult.Issue::category).contains("AUTH_RISK");
        assertThat(result.issues().get(0).filePath()).isEqualTo("src/main/java/AdminOrderController.java");
        assertThat(result.issues().get(0).evidence()).contains("security-policy.md");
    }

    @Test
    void detectsSqlStringConcatenation() {
        String diff = """
                diff --git a/src/main/java/OrderRepository.java b/src/main/java/OrderRepository.java
                +++ b/src/main/java/OrderRepository.java
                @@
                +String sql = "select * from orders where keyword = '" + keyword + "'";
                """;

        AiReviewResult result = client.review(diff, "");

        assertThat(result.issues()).extracting(AiReviewResult.Issue::category).contains("SQL_INJECTION");
    }
}
