package com.example.reposage.sandbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "app.sandbox.signing-secret=test-signing-secret"
})
class SandboxRunnerApplicationTest {

    @Test
    void contextLoads() {
    }
}
