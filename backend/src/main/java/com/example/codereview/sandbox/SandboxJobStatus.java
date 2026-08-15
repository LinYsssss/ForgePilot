package com.example.codereview.sandbox;

/**
 * 沙箱作业的终态。{@link #ENVIRONMENT_INCOMPLETE} 与 {@link #FAILED} 是**两回事**:
 * 前者表示依赖/工具链没准备好,因此该结果绝不能被转成代码问题(见依赖准备环节)。
 */
public enum SandboxJobStatus {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    REJECTED,
    ENVIRONMENT_INCOMPLETE
}
