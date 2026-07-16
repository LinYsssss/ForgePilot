package com.example.reposage.sandbox;

import org.springframework.stereotype.Component;

/**
 * Safe placeholder used until the container policy is implemented in Phase 3 Task 8. It deliberately
 * refuses to execute repository-controlled code instead of falling back to host command execution.
 */
@Component
final class ScaffoldSandboxExecutor implements SandboxExecutor {

    @Override
    public SandboxResult execute(SandboxJob job) {
        return new SandboxResult(
                job.jobId(),
                SandboxJobStatus.ENVIRONMENT_INCOMPLETE,
                null,
                "",
                false,
                "sandbox container executor is not configured");
    }
}
