package com.example.codereview.finding;

import java.time.Instant;
import java.util.List;

/**
 * Agent Run 的 Finding 对外视图:把持久化的 Finding、证据链与门禁裁决合成一条记录,
 * 供前端展示「严重度 / 置信度 / 是否阻断 / 证据 citation」。
 */
public final class AgentFindingDtos {

    private AgentFindingDtos() {
    }

    public record EvidenceResponse(
            Long evidenceId,
            String evidenceType,
            String sourceVersion,
            String filePath,
            Integer lineStart,
            Integer lineEnd,
            String excerpt,
            Double score,
            String contentHash,
            String reference
    ) {
        public static EvidenceResponse from(FindingEvidenceEntity entity) {
            return new EvidenceResponse(
                    entity.getId(),
                    entity.getEvidenceType() == null ? null : entity.getEvidenceType().name(),
                    entity.getSourceVersion(),
                    entity.getFilePath(),
                    entity.getLineStart(),
                    entity.getLineEnd(),
                    entity.getExcerpt(),
                    entity.getScore(),
                    entity.getContentHash(),
                    buildReference(entity)
            );
        }

        /** 便于前端直接展示的 citation 文本,如 {@code src/OrderService.java:21-28}。 */
        private static String buildReference(FindingEvidenceEntity entity) {
            if (entity.getFilePath() == null || entity.getFilePath().isBlank()) {
                return entity.getSourceVersion();
            }
            StringBuilder ref = new StringBuilder(entity.getFilePath());
            if (entity.getLineStart() != null) {
                ref.append(':').append(entity.getLineStart());
                if (entity.getLineEnd() != null && !entity.getLineEnd().equals(entity.getLineStart())) {
                    ref.append('-').append(entity.getLineEnd());
                }
            }
            return ref.toString();
        }
    }

    public record AgentFindingResponse(
            Long id,
            Long agentRunId,
            String severity,
            String category,
            String title,
            String description,
            String filePath,
            Integer lineStart,
            Integer lineEnd,
            String symbol,
            String status,
            String rejectionReason,
            Instant createdAt,
            Double confidence,
            Double threshold,
            boolean blocking,
            String decisionReason,
            String weightVersion,
            List<EvidenceResponse> evidence
    ) {
        public static AgentFindingResponse from(Finding finding,
                                                List<FindingEvidenceEntity> evidence,
                                                FindingDecisionEntity decision) {
            return new AgentFindingResponse(
                    finding.getId(),
                    finding.getAgentRunId(),
                    finding.getSeverity() == null ? null : finding.getSeverity().name(),
                    finding.getCategory(),
                    finding.getTitle(),
                    finding.getDescription(),
                    finding.getFilePath(),
                    finding.getLineStart(),
                    finding.getLineEnd(),
                    finding.getSymbol(),
                    finding.getStatus(),
                    finding.getRejectionReason(),
                    finding.getCreatedAt(),
                    decision == null ? null : decision.getConfidence(),
                    decision == null ? null : decision.getThreshold(),
                    decision != null && Boolean.TRUE.equals(decision.getBlocking()),
                    decision == null ? null : decision.getReason(),
                    decision == null ? null : decision.getWeightVersion(),
                    evidence.stream().map(EvidenceResponse::from).toList()
            );
        }
    }
}
