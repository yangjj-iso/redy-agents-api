package io.github.yangjjiso.redyagents.core;

import java.util.Map;

public record Decision(String kind, String message, ToolCall tool, Map<String, Object> modelState) {
    public Decision(String kind, String message, ToolCall tool) {
        this(kind, message, tool, Map.of());
    }

    public Decision {
        modelState = ModelState.copy(modelState);
    }

    public static Decision finalMessage(String message) {
        return new Decision("final", message, null);
    }

    public static Decision finalMessage(String message, Map<String, Object> modelState) {
        return new Decision("final", message, null, modelState);
    }

    public static Decision toolCall(String message, ToolCall tool) {
        return new Decision("tool_call", message, tool);
    }

    public static Decision toolCall(String message, ToolCall tool, Map<String, Object> modelState) {
        return new Decision("tool_call", message, tool, modelState);
    }

    @Override
    public String toString() {
        return "Decision[kind=" + kind + ", message=" + message + ", tool=" + tool
                + ", modelState=" + (modelState.isEmpty() ? "{}" : "<redacted>") + "]";
    }
}
