package com.example.codereview.ai.langchain4j;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("dev")
@SpringBootTest(properties = {
        "app.ai.runtime=langchain4j",
        "app.ai.provider=mock",
        "app.ai.embedding-provider=mock",
        "app.agent.recovery.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
class LangChain4jMockApplicationContextTest {

    @Autowired
    private LangChain4jRuntime runtime;

    @Test
    void startsTheFullApplicationWithDeterministicLangChain4jMockRuntime() {
        assertThat(runtime.mode()).isEqualTo(LangChain4jRuntime.Mode.LANGCHAIN4J);
    }
}
