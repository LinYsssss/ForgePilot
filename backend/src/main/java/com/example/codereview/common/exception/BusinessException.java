package com.example.codereview.common.exception;

public class BusinessException extends RuntimeException {

    private final int httpStatus;
    private final int code;

    public BusinessException(int code, String message) {
        this(resolveHttpStatus(code), code, message);
    }

    public BusinessException(int httpStatus, int code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public int getCode() {
        return code;
    }

    private static int resolveHttpStatus(int code) {
        return code >= 100 && code <= 599 ? code : 400;
    }
}
