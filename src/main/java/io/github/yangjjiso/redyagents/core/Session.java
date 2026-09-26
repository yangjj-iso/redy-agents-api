package io.github.yangjjiso.redyagents.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Session(
        @JsonProperty("id") String id,
        @JsonProperty("agent") AgentConfig agent,
        @JsonProperty("status") String status,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("active_turn_id") String activeTurnId,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {
}
