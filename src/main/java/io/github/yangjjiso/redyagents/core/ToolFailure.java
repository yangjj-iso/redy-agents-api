package io.github.yangjjiso.redyagents.core;

/** An expected tool failure that the model can use to correct its next call. */
public final class ToolFailure extends Exception {
    private final boolean retryable;

    public ToolFailure(String message) {
        this(message, false);
    }

    public ToolFailure(String message, boolean retryable) {
        super(message == null || message.isBlank() ? "tool failed" : message);
        this.retryable = retryable;
    }

    public ToolFailure(String message, Throwable cause, boolean retryable) {
        super(message == null || message.isBlank() ? "tool failed" : message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
