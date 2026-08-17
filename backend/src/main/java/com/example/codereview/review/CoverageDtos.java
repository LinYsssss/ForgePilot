package com.example.codereview.review;

import java.util.List;

/** AC 覆盖判定的结构(P4a,父 design §7)。落库 JSON 与 REST 响应共用同一结构。 */
public final class CoverageDtos {

    private CoverageDtos() {
    }

    /** 三态结论;未知值在解析层整体拒绝。 */
    public enum CoverageVerdict {
        COVERED,
        NOT_FOUND,
        AT_RISK
    }

    public record CoverageEvidence(String filePath, Integer lineStart, Integer lineEnd, String note) {
    }

    public record AcCoverage(String acId, String acText, String verdict,
                             List<CoverageEvidence> evidence, String rationale) {
    }

    /** 判定输入的需求侧快照(来自 requirement 域,判定服务组装)。 */
    public record CoverageInput(Long requirementId, String requirementCode, String requirementTitle,
                                String requirementDescription, List<AcRef> acs) {
    }

    public record AcRef(String acId, String text) {
    }

    /** LLM 层产物:结论 + 原始响应 + token(mock=0)。 */
    public record CoverageResult(List<AcCoverage> coverage, String rawResponse, int totalTokens) {
    }
}
