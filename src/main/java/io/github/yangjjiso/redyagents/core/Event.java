package io.github.yangjjiso.redyagents.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
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

    /** Stable across replay and restarts; the sequence is unique within a session. */
    @JsonProperty("event_id")
    public String eventId() {
        return "evt_" + sessionId + "_" + sequence;
    }

    /** Official event fields are top-level while the original data envelope stays available. */
    @JsonAnyGetter
    public Map<String, Object> officialPayload() {
        if (data == null || !type.startsWith("agent.session.")) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>(data);
        payload.keySet().removeAll(java.util.Set.of(
                "event_id", "sequence", "type", "session_id", "turn_id", "created_at", "data"));
        return payload;
    }
}
