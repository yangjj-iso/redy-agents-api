package io.github.yangjjiso.redyagents.core;

/** Result supplied by the application for a pending external function call. */
public record ToolResult(String callId, boolean success, String output, String error) {
    public ToolResult {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("tool result callId is required");
        }
        if (success && error != null && !error.isBlank()) {
            throw new IllegalArgumentException("successful tool result cannot contain an error");
        }
        if (!success && (error == null || error.isBlank())) {
            throw new IllegalArgumentException("failed tool result requires an error");
        }
        output = output == null ? "" : output;
    }
}
