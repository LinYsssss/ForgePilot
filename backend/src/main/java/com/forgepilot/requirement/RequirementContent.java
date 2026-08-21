package com.forgepilot.requirement;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * The prose and acceptance criteria of one revision, as the caller sends them.
 * Used both when a requirement is created and whenever its content changes;
 * {@code sortOrder} is derived from the array position and is never accepted
 * from the client (api-contract 3).
 */
public record RequirementContent(
        @NotBlank @Size(max = 200) String title,
        String background,
        String description,
        @NotEmpty @Valid List<CriterionInput> acceptanceCriteria) {
}
