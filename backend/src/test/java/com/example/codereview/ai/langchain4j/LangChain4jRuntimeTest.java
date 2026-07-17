package com.example.codereview.ai.langchain4j;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class LangChain4jRuntimeTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RuntimeConfiguration.class);

    @Test
    void startsWithLegacyRuntime() {
        contextRunner
                .withPropertyValues("app.ai.runtime=legacy")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LangChain4jRuntime.class).mode())
                            .isEqualTo(LangChain4jRuntime.Mode.LEGACY);
                });
    }

    @Test
    void startsWithDeterministicLangChain4jMockOutsideProduction() {
        contextRunner
                .withPropertyValues(
                        "app.ai.runtime=langchain4j",
                        "app.ai.provider=mock",
                        "app.ai.embedding-provider=mock"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LangChain4jRuntime.class).mode())
                            .isEqualTo(LangChain4jRuntime.Mode.LANGCHAIN4J);
                });
    }

    @Test
    void rejectsUnknownRuntimeWithoutEchoingUntrustedValue() {
        String untrusted = "secret-invalid-runtime-value";
        contextRunner
                .withPropertyValues("app.ai.runtime=" + untrusted)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure()))
                            .hasMessageContaining("app.ai.runtime")
                            .hasMessageNotContaining(untrusted);
                });
    }

    @Test
    void productionLangChain4jRuntimeRequiresRealChatConfiguration() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "app.ai.runtime=langchain4j",
                        "app.ai.provider=openai-compatible",
                        "app.ai.base-url=",
                        "app.ai.api-key=do-not-echo-this-secret",
                        "app.ai.chat-model="
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure()))
                            .hasMessageContaining("app.ai.base-url")
                            .hasMessageContaining("app.ai.chat-model")
                            .hasMessageNotContaining("do-not-echo-this-secret");
                });
    }

    @Test
    void productionLangChain4jRuntimeRejectsMockChatProvider() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "app.ai.runtime=langchain4j",
                        "app.ai.provider=mock"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure()))
                            .hasMessageContaining("app.ai.provider=openai-compatible");
                });
    }

    @Test
    void productionLangChain4jRuntimeStartsWithRealChatAndExplicitMockEmbedding() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "app.ai.runtime=langchain4j",
                        "app.ai.provider=openai-compatible",
                        "app.ai.base-url=https://models.example.test/v1",
                        "app.ai.api-key=test-secret-value",
                        "app.ai.chat-model=review-model",
                        "app.ai.embedding-provider=mock"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void productionOpenAiEmbeddingRequiresItsOwnConfiguration() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "app.ai.runtime=langchain4j",
                        "app.ai.provider=openai-compatible",
                        "app.ai.base-url=https://models.example.test/v1",
                        "app.ai.api-key=test-secret-value",
                        "app.ai.chat-model=review-model",
                        "app.ai.embedding-provider=openai-compatible",
                        "app.ai.embedding-base-url=",
                        "app.ai.embedding-api-key=",
                        "app.ai.embedding-model=mock-embedding"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure()))
                            .hasMessageContaining("app.ai.embedding-base-url")
                            .hasMessageContaining("app.ai.embedding-api-key")
                            .hasMessageContaining("app.ai.embedding-model")
                            .hasMessageNotContaining("test-secret-value");
                });
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable result = failure;
        while (result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    @Configuration(proxyBeanMethods = false)
    @Import({LangChain4jRuntime.class, LangChain4jRuntimeValidator.class})
    static class RuntimeConfiguration {
    }
}
