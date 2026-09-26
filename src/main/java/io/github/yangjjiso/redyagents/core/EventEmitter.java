package io.github.yangjjiso.redyagents.core;

import java.util.Map;

@FunctionalInterface
public interface EventEmitter {
    void emit(String type, Map<String, Object> data);
}
