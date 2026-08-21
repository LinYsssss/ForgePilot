package com.forgepilot.common;

import org.springframework.http.HttpStatus;

/** A failure a caller is allowed to see, carrying the status and code it maps to. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /**
     * Used for a resource that does not exist <em>and</em> for one the caller may
     * not see. The two must be indistinguishable, otherwise the status code leaks
     * whether another project's resource exists (design.md 5).
     */
    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", "Resource not found.");
    }

    /**
     * The caller may see the resource but not perform this operation. Only ever
     * raised once membership is established, so it reveals nothing new.
     */
    public static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", "This operation is not allowed for your role.");
    }

    /** A request that conflicts with the current state, e.g. a concurrent LEADER transfer. */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "conflict", message);
    }

    /** A well-formed request the domain refuses, e.g. an illegal status transition. */
    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "unprocessable", message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
