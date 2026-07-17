package com.example.codereview.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KnowledgeChunkingTest {

    @Test
    void prefersMarkdownHeadingAndCodeBoundaries() {
        String text = "# Security\n\nPolicy paragraph.\n\n```java\nclass AuthService {}\n```\n\n## Operations\n\nRunbook.";

        assertThat(KnowledgeService.splitForIndexing(text, 45, 5))
                .containsExactly(
                        "# Security\n\nPolicy paragraph.",
                        "```java\nclass AuthService {}\n```",
                        "## Operations\n\nRunbook.");
    }
}
