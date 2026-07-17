package com.example.codereview.ai.langchain4j;

import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class LangChain4jRuntime {

    private final Mode mode;

    public LangChain4jRuntime(@Value("${app.ai.runtime:legacy}") String configuredRuntime) {
        this.mode = Mode.parse(configuredRuntime);
    }

    public Mode mode() {
        return mode;
    }

    public boolean isLangChain4j() {
        return mode == Mode.LANGCHAIN4J;
    }

    public enum Mode {
        LEGACY,
        LANGCHAIN4J;

        static Mode parse(String configuredRuntime) {
            String normalized = configuredRuntime == null
                    ? ""
                    : configuredRuntime.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "legacy" -> LEGACY;
                case "langchain4j" -> LANGCHAIN4J;
                default -> throw new IllegalStateException(
                        "Unsupported app.ai.runtime; expected legacy or langchain4j"
                );
            };
        }
    }
}
