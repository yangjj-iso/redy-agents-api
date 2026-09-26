package io.github.yangjjiso.redyagents.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Model-visible metadata for one registered function tool. */
public record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name is required");
        }
        description = description == null ? "" : description;
        inputSchema = inputSchema == null
                ? Map.of("type", "object", "additionalProperties", true)
                : copyObject(inputSchema);
    }

    private static Map<String, Object> copyObject(Map<?, ?> source) {
        Map<String, Object> copied = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("tool schema keys must be strings");
            }
            copied.put(key, copyJson(entry.getValue()));
        }
        return Collections.unmodifiableMap(copied);
    }

    private static Object copyJson(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return copyObject(map);
        }
        if (value instanceof List<?> list) {
            List<Object> copied = new ArrayList<>(list.size());
            for (Object item : list) {
                copied.add(copyJson(item));
            }
            return Collections.unmodifiableList(copied);
        }
        throw new IllegalArgumentException("tool schema must contain JSON values");
    }
}
