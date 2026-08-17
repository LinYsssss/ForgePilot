package com.example.codereview.review;

import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.review.CoverageDtos.AcCoverage;
import com.example.codereview.review.CoverageDtos.AcRef;
import com.example.codereview.review.CoverageDtos.CoverageEvidence;
import com.example.codereview.review.CoverageDtos.CoverageInput;
import com.example.codereview.review.CoverageDtos.CoverageVerdict;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * coverage 输出的解析与校验(P4a,R4)。姿态与体检解析一致:未知 acId/verdict 整体拒绝;
 * AC 全量补齐——模型漏判的 AC 记 NOT_FOUND 并标注,报告端永远拿到与 AC 列表等长的结论。
 */
final class CoverageJudgeParser {

    private CoverageJudgeParser() {
    }

    static List<AcCoverage> parse(ObjectMapper objectMapper, CoverageInput input, String content) {
        JsonNode root;
        try {
            root = objectMapper.readTree(extractJson(content));
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID, "覆盖判定输出不是有效 JSON");
        }
        JsonNode coverage = root.path("coverage");
        if (!coverage.isArray()) {
            throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID, "覆盖判定输出缺少 coverage 数组");
        }
        Map<String, String> acTexts = new HashMap<>();
        for (AcRef ac : input.acs()) {
            acTexts.put(ac.acId(), ac.text());
        }
        Map<String, AcCoverage> byAcId = new HashMap<>();
        for (JsonNode node : coverage) {
            String acId = node.path("acId").asText("");
            if (!acTexts.containsKey(acId)) {
                throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID, "覆盖判定包含未知 acId: " + acId);
            }
            String verdictRaw = node.path("verdict").asText("");
            CoverageVerdict verdict;
            try {
                verdict = CoverageVerdict.valueOf(verdictRaw);
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID, "覆盖判定包含未知 verdict: " + verdictRaw);
            }
            List<CoverageEvidence> evidence = new ArrayList<>();
            for (JsonNode ev : node.path("evidence")) {
                String filePath = ev.path("filePath").asText("").strip();
                if (filePath.isEmpty()) {
                    continue;
                }
                evidence.add(new CoverageEvidence(
                        filePath,
                        ev.path("lineStart").isNumber() ? ev.path("lineStart").asInt() : null,
                        ev.path("lineEnd").isNumber() ? ev.path("lineEnd").asInt() : null,
                        ev.path("note").asText("").strip()));
            }
            byAcId.put(acId, new AcCoverage(acId, acTexts.get(acId), verdict.name(),
                    List.copyOf(evidence), node.path("rationale").asText("").strip()));
        }
        // AC 全量补齐:漏判 = NOT_FOUND + 标注(缺席不等于覆盖)。
        List<AcCoverage> result = new ArrayList<>();
        for (AcRef ac : input.acs()) {
            AcCoverage judged = byAcId.get(ac.acId());
            result.add(judged != null ? judged : new AcCoverage(
                    ac.acId(), ac.text(), CoverageVerdict.NOT_FOUND.name(), List.of(),
                    "模型未对该 AC 给出结论，按未发现处理"));
        }
        return List.copyOf(result);
    }

    /** 证据引用校验:filePath 必须出现在 diff 的文件集合里;COVERED 失去全部证据 → 降级 AT_RISK。 */
    static List<AcCoverage> verifyEvidence(List<AcCoverage> coverage, String diffText) {
        String diff = diffText == null ? "" : diffText;
        List<AcCoverage> verified = new ArrayList<>();
        for (AcCoverage ac : coverage) {
            List<CoverageEvidence> kept = new ArrayList<>();
            int dropped = 0;
            for (CoverageEvidence ev : ac.evidence()) {
                if (diff.contains(" b/" + ev.filePath()) || diff.contains("+++ b/" + ev.filePath())) {
                    kept.add(ev);
                } else {
                    dropped++;
                }
            }
            String verdict = ac.verdict();
            String rationale = ac.rationale();
            if (dropped > 0 && kept.isEmpty() && CoverageVerdict.COVERED.name().equals(verdict)) {
                verdict = CoverageVerdict.AT_RISK.name();
                rationale = (rationale == null || rationale.isBlank() ? "" : rationale + " ")
                        + "[证据引用未通过校验（引用的文件不在本次 diff 中），结论由 COVERED 降级]";
            } else if (dropped > 0) {
                rationale = (rationale == null || rationale.isBlank() ? "" : rationale + " ")
                        + "[" + dropped + " 条证据引用未通过校验已丢弃]";
            }
            verified.add(new AcCoverage(ac.acId(), ac.acText(), verdict, List.copyOf(kept), rationale));
        }
        return List.copyOf(verified);
    }

    private static String extractJson(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID, "覆盖判定输出为空");
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
