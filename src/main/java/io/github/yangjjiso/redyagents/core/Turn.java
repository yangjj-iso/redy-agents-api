package io.github.yangjjiso.redyagents.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Turn(
        @JsonProperty("id") String id,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("input") String input,
        @JsonProperty("status") String status,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("output") String output,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("error") String error,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("completed_at") Instant completedAt) {
}
