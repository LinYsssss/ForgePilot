package com.acme.modelio;

public final class ModelOutputs {

    private ModelOutputs() {
    }

    /**
     * Strips a single Markdown code fence (``` or ~~~) so that every parser sees bare JSON.
     * All model-output parse paths must go through this method before feeding Jackson.
     */
    public static String stripCodeFence(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        String fence = trimmed.startsWith("```") ? "```" : trimmed.startsWith("~~~") ? "~~~" : null;
        if (fence == null) {
            return trimmed;
        }
        int firstLineBreak = trimmed.indexOf('\n');
        int closingFence = trimmed.lastIndexOf(fence);
        if (firstLineBreak < 0 || closingFence <= firstLineBreak) {
            return trimmed;
        }
        return trimmed.substring(firstLineBreak + 1, closingFence).trim();
    }
}
