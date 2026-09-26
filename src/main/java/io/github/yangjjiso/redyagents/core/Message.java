package io.github.yangjjiso.redyagents.core;

import java.util.Map;

/** One model-visible item, including correlation data for a tool exchange. */
public record Message(String role, String content, String tool, String callId,
                      String argumentsBase64, Boolean success, Map<String, Object> modelState) {
    public Message(String role, String content, String tool, String callId,
                   String argumentsBase64, Boolean success) {
        this(role, content, tool, callId, argumentsBase64, success, Map.of());
    }

    public Message {
        modelState = ModelState.copy(modelState);
    }

    public Message(String role, String content) {
        this(role, content, "", null, null, null);
    }

    public Message(String role, String content, String tool) {
        this(role, content, tool, null, null, null);
    }

    public Message withContent(String newContent) {
        return new Message(role, newContent, tool, callId, argumentsBase64, success, modelState);
    }

    @Override
    public String toString() {
        return "Message[role=" + role + ", content=" + content + ", tool=" + tool
                + ", callId=" + callId + ", argumentsBase64=" + argumentsBase64
                + ", success=" + success + ", modelState="
                + (modelState.isEmpty() ? "{}" : "<redacted>") + "]";
    }
}
