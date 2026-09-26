package io.github.yangjjiso.redyagents.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A function call waiting for an application supplied result. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RequiredAction(
        @JsonProperty("type") String type,
        @JsonProperty("turn_id") String turnId,
        @JsonProperty("call_id") String callId,
        @JsonProperty("name") String name,
        @JsonProperty("arguments") Map<String, Object> arguments) {
    public RequiredAction {
        arguments = arguments == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
}
