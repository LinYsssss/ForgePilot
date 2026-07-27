package com.example.codereview.review;

import com.example.codereview.review.ReviewDtos.ReviewIssueResponse;
import com.example.codereview.review.ReviewDtos.ReviewReportDetail;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 把审查报告导出为可携带的格式:Markdown 供人阅读/传阅,SARIF 2.1.0 供 CI 消费
 * (可直接上传到 GitHub Code Scanning)。纯格式化,不做任何权限判断——调用方先用
 * {@link ReviewService#reportDetail} 完成归属校验。
 */
@Component
public class ReviewReportExporter {

    private static final String SARIF_SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json";
    private static final String TOOL_NAME = "RepoSage";

    private final ObjectMapper objectMapper;

    public ReviewReportExporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toMarkdown(ReviewReportDetail report) {
        StringBuilder md = new StringBuilder();
        md.append("# 审查报告 #").append(report.reportId()).append('\n').append('\n');
        md.append("| 项 | 值 |\n| --- | --- |\n");
        md.append("| 总体风险 | ").append(nullToDash(report.overallRisk())).append(" |\n");
        md.append("| 问题数 | ").append(report.issueCount()).append(" |\n");
        md.append("| Commit | `").append(nullToDash(report.commitId())).append("` |\n");
        md.append("| 任务 | ").append(report.taskId() == null ? "-" : report.taskId()).append(" |\n");
        md.append("| 生成时间 | ").append(report.createdAt() == null ? "-" : report.createdAt()).append(" |\n\n");

        if (report.summary() != null && !report.summary().isBlank()) {
            md.append("## 摘要\n\n").append(report.summary()).append("\n\n");
        }

        Map<String, Integer> tally = severityTally(report.issues());
        if (!tally.isEmpty()) {
            md.append("## 严重度分布\n\n");
            tally.forEach((severity, count) -> md.append("- **").append(severity).append("**: ").append(count).append('\n'));
            md.append('\n');
        }

        md.append("## 问题清单\n\n");
        if (report.issues() == null || report.issues().isEmpty()) {
            md.append("未发现明显风险。\n");
            return md.toString();
        }
        int index = 1;
        for (ReviewIssueResponse issue : report.issues()) {
            md.append("### ").append(index++).append(". [").append(nullToDash(issue.severity())).append("] ")
                    .append(nullToDash(issue.title())).append("\n\n");
            if (issue.filePath() != null && !issue.filePath().isBlank()) {
                md.append("- **位置**: `").append(issue.filePath()).append(location(issue)).append("`\n");
            }
            if (issue.category() != null && !issue.category().isBlank()) {
                md.append("- **类别**: ").append(issue.category()).append('\n');
            }
            if (issue.confidence() != null) {
                md.append("- **置信度**: ").append(Math.round(issue.confidence() * 100)).append("%\n");
            }
            md.append('\n');
            appendSection(md, "问题描述", issue.description());
            appendSection(md, "影响", issue.impact());
            appendSection(md, "证据", issue.evidence());
            appendSection(md, "修复建议", issue.suggestion());
        }
        return md.toString();
    }

    public String toSarif(ReviewReportDetail report) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("$schema", SARIF_SCHEMA);
        root.put("version", "2.1.0");

        ArrayNode runs = root.putArray("runs");
        ObjectNode run = runs.addObject();
        ObjectNode driver = run.putObject("tool").putObject("driver");
        driver.put("name", TOOL_NAME);
        driver.put("informationUri", "https://github.com/LinYsssss/reposage");

        List<ReviewIssueResponse> issues = report.issues() == null ? List.of() : report.issues();
        // SARIF 要求规则先声明再引用:按类别去重成 rule,results 用 ruleId 指回。
        ArrayNode rules = driver.putArray("rules");
        Map<String, Integer> ruleIndex = new LinkedHashMap<>();
        for (ReviewIssueResponse issue : issues) {
            String ruleId = ruleId(issue);
            if (ruleIndex.containsKey(ruleId)) {
                continue;
            }
            ruleIndex.put(ruleId, ruleIndex.size());
            ObjectNode rule = rules.addObject();
            rule.put("id", ruleId);
            rule.putObject("shortDescription").put("text", nullToDash(issue.category()));
        }

        ArrayNode results = run.putArray("results");
        for (ReviewIssueResponse issue : issues) {
            ObjectNode result = results.addObject();
            result.put("ruleId", ruleId(issue));
            result.put("ruleIndex", ruleIndex.get(ruleId(issue)));
            result.put("level", sarifLevel(issue.severity()));
            result.putObject("message").put("text", message(issue));
            if (issue.filePath() != null && !issue.filePath().isBlank()) {
                ObjectNode physical = result.putArray("locations").addObject().putObject("physicalLocation");
                physical.putObject("artifactLocation").put("uri", issue.filePath());
                ObjectNode region = physical.putObject("region");
                // SARIF 行号从 1 开始,缺失或非法时退回 1,避免消费方拒绝整份报告。
                region.put("startLine", issue.lineStart() == null || issue.lineStart() < 1 ? 1 : issue.lineStart());
                if (issue.lineEnd() != null && issue.lineStart() != null && issue.lineEnd() >= issue.lineStart()) {
                    region.put("endLine", issue.lineEnd());
                }
            }
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("导出 SARIF 失败", ex);
        }
    }

    private static String ruleId(ReviewIssueResponse issue) {
        String category = issue.category();
        return category == null || category.isBlank() ? "reposage.general" : category;
    }

    private static String message(ReviewIssueResponse issue) {
        StringBuilder text = new StringBuilder(nullToDash(issue.title()));
        if (issue.description() != null && !issue.description().isBlank()) {
            text.append("\n\n").append(issue.description());
        }
        if (issue.suggestion() != null && !issue.suggestion().isBlank()) {
            text.append("\n\n修复建议: ").append(issue.suggestion());
        }
        return text.toString();
    }

    private static String sarifLevel(String severity) {
        if (severity == null) {
            return "none";
        }
        return switch (severity.toUpperCase()) {
            case "HIGH", "CRITICAL", "BLOCKER" -> "error";
            case "MEDIUM" -> "warning";
            case "LOW" -> "note";
            default -> "none";
        };
    }

    private static Map<String, Integer> severityTally(List<ReviewIssueResponse> issues) {
        Map<String, Integer> tally = new LinkedHashMap<>();
        if (issues == null) {
            return tally;
        }
        for (String severity : List.of("HIGH", "MEDIUM", "LOW")) {
            int count = (int) issues.stream().filter(i -> severity.equalsIgnoreCase(i.severity())).count();
            if (count > 0) {
                tally.put(severity, count);
            }
        }
        return tally;
    }

    private static String location(ReviewIssueResponse issue) {
        if (issue.lineStart() == null) {
            return "";
        }
        if (issue.lineEnd() != null && !issue.lineEnd().equals(issue.lineStart())) {
            return ":" + issue.lineStart() + "-" + issue.lineEnd();
        }
        return ":" + issue.lineStart();
    }

    private static void appendSection(StringBuilder md, String title, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        md.append("**").append(title).append("**\n\n").append(body).append("\n\n");
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
