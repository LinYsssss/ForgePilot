package com.example.reposage.sandbox;

/**
 * 沙箱作业的终态,backend 同名枚举的镜像。{@link #ENVIRONMENT_INCOMPLETE} 与 {@link #FAILED}
 * 是**两回事**:前者表示依赖/工具链没准备好,因此该结果绝不能被转成代码问题。
 */
public enum SandboxJobStatus {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    REJECTED,
    ENVIRONMENT_INCOMPLETE
}
