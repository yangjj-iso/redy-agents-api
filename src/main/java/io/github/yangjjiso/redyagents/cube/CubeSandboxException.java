package io.github.yangjjiso.redyagents.cube;

public final class CubeSandboxException extends RuntimeException {
    private final int statusCode;

    public CubeSandboxException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public CubeSandboxException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** HTTP status, or -1 for transport and protocol failures. */
    public int statusCode() {
        return statusCode;
    }
}
