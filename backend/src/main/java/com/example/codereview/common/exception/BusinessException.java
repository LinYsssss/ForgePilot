package com.example.codereview.common.exception;

import com.example.codereview.common.api.ErrorCode;

public class BusinessException extends RuntimeException {

    private final int httpStatus;
    private final int code;
    private final ErrorCode errorCode;

    /**
     * 首选构造器。带上稳定的 {@link ErrorCode},让响应能暴露一个客户端可据以分支的标识,
     * 同时把 HTTP 状态的决定权收在一处。
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
     * 遗留构造器,留着是为了让存量调用点在逐条迁移期间仍能编译。字符串码是**推导**出来的,
     * 不是随手编的。
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
