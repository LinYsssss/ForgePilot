package com.example.reposage.sandbox;

public record DockerWaitResult(Integer exitCode, String output, boolean truncated, boolean timedOut) {

    public DockerWaitResult {
        output = output == null ? "" : output;
    }
}
