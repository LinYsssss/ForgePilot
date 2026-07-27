package com.example.codereview.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.codereview.review.ReviewDtos.ReviewIssueResponse;
import com.example.codereview.review.ReviewDtos.ReviewReportDetail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewReportExporterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReviewReportExporter exporter = new ReviewReportExporter(objectMapper);

    private ReviewIssueResponse issue(String severity, String category, String path,
                                      Integer start, Integer end, Double confidence) {
        return new ReviewIssueResponse(1L, severity, category, path, start, end,
                "发货未校验支付状态", "forceShip 直接改状态", "可能造成资损",
                "OrderService.java:21", "补充支付状态校验", confidence);
    }

    private ReviewReportDetail report(List<ReviewIssueResponse> issues) {
        return new ReviewReportDetail(42L, 7L, "7fd1a60b", "HIGH", "存在高危问题",
                issues.size(), Instant.parse("2026-07-27T00:00:00Z"), issues);
    }

    @Test
    void markdownContainsHeaderTallyAndIssueSections() {
        String md = exporter.toMarkdown(report(List.of(
                issue("HIGH", "security", "src/OrderService.java", 21, 28, 0.86),
                issue("LOW", "style", "src/App.java", 5, 5, null))));

        assertThat(md).contains("# 审查报告 #42");
        assertThat(md).contains("| 总体风险 | HIGH |");
        assertThat(md).contains("**HIGH**: 1").contains("**LOW**: 1");
        assertThat(md).contains("`src/OrderService.java:21-28`"); // 跨行区间
        assertThat(md).contains("`src/App.java:5`");              // 单行不写成 5-5
        assertThat(md).contains("**置信度**: 86%");
        assertThat(md).contains("**修复建议**");
    }

    @Test
    void markdownHandlesEmptyIssueList() {
        assertThat(exporter.toMarkdown(report(List.of()))).contains("未发现明显风险");
    }

    @Test
    void sarifMapsSeverityToLevelAndDeclaresRulesOnce() throws Exception {
        String sarif = exporter.toSarif(report(List.of(
                issue("HIGH", "security", "src/OrderService.java", 21, 28, 0.9),
                issue("MEDIUM", "security", "src/Other.java", 3, 3, 0.5),
                issue("LOW", "style", "src/App.java", 9, 9, 0.2))));
        JsonNode root = objectMapper.readTree(sarif);

        assertThat(root.get("version").asText()).isEqualTo("2.1.0");
        JsonNode run = root.get("runs").get(0);
        assertThat(run.get("tool").get("driver").get("name").asText()).isEqualTo("RepoSage");
        // security 出现两次但只声明一条 rule
        assertThat(run.get("tool").get("driver").get("rules")).hasSize(2);

        JsonNode results = run.get("results");
        assertThat(results).hasSize(3);
        assertThat(results.get(0).get("level").asText()).isEqualTo("error");
        assertThat(results.get(1).get("level").asText()).isEqualTo("warning");
        assertThat(results.get(2).get("level").asText()).isEqualTo("note");

        JsonNode region = results.get(0).get("locations").get(0).get("physicalLocation").get("region");
        assertThat(region.get("startLine").asInt()).isEqualTo(21);
        assertThat(region.get("endLine").asInt()).isEqualTo(28);
        assertThat(results.get(0).get("message").get("text").asText()).contains("修复建议");
    }

    @Test
    void sarifFallsBackToLineOneWhenLocationMissingOrInvalid() throws Exception {
        JsonNode root = objectMapper.readTree(exporter.toSarif(report(List.of(
                issue("HIGH", "security", "src/OrderService.java", null, null, null),
                issue("HIGH", "security", "src/Other.java", 0, 0, null)))));
        JsonNode results = root.get("runs").get(0).get("results");

        assertThat(results.get(0).get("locations").get(0).get("physicalLocation")
                .get("region").get("startLine").asInt()).isEqualTo(1);
        assertThat(results.get(1).get("locations").get(0).get("physicalLocation")
                .get("region").get("startLine").asInt()).isEqualTo(1);
    }

    @Test
    void issuesWithoutFilePathProduceNoLocations() throws Exception {
        JsonNode root = objectMapper.readTree(exporter.toSarif(report(List.of(
                issue("HIGH", null, null, null, null, null)))));
        JsonNode result = root.get("runs").get(0).get("results").get(0);

        assertThat(result.has("locations")).isFalse();
        assertThat(result.get("ruleId").asText()).isEqualTo("reposage.general");
    }
}
