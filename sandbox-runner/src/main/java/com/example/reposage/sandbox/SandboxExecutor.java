package com.example.reposage.sandbox;

@FunctionalInterface
public interface SandboxExecutor {

    SandboxResult execute(SandboxJob job);
}
