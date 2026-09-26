package io.github.yangjjiso.redyagents.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Session(
        @JsonProperty("id") String id,
        @JsonProperty("agent") AgentConfig agent,
        @JsonProperty("status") String status,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("active_turn_id") String activeTurnId,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("required_actions") List<RequiredAction> requiredActions,
        @JsonProperty("environment") SessionEnvironment environment) {
    public Session(String id, AgentConfig agent, String status, String activeTurnId,
                   Instant createdAt, Instant updatedAt) {
        this(id, agent, status, activeTurnId, createdAt, updatedAt, List.of(), SessionEnvironment.none());
    }

    public Session(String id, AgentConfig agent, String status, String activeTurnId,
                   Instant createdAt, Instant updatedAt, List<RequiredAction> requiredActions) {
        this(id, agent, status, activeTurnId, createdAt, updatedAt, requiredActions,
                SessionEnvironment.none());
    }

    public Session {
        requiredActions = requiredActions == null ? List.of() : List.copyOf(requiredActions);
        environment = environment == null ? SessionEnvironment.none() : environment;
    }
}
