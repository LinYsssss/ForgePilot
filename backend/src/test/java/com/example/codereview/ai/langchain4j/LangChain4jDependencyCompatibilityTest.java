package com.example.codereview.ai.langchain4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class LangChain4jDependencyCompatibilityTest {

    private static final String LANGCHAIN4J_VERSION = "1.8.0";
    private static final int JAVA_17_CLASS_MAJOR_VERSION = 61;

    @Test
    void pinsJava17CompatibleCoreAndOpenAiModules() throws Exception {
        assertThat(Runtime.version().feature()).isEqualTo(17);
        assertThat(SpringBootVersion.getVersion()).isEqualTo("3.5.14");
        assertThat(artifactFileName(ChatModel.class))
                .isEqualTo("langchain4j-core-" + LANGCHAIN4J_VERSION + ".jar");
        assertThat(artifactFileName(OpenAiChatModel.class))
                .isEqualTo("langchain4j-open-ai-" + LANGCHAIN4J_VERSION + ".jar");
        assertThat(classMajorVersion(ChatModel.class)).isEqualTo(JAVA_17_CLASS_MAJOR_VERSION);
        assertThat(classMajorVersion(OpenAiChatModel.class)).isEqualTo(JAVA_17_CLASS_MAJOR_VERSION);
    }

    @Test
    void exposesOnlyTheRequiredTaskOneApiSurfaceInsideSpringBoot() {
        new ApplicationContextRunner()
                .withUserConfiguration(CompatibilityConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ChatModel.class);
                    assertThat(context).hasSingleBean(EmbeddingModel.class);
                    assertThat(context).hasSingleBean(ContentRetriever.class);
                    assertThat(context).hasSingleBean(ToolSpecification.class);
                });
    }

    @Test
    void keepsSpringBootJacksonAndSingleLoggingProvider() throws Exception {
        // jackson 2.19 线没有 CVE-2026-54512/54513 的修复版,随 pom 升到 2.21.4(见 jackson-bom.version)。
        assertThat(artifactFileName(ObjectMapper.class)).isEqualTo("jackson-databind-2.21.4.jar");

        List<URL> providers = Collections.list(
                getClass().getClassLoader()
                        .getResources("META-INF/services/org.slf4j.spi.SLF4JServiceProvider")
        );
        assertThat(providers)
                .singleElement()
                .satisfies(provider -> assertThat(provider.toString()).contains("logback-classic"));
    }

    @Test
    void usesJdkHttpClientWithoutTheHighLevelNlpModule() throws Exception {
        assertThat(artifactFileName(JdkHttpClientBuilder.class))
                .isEqualTo("langchain4j-http-client-jdk-" + LANGCHAIN4J_VERSION + ".jar");
        assertThatThrownBy(() -> Class.forName("opennlp.tools.tokenize.TokenizerModel"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    private static String artifactFileName(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                .getFileName()
                .toString();
    }

    private static int classMajorVersion(Class<?> type) throws Exception {
        String resourceName = "/" + type.getName().replace('.', '/') + ".class";
        try (var input = type.getResourceAsStream(resourceName)) {
            assertThat(input).isNotNull();
            byte[] header = input.readNBytes(8);
            assertThat(header).hasSize(8);
            return ((header[6] & 0xff) << 8) | (header[7] & 0xff);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CompatibilityConfiguration {

        @Bean
        ChatModel chatModel() {
            return new ChatModel() {
            };
        }

        @Bean
        EmbeddingModel embeddingModel() {
            return textSegments -> null;
        }

        @Bean
        ContentRetriever contentRetriever() {
            return query -> java.util.List.of();
        }

        @Bean
        ToolSpecification toolSpecification() {
            return ToolSpecification.builder()
                    .name("compatibility.check")
                    .description("Compile-time LangChain4j tool API check")
                    .parameters(JsonObjectSchema.builder()
                            .additionalProperties(false)
                            .build())
                    .build();
        }
    }
}
