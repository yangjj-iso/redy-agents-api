package io.github.yangjjiso.redyagents.core;

public record Message(String role, String content, String tool) {
    public Message(String role, String content) {
        this(role, content, "");
    }
}
