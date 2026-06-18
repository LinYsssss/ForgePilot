package com.example.codereview.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DiffSplitterTest {

    private static final String THREE_FILE_DIFF = """
            diff --git a/src/A.java b/src/A.java
            index 111..222 100644
            --- a/src/A.java
            +++ b/src/A.java
            @@ -1,2 +1,3 @@
             line
            +added A
            diff --git a/src/B.java b/src/B.java
            index 333..444 100644
            --- a/src/B.java
            +++ b/src/B.java
            @@ -1,2 +1,3 @@
             line
            +added B
            diff --git a/src/C.java b/src/C.java
            index 555..666 100644
            --- a/src/C.java
            +++ b/src/C.java
            @@ -1,2 +1,3 @@
             line
            +added C
            """;

    @Test
    void splitByFile_separatesEachFileAndParsesPath() {
        List<DiffSplitter.FileDiff> files = DiffSplitter.splitByFile(THREE_FILE_DIFF);

        assertEquals(3, files.size());
        assertEquals("src/A.java", files.get(0).path());
        assertEquals("src/B.java", files.get(1).path());
        assertEquals("src/C.java", files.get(2).path());
        assertTrue(files.get(0).content().contains("+added A"));
    }

    @Test
    void plan_smallDiffFitsInOneChunk() {
        DiffSplitter.ChunkPlan plan = DiffSplitter.plan(THREE_FILE_DIFF, 100_000, 40);

        assertEquals(3, plan.totalFiles());
        assertEquals(3, plan.reviewedFiles());
        assertEquals(0, plan.skippedFiles());
        assertEquals(1, plan.chunks().size()); // single-chunk = old single-call path
    }

    @Test
    void plan_tinyBudgetSplitsPerFileAndTruncates() {
        DiffSplitter.ChunkPlan plan = DiffSplitter.plan(THREE_FILE_DIFF, 1, 40);

        assertEquals(3, plan.chunks().size());
        assertTrue(plan.chunks().get(0).contains("已截断"), "oversized file diff should be truncated in place");
    }

    @Test
    void plan_capsFileCountAndReportsSkipped() {
        DiffSplitter.ChunkPlan plan = DiffSplitter.plan(THREE_FILE_DIFF, 100_000, 2);

        assertEquals(3, plan.totalFiles());
        assertEquals(2, plan.reviewedFiles());
        assertEquals(1, plan.skippedFiles());
        assertFalse(String.join("", plan.chunks()).contains("src/C.java"), "3rd file must be dropped by the cap");
    }

    @Test
    void plan_blankDiffYieldsSingleFallbackChunk() {
        DiffSplitter.ChunkPlan plan = DiffSplitter.plan("", 1000, 10);

        assertEquals(0, plan.totalFiles());
        assertEquals(1, plan.chunks().size());
    }
}
