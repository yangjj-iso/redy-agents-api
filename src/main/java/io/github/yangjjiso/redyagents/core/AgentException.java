package io.github.yangjjiso.redyagents.core;

public final class AgentException extends RuntimeException {
    public enum Reason { INVALID, NOT_FOUND, CONFLICT, UNAVAILABLE }

    private final Reason reason;

    public AgentException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public static AgentException invalid() {
        return new AgentException(Reason.INVALID, "invalid request");
    }

    public static AgentException notFound() {
        return new AgentException(Reason.NOT_FOUND, "resource not found");
    }

    public static AgentException conflict() {
        return new AgentException(Reason.CONFLICT, "session already has an active turn");
    }

    public static AgentException unavailable(String message) {
        return new AgentException(Reason.UNAVAILABLE, message);
    }
}
