package com.example.codereview.assistant;

import com.example.codereview.ai.TokenUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Incremental OpenAI-compatible SSE parser; independent of HTTP chunk boundaries and CRLF. */
public final class OpenAiSseParser {

    private final ObjectMapper objectMapper;
    private final Consumer<String> onDelta;
    private final StringBuilder buffer = new StringBuilder();
    private TokenUsage usage = TokenUsage.none();
    private boolean done;
    private boolean pendingCarriageReturn;

    public OpenAiSseParser(ObjectMapper objectMapper, Consumer<String> onDelta) {
        this.objectMapper = objectMapper;
        this.onDelta = onDelta;
    }

    public void feed(String chunk) {
        if (chunk == null || chunk.isEmpty() || done) {
            return;
        }
        appendNormalized(chunk);
        drainEvents();
    }

    public void finish() {
        if (pendingCarriageReturn) {
            buffer.append('\n');
            pendingCarriageReturn = false;
        }
        drainEvents();
        if (!buffer.isEmpty() && !done) {
            parseEvent(buffer.toString());
            buffer.setLength(0);
        }
    }

    public TokenUsage usage() {
        return usage;
    }

    public boolean done() {
        return done;
    }

    private void appendNormalized(String chunk) {
        for (int i = 0; i < chunk.length(); i++) {
            char current = chunk.charAt(i);
            if (pendingCarriageReturn) {
                buffer.append('\n');
                pendingCarriageReturn = false;
                if (current == '\n') {
                    continue;
                }
            }
            if (current == '\r') {
                pendingCarriageReturn = true;
            } else {
                buffer.append(current);
            }
        }
    }

    private void drainEvents() {
        int boundary;
        while ((boundary = buffer.indexOf("\n\n")) >= 0) {
            String event = buffer.substring(0, boundary);
            buffer.delete(0, boundary + 2);
            parseEvent(event);
        }
    }

    private void parseEvent(String event) {
        List<String> data = new ArrayList<>();
        for (String line : event.split("\n", -1)) {
            if (line.startsWith("data:")) {
                data.add(line.substring(5).stripLeading());
            }
        }
        if (data.isEmpty()) {
            return;
        }
        String payload = String.join("\n", data);
        if ("[DONE]".equals(payload.strip())) {
            done = true;
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            String content = root.path("choices").path(0).path("delta").path("content").asText("");
            if (!content.isEmpty()) {
                onDelta.accept(content);
            }
            JsonNode usageNode = root.path("usage");
            if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                usage = new TokenUsage(
                        usageNode.path("prompt_tokens").asInt(0),
                        usageNode.path("completion_tokens").asInt(0),
                        usageNode.path("total_tokens").asInt(0));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("AI stream payload is invalid", ex);
        }
    }
}
