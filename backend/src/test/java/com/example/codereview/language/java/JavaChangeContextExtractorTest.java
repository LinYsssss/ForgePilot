package com.example.codereview.language.java;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class JavaChangeContextExtractorTest {

    @Test
    void extractsClassesMethodsAnnotationsAndCallsFromChangedSources() {
        String source = """
                package com.acme;

                @Service
                public class OrderService {
                    @Transactional
                    public Order submit(Order order) {
                        validator.validate(order);
                        return repository.save(order);
                    }
                }
                """;

        JavaSymbolContext context = new JavaChangeContextExtractor().extract(
                Map.of("src/main/java/com/acme/OrderService.java", source));

        assertThat(context.classes()).containsExactly("com.acme.OrderService");
        assertThat(context.methods()).containsExactly("com.acme.OrderService#submit(Order)");
        assertThat(context.annotations()).containsExactlyInAnyOrder("Service", "Transactional");
        assertThat(context.calls()).containsExactlyInAnyOrder("validator.validate", "repository.save");
    }

    @Test
    void reportsParseFailuresAsEnvironmentResultsInsteadOfDefects() {
        JavaSymbolContext context = new JavaChangeContextExtractor().extract(
                Map.of("src/Broken.java", "class Broken { void x( }"));

        assertThat(context.classes()).isEmpty();
        assertThat(context.parseErrors()).singleElement().asString().contains("src/Broken.java");
    }
}
