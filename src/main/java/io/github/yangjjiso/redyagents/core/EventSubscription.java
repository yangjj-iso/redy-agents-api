package io.github.yangjjiso.redyagents.core;

public interface EventSubscription extends AutoCloseable {
    @Override
    void close();
}
