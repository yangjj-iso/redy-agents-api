package io.github.yangjjiso.redyagents.core;

@FunctionalInterface
public interface Tool {
    String execute(CancellationToken cancellation, byte[] arguments) throws Exception;

    default void validateArguments(byte[] arguments) throws ToolFailure {
    }

    default void validateResult(byte[] arguments, String result) throws ToolFailure {
    }

    /** Repeating a failed call is permitted only when this is true and the failure is retryable. */
    default boolean isIdempotent() {
        return false;
    }

    /** External functions pause the loop until the application supplies a result. */
    default boolean isExternal() {
        return false;
    }
}
