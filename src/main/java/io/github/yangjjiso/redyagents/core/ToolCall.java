package io.github.yangjjiso.redyagents.core;

public record ToolCall(String name, byte[] arguments, String callId) {
    public ToolCall(String name, byte[] arguments) {
        this(name, arguments, null);
    }

    public ToolCall {
        arguments = arguments == null ? new byte[0] : arguments.clone();
        callId = callId == null || callId.isBlank() ? null : callId;
    }

    @Override
    public byte[] arguments() {
        return arguments.clone();
    }
}
