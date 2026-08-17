package com.example.codereview.requirement;

import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.requirement.RequirementCheckDtos.CheckDimension;
import com.example.codereview.requirement.RequirementCheckDtos.CheckItem;
import com.example.codereview.requirement.RequirementCheckDtos.DimensionReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 体检 LLM 输出的解析与 schema 校验(P2,R4)。防御姿态与 agent-model-contracts 一致:
 * 未知维度/严重度不是"跳过该条"而是**整体拒绝**——部分采信会让坏输出以残缺报告的形态落库。
 * 六维全量补齐:模型漏掉的维度补空 items,前端渲染不用判缺。
 */
final class RequirementCheckParser {

    private static final Set<String> SEVERITIES = Set.of("HIGH", "MEDIUM", "LOW");

    private RequirementCheckParser() {
    }

    static List<DimensionReport> parse(ObjectMapper objectMapper, String content) {
        JsonNode root;
        try {
            root = objectMapper.readTree(extractJson(content));
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID, "体检输出不是有效 JSON");
        }
        JsonNode dimensions = root.path("dimensions");
        if (!dimensions.isArray()) {
            throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID, "体检输出缺少 dimensions 数组");
        }
        Map<CheckDimension, List<CheckItem>> byDimension = new EnumMap<>(CheckDimension.class);
        for (CheckDimension dimension : CheckDimension.values()) {
            byDimension.put(dimension, new ArrayList<>());
        }
        for (JsonNode node : dimensions) {
            String rawDimension = node.path("dimension").asText("");
            CheckDimension dimension;
            try {
                dimension = CheckDimension.valueOf(rawDimension);
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID, "体检输出包含未知维度: " + rawDimension);
            }
            for (JsonNode item : node.path("items")) {
                String severity = item.path("severity").asText("");
                if (!SEVERITIES.contains(severity)) {
                    throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID, "体检输出包含未知严重度: " + severity);
                }
                String message = item.path("message").asText("").strip();
                if (message.isEmpty()) {
                    throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID, "体检条目缺少 message");
                }
                byDimension.get(dimension).add(new CheckItem(
                        "LLM", severity, message, item.path("suggestion").asText("").strip()));
            }
        }
        List<DimensionReport> reports = new ArrayList<>();
        for (CheckDimension dimension : CheckDimension.values()) {
            reports.add(new DimensionReport(dimension.name(), List.copyOf(byDimension.get(dimension))));
        }
        return List.copyOf(reports);
    }

    private static String extractJson(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID, "体检输出为空");
        }
        String trimmed = content.trim();
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return trimmed;
    }
}
