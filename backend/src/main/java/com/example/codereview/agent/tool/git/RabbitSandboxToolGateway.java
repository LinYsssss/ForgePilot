package com.example.codereview.agent.tool.git;

import com.example.codereview.agent.tool.ToolContext;
import com.example.codereview.agent.tool.ToolResult;
import com.example.codereview.config.RabbitMqConfig;
import com.example.codereview.sandbox.SandboxJob;
import com.example.codereview.sandbox.SandboxJobSigner;
import com.example.codereview.sandbox.SandboxResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public final class RabbitSandboxToolGateway implements SandboxToolGateway {

    private final RabbitTemplate rabbit;
    private final SandboxJobSigner signer;
    private final ObjectMapper mapper;
    private final String signingSecret;
    private final String imageDigest;
    private final long timeoutMs;

    public RabbitSandboxToolGateway(
            RabbitTemplate rabbit,
            SandboxJobSigner signer,
            ObjectMapper mapper,
            @Value("${app.sandbox.signing-secret:}") String signingSecret,
            @Value("${app.sandbox.tool-image:reposage-tools@sha256:abc}") String imageDigest,
            @Value("${app.sandbox.tool-timeout-ms:120000}") long timeoutMs) {
        this.rabbit = rabbit;
        this.signer = signer;
        this.mapper = mapper;
        this.signingSecret = signingSecret;
        this.imageDigest = imageDigest;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public ToolResult<Map<String, Object>> execute(ToolContext context, SandboxToolRequest request) {
        if (signingSecret == null || signingSecret.isBlank() || imageDigest == null || imageDigest.isBlank()) {
            return ToolResult.failure("ENVIRONMENT_INCOMPLETE: sandbox signing secret or image is not configured");
        }
        SandboxJob job = new SandboxJob(
                "tool-" + UUID.randomUUID(),
                request.archiveRef(),
                request instanceof LanguageCommandRequest language
                        ? language.imageDigest() : imageDigest,
                commandId(request),
                commandArgs(request),
                new SandboxJob.Limits(1000, 512, 128, timeoutMs),
                Instant.now().plusMillis(timeoutMs + 30_000).getEpochSecond(),
                UUID.randomUUID().toString());
        SignedSandboxJob message = new SignedSandboxJob(job, signer.sign(job, signingSecret));
        try {
            Message requestMessage = MessageBuilder.withBody(mapper.writeValueAsBytes(message))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .build();
            Message raw = rabbit.sendAndReceive(
                    RabbitMqConfig.SANDBOX_EXCHANGE,
                    RabbitMqConfig.SANDBOX_JOB_ROUTING_KEY,
                    requestMessage);
            if (raw == null || raw.getBody() == null) {
                return ToolResult.failure("ENVIRONMENT_INCOMPLETE: sandbox runner did not respond");
            }
            SandboxResult result = mapper.readValue(raw.getBody(), SandboxResult.class);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", result.status().name());
            data.put("exitCode", result.exitCode());
            data.put("output", result.outputPreview());
            data.put("truncated", result.truncated());
            data.put("message", result.message());
            if (result.status().name().equals("ENVIRONMENT_INCOMPLETE")) {
                return ToolResult.failure("ENVIRONMENT_INCOMPLETE: " + result.message());
            }
            return ToolResult.success(data);
        } catch (Exception ex) {
            return ToolResult.failure("ENVIRONMENT_INCOMPLETE: sandbox request failed");
        }
    }

    private static String commandId(SandboxToolRequest request) {
        if (request instanceof GitDiffRequest) {
            return "git.diff";
        }
        if (request instanceof GitFileRequest) {
            return "git.file";
        }
        if (request instanceof CodeSearchRequest) {
            return "code.search";
        }
        if (request instanceof PatchValidateRequest) {
            return "patch.validate";
        }
        if (request instanceof LanguageCommandRequest value) {
            return value.commandId();
        }
        throw new IllegalArgumentException("unsupported sandbox tool request");
    }

    private static List<String> commandArgs(SandboxToolRequest request) {
        if (request instanceof GitDiffRequest value) {
            return List.of(value.baseRef(), value.headRef(), Integer.toString(value.maxBytes()));
        }
        if (request instanceof GitFileRequest value) {
            return List.of(value.path(), Integer.toString(value.maxBytes()));
        }
        if (request instanceof CodeSearchRequest value) {
            return List.of(value.query(), Integer.toString(value.maxResults()), Integer.toString(value.maxBytes()));
        }
        if (request instanceof PatchValidateRequest value) {
            return List.of(value.boundHeadSha(), value.currentHeadSha(), value.validationCommandId(),
                    value.targetFingerprint());
        }
        if (request instanceof LanguageCommandRequest value) {
            return value.arguments();
        }
        throw new IllegalArgumentException("unsupported sandbox tool request");
    }

    private record SignedSandboxJob(SandboxJob job, String signature) {
    }
}
