package com.example.codereview.agent.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public final class PromptTemplateRegistry {

    private static final Map<String, String> RESOURCES = Map.of(
            "review-v1", "prompts/agent/review-v1.txt"
    );

    public String require(String version) {
        String path = RESOURCES.get(version);
        if (path == null) {
            throw new IllegalArgumentException("unknown prompt version");
        }
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8).strip();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load prompt template " + version, ex);
        }
    }
}
