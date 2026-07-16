package com.example.reposage.sandbox;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SandboxRunnerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SandboxRunnerApplication.class, args);
    }

    @Bean
    Clock sandboxClock() {
        return Clock.systemUTC();
    }

    @Bean
    SandboxJobSigner sandboxJobSigner() {
        return new SandboxJobSigner();
    }

    @Bean
    SandboxReplayGuard sandboxReplayGuard() {
        return new SandboxReplayGuard();
    }
}
