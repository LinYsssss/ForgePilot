package com.example.codereview.agent.tool.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.codereview.agent.tool.ToolContext;
import com.example.codereview.agent.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GitToolsTest {

    private final RecordingGateway gateway = new RecordingGateway();
    private final ToolContext context = new ToolContext(1L, 2L, "invocation-1", false, "trace-1");

    @Test
    void registersReadOnlyToolsAndDispatchesTypedRequestsWithoutRawCommands() {
        GitDiffTool diff = new GitDiffTool(gateway);
        GitFileTool file = new GitFileTool(gateway);
        CodeSearchTool search = new CodeSearchTool(gateway);

        assertThat(diff.name()).isEqualTo("git.diff");
        assertThat(file.name()).isEqualTo("git.file");
        assertThat(search.name()).isEqualTo("code.search");
        assertThat(diff.riskLevel()).isEqualTo(com.example.codereview.agent.tool.ToolRiskLevel.READ_ONLY);

        diff.execute(context, new GitDiffRequest("archive-1", "main", "feature", 1000));
        file.execute(context, new GitFileRequest("archive-1", "src/App.java", 1000));
        search.execute(context, new CodeSearchRequest("archive-1", "needle", 10, 1000));

        assertThat(gateway.requests).hasSize(3);
        assertThat(gateway.requests.get(0)).isInstanceOf(GitDiffRequest.class);
        assertThat(gateway.requests.get(1)).isInstanceOf(GitFileRequest.class);
        assertThat(gateway.requests.get(2)).isInstanceOf(CodeSearchRequest.class);
    }

    @Test
    void rejectsUnsafePathRefsAndRawCommandLikeInput() {
        assertThatThrownBy(() -> new GitFileRequest("archive-1", "../secret", 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GitDiffRequest("archive-1", "main", "--exec=bad", 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CodeSearchRequest("archive-1", "", 10, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class RecordingGateway implements SandboxToolGateway {
        private final List<SandboxToolRequest> requests = new ArrayList<>();

        @Override
        public ToolResult<Map<String, Object>> execute(ToolContext context, SandboxToolRequest request) {
            requests.add(request);
            return ToolResult.success(Map.of("status", "queued"));
        }
    }
}
