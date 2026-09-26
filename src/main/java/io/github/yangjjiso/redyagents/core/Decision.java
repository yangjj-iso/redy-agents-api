package io.github.yangjjiso.redyagents.core;

public record Decision(String kind, String message, ToolCall tool) {
    public static Decision finalMessage(String message) {
        return new Decision("final", message, null);
    }

    public static Decision toolCall(String message, ToolCall tool) {
        return new Decision("tool_call", message, tool);
    }
}
