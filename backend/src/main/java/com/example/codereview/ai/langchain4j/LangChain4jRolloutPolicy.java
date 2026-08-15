package com.example.codereview.ai.langchain4j;

import java.util.Locale;
import java.util.Set;

/** 确定性的灰度放量门;这里的标识符是配置值,绝不用作指标标签(否则会撑爆时间序列基数)。 */
@org.springframework.stereotype.Component
public final class LangChain4jRolloutPolicy {
    public enum Stage { DISABLED, SHADOW, SELECTED_PROJECTS, DEFAULT }

    private final Stage stage;
    private final Set<String> selectedProjects;

    public LangChain4jRolloutPolicy(
            @org.springframework.beans.factory.annotation.Value("${app.ai.rollout:default}") String configuredStage,
            @org.springframework.beans.factory.annotation.Value("${app.ai.selected-projects:}") String configuredProjects) {
        this.stage = parse(configuredStage);
        this.selectedProjects = Set.of(configuredProjects == null ? "" : configuredProjects)
                .stream().flatMap(value -> java.util.Arrays.stream(value.split(",")))
                .map(String::trim).filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Stage stage() { return stage; }
    public boolean enabledFor(String projectKey) {
        return stage == Stage.DEFAULT || stage == Stage.SHADOW
                || (stage == Stage.SELECTED_PROJECTS && selectedProjects.contains(projectKey));
    }
    public boolean allowsScmWrites() { return stage == Stage.DEFAULT || stage == Stage.SELECTED_PROJECTS; }
    public boolean shadow() { return stage == Stage.SHADOW; }

    private static Stage parse(String value) {
        String normalized = value == null ? "default" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "disabled" -> Stage.DISABLED;
            case "shadow" -> Stage.SHADOW;
            case "selected", "selected-projects" -> Stage.SELECTED_PROJECTS;
            case "default" -> Stage.DEFAULT;
            default -> throw new IllegalStateException("Unsupported app.ai.rollout; expected disabled, shadow, selected-projects or default");
        };
    }
}
