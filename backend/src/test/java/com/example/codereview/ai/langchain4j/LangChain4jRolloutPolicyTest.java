package com.example.codereview.ai.langchain4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class LangChain4jRolloutPolicyTest {
    @Test void shadowEnablesEvaluationButNeverWrites() {
        var policy = new LangChain4jRolloutPolicy("shadow", "acme/repo");
        assertThat(policy.enabledFor("anything")).isTrue();
        assertThat(policy.shadow()).isTrue();
        assertThat(policy.allowsScmWrites()).isFalse();
    }
    @Test void selectedProjectsAreExplicit() {
        var policy = new LangChain4jRolloutPolicy("selected-projects", "acme/a, acme/b");
        assertThat(policy.enabledFor("acme/a")).isTrue();
        assertThat(policy.enabledFor("acme/c")).isFalse();
        assertThat(policy.allowsScmWrites()).isTrue();
    }
    @Test void unknownStageRejected() {
        assertThatThrownBy(() -> new LangChain4jRolloutPolicy("maybe", ""))
                .isInstanceOf(IllegalStateException.class);
    }
}
