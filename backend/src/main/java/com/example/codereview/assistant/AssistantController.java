package com.example.codereview.assistant;

import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.common.security.CurrentUserProvider;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class AssistantController {

    private final AssistantService assistantService;
    private final CurrentUserProvider currentUserProvider;

    public AssistantController(AssistantService assistantService, CurrentUserProvider currentUserProvider) {
        this.assistantService = assistantService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/assistant/config")
    public ApiResponse<AssistantDtos.ConfigResponse> config() {
        currentUserProvider.getRequired();
        return ApiResponse.ok(new AssistantDtos.ConfigResponse(assistantService.enabled()));
    }

    @PostMapping(value = "/projects/{projectId}/requirements/{requirementId}/assistant/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter stream(@PathVariable Long projectId, @PathVariable Long requirementId,
                             @Valid @RequestBody AssistantDtos.StreamRequest request,
                             HttpServletResponse response) {
        SseEmitter emitter = assistantService.stream(projectId, requirementId,
                currentUserProvider.getRequired().userId(), request);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8");
        return emitter;
    }
}
