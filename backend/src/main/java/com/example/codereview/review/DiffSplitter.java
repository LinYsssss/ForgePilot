package com.example.codereview.review;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a unified git diff into per-file segments and plans how to batch them into AI calls.
 *
 * <p>Replaces the old "truncate the whole diff at N chars" behaviour: instead of silently dropping
 * everything past a global limit, a large change is reviewed file by file. Files are packed into
 * batches that each stay under a per-call character budget; an individual file whose own diff
 * exceeds the budget is truncated in place (far less lossy than dropping later files entirely).
 *
 * <p>Pure and side-effect free so the batching rules can be unit tested without Spring or a model.
 */
public final class DiffSplitter {

    private static final String FILE_HEADER = "diff --git ";

    private DiffSplitter() {
    }

    public record FileDiff(String path, String content) {
    }

    /**
     * @param chunks        diff text to send to the model, one entry per AI call
     * @param totalFiles    files found in the diff before any cap
     * @param reviewedFiles files actually included after the {@code maxFiles} cap
     * @param skippedFiles  files dropped because of the {@code maxFiles} cap
     */
    public record ChunkPlan(List<String> chunks, int totalFiles, int reviewedFiles, int skippedFiles) {
    }

    public static List<FileDiff> splitByFile(String diff) {
        List<FileDiff> result = new ArrayList<>();
        if (diff == null || diff.isBlank()) {
            return result;
        }
        StringBuilder current = null;
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith(FILE_HEADER)) {
                if (current != null) {
                    result.add(toFileDiff(current.toString()));
                }
                current = new StringBuilder();
            } else if (current == null) {
                // Content before the first "diff --git" header (uncommon); keep it as its own segment.
                current = new StringBuilder();
            }
            current.append(line).append('\n');
        }
        if (current != null && !current.toString().isBlank()) {
            result.add(toFileDiff(current.toString()));
        }
        return result;
    }

    public static ChunkPlan plan(String diff, int maxChunkChars, int maxFiles) {
        int budget = maxChunkChars > 0 ? maxChunkChars : Integer.MAX_VALUE;
        List<FileDiff> files = splitByFile(diff);
        int totalFiles = files.size();
        int skipped = 0;
        if (maxFiles > 0 && files.size() > maxFiles) {
            skipped = files.size() - maxFiles;
            files = new ArrayList<>(files.subList(0, maxFiles));
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder batch = new StringBuilder();
        for (FileDiff file : files) {
            String content = capFile(file, budget);
            if (batch.length() > 0 && batch.length() + content.length() > budget) {
                chunks.add(batch.toString());
                batch.setLength(0);
            }
            batch.append(content);
        }
        if (batch.length() > 0) {
            chunks.add(batch.toString());
        }
        if (chunks.isEmpty()) {
            // No recognizable file headers (or blank diff): fall back to one bounded chunk.
            String fallback = diff == null ? "" : diff;
            if (fallback.length() > budget) {
                fallback = fallback.substring(0, budget) + "\n[Diff 已截断，超过单片预算 " + budget + " 字符]\n";
            }
            chunks.add(fallback);
        }
        return new ChunkPlan(chunks, totalFiles, files.size(), skipped);
    }

    private static String capFile(FileDiff file, int budget) {
        String content = file.content();
        if (content.length() <= budget) {
            return content;
        }
        String label = file.path() == null || file.path().isBlank() ? "" : file.path() + " 的";
        return content.substring(0, budget)
                + "\n[文件 " + label + " diff 已截断，超过单片预算 " + budget + " 字符]\n";
    }

    private static FileDiff toFileDiff(String content) {
        return new FileDiff(parsePath(content), content);
    }

    private static String parsePath(String content) {
        for (String line : content.split("\n", -1)) {
            if (line.startsWith("+++ b/")) {
                return line.substring("+++ b/".length()).trim();
            }
        }
        for (String line : content.split("\n", -1)) {
            if (line.startsWith(FILE_HEADER)) {
                int bIdx = line.lastIndexOf(" b/");
                if (bIdx >= 0) {
                    return line.substring(bIdx + 3).trim();
                }
            }
        }
        return null;
    }
}
