package io.github.yangjjiso.redyagents.core;

public record ToolCall(String name, byte[] arguments) {
    public ToolCall {
        arguments = arguments == null ? new byte[0] : arguments.clone();
    }

    @Override
    public byte[] arguments() {
        return arguments.clone();
    }
}
