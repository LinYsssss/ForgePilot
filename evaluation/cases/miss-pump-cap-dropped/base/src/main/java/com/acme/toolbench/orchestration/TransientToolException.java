package com.acme.toolbench.orchestration;

/** 工具瞬态失败：进程池抖动、管道过早关闭等，可安全重试。 */
public class TransientToolException extends RuntimeException {

    public TransientToolException(String message) {
        super(message);
    }
}
