package com.example.codereview.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class ProductionMigrationConfigTest {

    @Test
    void productionUsesFlywayAndDisablesSqlInit() throws Exception {
        var source = new YamlPropertySourceLoader()
                .load("prod", new ClassPathResource("application-prod.yml"))
                .get(0);

        assertThat(source.getProperty("spring.flyway.enabled")).isEqualTo(true);
        assertThat(source.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo(true);
        assertThat(source.getProperty("spring.sql.init.mode")).isEqualTo("never");
    }
}
