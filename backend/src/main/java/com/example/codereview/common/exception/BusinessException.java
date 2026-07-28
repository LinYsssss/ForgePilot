package com.example.codereview.common.exception;

import com.example.codereview.common.api.ErrorCode;

public class BusinessException extends RuntimeException {

    private final int httpStatus;
    private final int code;
    private final ErrorCode errorCode;

    /**
     * Preferred constructor. Carries a stable {@link ErrorCode} so the response can expose an
     * identifier clients may branch on, and so the HTTP status is decided in one place.
     */
    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = errorCode.httpStatus();
        this.code = errorCode.legacyCode();
    }

    /**
     * Legacy constructor kept so the existing call sites keep compiling while they are migrated
     * track by track. The string code is derived rather than invented.
     */
    public BusinessException(int code, String message) {
        this(resolveHttpStatus(code), code, message);
    }

    public BusinessException(int httpStatus, int code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
        this.errorCode = ErrorCode.fromLegacy(code);
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public int getCode() {
        return code;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    private static int resolveHttpStatus(int code) {
        return code >= 100 && code <= 599 ? code : 400;
    }
}
