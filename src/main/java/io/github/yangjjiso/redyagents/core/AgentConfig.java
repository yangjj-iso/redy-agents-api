package io.github.yangjjiso.redyagents.core;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AgentConfig(
        @JsonProperty("name") String name,
        @JsonProperty("model") String model,
        @JsonProperty("instructions") String instructions) {

    public AgentConfig {
        if (instructions == null) {
            instructions = "";
        }
    }
}
