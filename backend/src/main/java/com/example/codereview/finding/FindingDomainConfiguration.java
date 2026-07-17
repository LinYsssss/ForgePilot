package com.example.codereview.finding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class FindingDomainConfiguration {

    @Bean
    FindingDeduplicator findingDeduplicator() {
        return new FindingDeduplicator();
    }

    @Bean
    FindingVerifier findingVerifier() {
        return new FindingVerifier();
    }

    @Bean
    FindingConfidenceService findingConfidenceService() {
        return new FindingConfidenceService();
    }

    @Bean
    GateDecisionService gateDecisionService(
            FindingConfidenceService confidence,
            @Value("${app.agent.finding.blocking-threshold:0.70}") double threshold
    ) {
        return new GateDecisionService(confidence, threshold);
    }
}
