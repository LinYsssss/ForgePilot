package com.example.codereview.agent.tool;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolInvocationRepository extends JpaRepository<ToolInvocation, Long> {
    Optional<ToolInvocation> findByInvocationKey(String invocationKey);
}
