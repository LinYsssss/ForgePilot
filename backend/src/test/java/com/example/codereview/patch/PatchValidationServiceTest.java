package com.example.codereview.patch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.tool.ToolContext;
import com.example.codereview.agent.tool.ToolResult;
import com.example.codereview.agent.tool.git.SandboxToolGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PatchValidationServiceTest {
    @Mock PatchCandidateRepository patches;
    @Mock SandboxToolGateway gateway;

    @Test
    void persistsStructuredSandboxDeltaAndIndependentKindStatus() {
        PatchCandidate candidate = new PatchCandidate(7L, "head", Set.of(2L), "model", "prompt", "diff",
                new PatchValidation(true, null, List.of("src/App.java"), 2));
        when(patches.findById(9L)).thenReturn(Optional.of(candidate));
        when(patches.save(any(PatchCandidate.class))).thenAnswer(invocation -> invocation.getArgument(0));
        String output = "{\"applySucceeded\":true,\"buildOrTestPassed\":true,\"targetDisappeared\":true,"
                + "\"baselineLog\":\"fingerprint\",\"patchedLog\":\"clean\"}";
        when(gateway.execute(any(), any())).thenReturn(ToolResult.success(Map.of(
                "status", "SUCCEEDED", "output", output, "truncated", false)));
        PatchValidationService service = new PatchValidationService(patches, gateway, new ObjectMapper());

        PatchCandidate result = service.validate(9L, "head", "repo.zip", "java.maven.test", "fingerprint",
                PatchValidationKind.TEST, new ToolContext(7L, 8L, "patch-9-test", false, "trace"));

        assertThat(result.getApplyStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.getTestStatus()).isEqualTo("PASSED");
        assertThat(result.isApprovable()).isTrue();
        assertThat(result.getValidationResultJson()).contains("baselineLog", "patchedLog");
    }
}
