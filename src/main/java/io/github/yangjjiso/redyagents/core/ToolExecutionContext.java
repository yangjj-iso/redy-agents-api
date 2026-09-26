package io.github.yangjjiso.redyagents.core;

import java.util.Objects;

/** Identifies the durable turn and call whose side effects a tool may produce. */
public record ToolExecutionContext(Session session, String callId) {
    public ToolExecutionContext {
        Objects.requireNonNull(session, "session");
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("callId is required");
        }
    }

    public String sessionId() {
        return session.id();
    }

    public String turnId() {
        return session.activeTurnId();
    }
}
