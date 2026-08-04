package com.example.reposage.sandbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "app.sandbox.signing-secret=test-signing-secret",
        // CI runner 非 root,默认 /app/archives 会因 AccessDeniedException 拉不起上下文(F-02)
        "app.sandbox.archive-root=${java.io.tmpdir}/reposage-test-archives"
})
class SandboxRunnerApplicationTest {

    @Test
    void contextLoads() {
    }
}
