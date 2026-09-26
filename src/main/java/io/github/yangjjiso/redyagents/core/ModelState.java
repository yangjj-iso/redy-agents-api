package io.github.yangjjiso.redyagents.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable JSON values carried between model decisions and durable model messages. */
final class ModelState {
    private ModelState() {}

    static Map<String, Object> copy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("model state keys must be strings");
            }
            copy.put(key, copyJson(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object copyJson(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copied = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("model state keys must be strings");
                }
                copied.put(key, copyJson(entry.getValue()));
            }
            return Collections.unmodifiableMap(copied);
        }
        if (value instanceof List<?> list) {
            List<Object> copied = new ArrayList<>(list.size());
            for (Object item : list) {
                copied.add(copyJson(item));
            }
            return Collections.unmodifiableList(copied);
        }
        throw new IllegalArgumentException("model state must contain JSON values");
    }
}
