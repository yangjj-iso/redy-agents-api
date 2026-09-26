package io.github.yangjjiso.redyagents.core;

/** One model-visible item, including correlation data for a tool exchange. */
public record Message(String role, String content, String tool, String callId,
                      String argumentsBase64, Boolean success) {
    public Message(String role, String content) {
        this(role, content, "", null, null, null);
    }

    public Message(String role, String content, String tool) {
        this(role, content, tool, null, null, null);
    }

    public Message withContent(String newContent) {
        return new Message(role, newContent, tool, callId, argumentsBase64, success);
    }
}
