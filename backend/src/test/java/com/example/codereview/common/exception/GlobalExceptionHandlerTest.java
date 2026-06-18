package com.example.codereview.common.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
        classes = GlobalExceptionHandlerTest.TestApplication.class,
        properties = {
                "app.security.token-secret=test-secret",
                "app.security.token-encrypt-key=test-encrypt-key",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "management.health.rabbit.enabled=false"
        }
)
@AutoConfigureMockMvc(addFilters = false)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void businessExceptionRespectsHttpStatus() throws Exception {
        mockMvc.perform(get("/test/status"))
                .andExpect(status().isNotFound());
    }

    @EnableAutoConfiguration
    @Import({GlobalExceptionHandler.class, TestController.class})
    static class TestApplication {
    }

    @RestController
    static class TestController {

        @GetMapping("/test/status")
        public void testStatus() {
            throw new BusinessException(404, 404, "not found");
        }
    }
}
