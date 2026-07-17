package com.example.codereview.agent.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AgentModelCallAuditServiceTest {

    @Test
    void persistsModelAuditInAnIndependentTransaction() throws Exception {
        Method save = AgentModelCallAuditService.class.getMethod("save", AgentModelCall.class);

        Transactional transactional = save.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
