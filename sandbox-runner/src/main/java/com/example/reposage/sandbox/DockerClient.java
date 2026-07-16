package com.example.reposage.sandbox;

public interface DockerClient {

    String create(ContainerLaunchSpec spec);

    void start(String containerId);

    DockerWaitResult waitFor(String containerId, long timeoutMs);

    void kill(String containerId);

    void remove(String containerId);
}
