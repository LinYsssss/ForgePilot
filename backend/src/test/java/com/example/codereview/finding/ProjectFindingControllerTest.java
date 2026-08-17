package com.example.codereview.finding;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.api.PageResponse;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.exception.GlobalExceptionHandler;
import com.example.codereview.common.security.CurrentUser;
import com.example.codereview.common.security.CurrentUserProvider;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
class ProjectFindingControllerTest {

    @Mock
    private FindingLifecycleService service;
    @Mock
    private CurrentUserProvider currentUserProvider;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(currentUserProvider.getRequired())
                .thenReturn(new CurrentUser(7L, "reviewer", "USER"));
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new ProjectFindingController(service, currentUserProvider))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void listUsesFrozenEnvelopeAndAppendsLifecycleFields() throws Exception {
        when(service.listByProject(1L, 7L, "OPEN", 0, 20))
                .thenReturn(new PageResponse<>(List.of(response()), 0, 20, 1, 1));

        mockMvc.perform(get("/api/projects/{projectId}/findings", 1L)
                        .queryParam("lifecycle", "OPEN")
                        .queryParam("page", "0")
                        .queryParam("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].status").value("verified"))
                .andExpect(jsonPath("$.data.items[0].lifecycle").value("OPEN"))
                .andExpect(jsonPath("$.data.items[0].assigneeId").value(30))
                .andExpect(jsonPath("$.data.items[0].fixCommitSha").value("deadbeef"))
                .andExpect(jsonPath("$.data.items[0].verifiedBy").value(20))
                .andExpect(jsonPath("$.data.items[0].resolutionSuggestion").value("STILL_PRESENT"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void mutationRequestsAreValidated() throws Exception {
        mockMvc.perform(post("/api/projects/{projectId}/findings/{findingId}/lifecycle", 1L, 9L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fixCommitSha\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));

        mockMvc.perform(post("/api/projects/{projectId}/findings/{findingId}/assign", 1L, 9L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void businessErrorsKeep403404409Mappings() throws Exception {
        when(service.transition(1L, 7L, 41L, "CONFIRMED", null))
                .thenThrow(new BusinessException(ErrorCode.PROJECT_FORBIDDEN));
        when(service.transition(1L, 7L, 42L, "CONFIRMED", null))
                .thenThrow(new BusinessException(ErrorCode.FINDING_NOT_FOUND));
        when(service.transition(1L, 7L, 43L, "CONFIRMED", null))
                .thenThrow(new BusinessException(ErrorCode.FINDING_TRANSITION_ILLEGAL));

        assertLifecycleError(41L, 403, "PROJECT_FORBIDDEN");
        assertLifecycleError(42L, 404, "FINDING_NOT_FOUND");
        assertLifecycleError(43L, 409, "FINDING_TRANSITION_ILLEGAL");
    }

    private void assertLifecycleError(Long findingId, int statusCode, String errorCode) throws Exception {
        mockMvc.perform(post("/api/projects/{projectId}/findings/{findingId}/lifecycle", 1L, findingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRMED\"}"))
                .andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.errorCode").value(errorCode));
    }

    private AgentFindingDtos.AgentFindingResponse response() {
        return new AgentFindingDtos.AgentFindingResponse(
                9L, 8L, "HIGH", "security", "title", "description",
                "src/App.java", 10, 12, "App.run", "verified", null,
                Instant.parse("2026-08-17T00:00:00Z"), 0.9, 0.8, true,
                "blocking", "v1", List.of(), "OPEN", 30L, "deadbeef",
                20L, Instant.parse("2026-08-17T01:00:00Z"), "STILL_PRESENT");
    }
}
