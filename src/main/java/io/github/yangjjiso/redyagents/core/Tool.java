package io.github.yangjjiso.redyagents.core;

@FunctionalInterface
public interface Tool {
    String execute(CancellationToken cancellation, byte[] arguments) throws Exception;
}
