package io.github.yangjjiso.redyagents.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Event(
        @JsonProperty("sequence") long sequence,
        @JsonProperty("type") String type,
        @JsonProperty("session_id") String sessionId,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("turn_id") String turnId,
        @JsonProperty("created_at") Instant createdAt,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("data") Map<String, Object> data) {

    public Event {
        if (data != null) {
            data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
        }
    }
}
