package com.example.codereview.agent.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.codereview.agent.tool.AgentToolRegistry;
import com.example.codereview.agent.tool.ToolRiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import java.util.List;
import org.junit.jupiter.api.Test;

class LangChainToolSchemaMapperTest {

    private final LangChainToolSchemaMapper mapper = new LangChainToolSchemaMapper(new ObjectMapper());

    @Test
    void mapsProviderNeutralDescriptorsWithoutExecutableFields() {
        AgentToolRegistry.ToolDescriptor descriptor = new AgentToolRegistry.ToolDescriptor(
                "git.diff",
                "Read the bounded pull request diff",
                GitDiffInput.class,
                ToolRiskLevel.READ_ONLY
        );

        ToolSpecification specification = mapper.map(descriptor);

        assertThat(specification.name()).isEqualTo("git.diff");
        assertThat(specification.description()).contains("bounded pull request diff");
        assertThat(specification.parameters().properties()).containsKeys("baseSha", "headSha", "path");
        assertThat(specification.parameters().required()).contains("baseSha", "headSha");
        assertThat(specification.parameters().additionalProperties()).isFalse();
        assertThat(specification.parameters().properties().keySet())
                .doesNotContain("command", "shell", "executablePath");
    }

    @Test
    void mapsOnlyExplicitlyExposedDescriptors() {
        List<ToolSpecification> specifications = mapper.mapAll(List.of(
                new AgentToolRegistry.ToolDescriptor(
                        "git.diff", "Read diff", GitDiffInput.class, ToolRiskLevel.READ_ONLY
                )
        ));

        assertThat(specifications).extracting(ToolSpecification::name)
                .containsExactly("git.diff");
    }

    record GitDiffInput(String baseSha, String headSha, String path) {
    }
}
