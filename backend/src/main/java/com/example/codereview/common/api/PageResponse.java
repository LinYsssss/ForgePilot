package com.example.codereview.common.api;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Uniform page envelope for every growing collection endpoint.
 *
 * <p>Frozen in Phase 0 (see {@code docs/archive/并行实施拆分方案.md}) so the frontend can be written against
 * the shape before the backend endpoints are paginated, and neither workstream has to wait on the
 * other.
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    public static <S, T> PageResponse<T> from(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    /** Clamps a client-supplied size into {@code [1, MAX_SIZE]}, defaulting when absent. */
    public static int sanitizeSize(Integer requested) {
        if (requested == null || requested < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(requested, MAX_SIZE);
    }

    /** Clamps a client-supplied page index to a non-negative value. */
    public static int sanitizePage(Integer requested) {
        return requested == null || requested < 0 ? 0 : requested;
    }
}
