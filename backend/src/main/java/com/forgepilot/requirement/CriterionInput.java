package com.forgepilot.requirement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One acceptance criterion as the caller sends it. A null {@code acKey} means
 * "new criterion"; a given one must already belong to this requirement and is
 * kept unchanged, because it is the stable cross-revision identity (D011).
 */
public record CriterionInput(@Size(max = 64) String acKey, @NotBlank String text) {
}
