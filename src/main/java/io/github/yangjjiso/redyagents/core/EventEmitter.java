package io.github.yangjjiso.redyagents.core;

import java.util.Map;
import java.util.List;

@FunctionalInterface
public interface EventEmitter {
    void emit(String type, Map<String, Object> data);

    /** Records a completed model-visible item for durable session storage. */
    default void record(Message message) {
    }

    /** Returns user messages submitted while this turn was running. */
    default List<String> drainSteering() {
        return List.of();
    }

    /** Commits that claimed steering reached a model boundary and must not be auto-replayed. */
    default void markSteeringConsumed() {
    }
}
