package com.example.codereview.requirement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class RequirementDtos {

    private RequirementDtos() {
    }

    public record AcItem(
            @NotBlank @Size(max = 2000) String text
    ) {
    }

    public record SaveRequirementRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 20000) String background,
            @Size(max = 20000) String description,
            @Size(max = 16) String priority,
            @Valid @Size(max = 50) List<AcItem> acceptanceCriteria
    ) {
    }

    public record AssignRequest(
            @NotNull Long userId
    ) {
    }

    public record StatusRequest(
            @NotBlank @Size(max = 32) String status
    ) {
    }

    public record AcResponse(Long acId, Integer seq, String text) {
        static AcResponse from(AcceptanceCriterionEntity entity) {
            return new AcResponse(entity.getId(), entity.getSeq(), entity.getText());
        }
    }

    public record RequirementSummary(
            Long requirementId,
            String code,
            String title,
            String priority,
            String status,
            Long assigneeId,
            String assigneeName,
            int acCount,
            Instant updatedAt
    ) {
    }

    public record RequirementDetail(
            Long requirementId,
            String code,
            String title,
            String background,
            String description,
            String priority,
            String status,
            Long assigneeId,
            String assigneeName,
            Long createdBy,
            Instant createdAt,
            Instant updatedAt,
            List<AcResponse> acceptanceCriteria
    ) {
    }
}
