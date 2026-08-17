package com.example.codereview.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.codereview.ai.AiCallLogService;
import com.example.codereview.ai.AiMetrics;
import com.example.codereview.ai.AiReviewClient;
import com.example.codereview.ai.AiReviewResult;
import com.example.codereview.ai.TokenUsage;
import com.example.codereview.rag.RagService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewProcessorTest {

    private static final String THREE_FILE_DIFF = """
            diff --git a/A.java b/A.java
            --- a/A.java
            +++ b/A.java
            @@ -1 +1,2 @@
            +a
            diff --git a/B.java b/B.java
            --- a/B.java
            +++ b/B.java
            @@ -1 +1,2 @@
            +b
            diff --git a/C.java b/C.java
            --- a/C.java
            +++ b/C.java
            @@ -1 +1,2 @@
            +c
            """;

    @Mock ReviewTaskRepository tasks;
    @Mock RagService ragService;
    @Mock AiReviewClient aiReviewClient;
    @Mock AiCallLogService aiCallLogService;
    @Mock AiMetrics aiMetrics;
    @Mock ReviewTaskStatusService taskStatusService;
    @Mock ReviewResultWriter resultWriter;

    @Test
    void multiFileDiff_fansOutPerFileAndMergesFindings() {
        ReviewTask task = new ReviewTask(7L, 1L, "abc", null, "main", 9L, THREE_FILE_DIFF);
        when(tasks.findById(1L)).thenReturn(Optional.of(task));
        when(ragService.buildContext(any(), any(), any())).thenReturn("");
        when(aiReviewClient.review(anyString(), anyString())).thenReturn(
                new AiReviewResult("片段问题", "HIGH", List.of(issue("x")), "{}", new TokenUsage(10, 5, 15)));

        // maxDiffChars=1 forces one chunk per file; maxFiles=40 keeps all three.
        ReviewProcessor processor = new ReviewProcessor(tasks, ragService, aiReviewClient, aiCallLogService,
                aiMetrics, taskStatusService, resultWriter, 48_000, 1, 40);

        processor.process(1L);

        // Three files -> three AI calls (no longer one truncated call).
        verify(aiReviewClient, times(3)).review(anyString(), anyString());

        ArgumentCaptor<AiReviewResult> captor = ArgumentCaptor.forClass(AiReviewResult.class);
        verify(resultWriter).saveSuccess(eq(1L), captor.capture());
        AiReviewResult merged = captor.getValue();

        assertEquals(3, merged.issues().size(), "issues from every chunk are merged");
        assertEquals("HIGH", merged.overallRisk(), "overall risk is the max across chunks");
        assertEquals(45, merged.usage().totalTokens(), "token cost is summed across chunks");
        assertTrue(merged.summary().contains("分片审查"), "summary reflects the chunked review");
    }

    @Test
    void canceledTaskDoesNotInvokeReviewDependencies() {
        ReviewTask task = new ReviewTask(7L, 1L, "abc", null, "main", 9L, THREE_FILE_DIFF);
        task.markCanceled();
        when(tasks.findById(1L)).thenReturn(Optional.of(task));
        ReviewProcessor processor = new ReviewProcessor(tasks, ragService, aiReviewClient, aiCallLogService,
                aiMetrics, taskStatusService, resultWriter, 48_000, 20_000, 40);

        processor.process(1L);

        verifyNoInteractions(ragService, aiReviewClient, resultWriter);
    }

    private static AiReviewResult.Issue issue(String title) {
        return new AiReviewResult.Issue("HIGH", "AUTH_RISK", "f.java", 1, 2, title, "d", "i", "e", "s", 0.9);
    }
}
