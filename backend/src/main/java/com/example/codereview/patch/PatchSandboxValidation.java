package com.example.codereview.patch;

public record PatchSandboxValidation(boolean applyChecked, boolean applySucceeded,
                                     boolean commandSucceeded, boolean targetDisappeared,
                                     String resultJson, String boundedLog) {
    public PatchSandboxValidation {
        resultJson = resultJson == null ? "{}" : resultJson;
        boundedLog = boundedLog == null ? "" : boundedLog;
    }
}
