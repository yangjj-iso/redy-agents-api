package io.github.yangjjiso.redyagents.core;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AgentConfig(
        @JsonProperty("name") String name,
        @JsonProperty("model") String model,
        @JsonProperty("instructions") String instructions,
        @JsonProperty("context_window_tokens") Integer contextWindowTokens,
        @JsonProperty("max_output_tokens") Integer maxOutputTokens) {

    public static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 4096;
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 512;
    public static final int PROMPT_SAFETY_MARGIN_TOKENS = 128;

    public AgentConfig(String name, String model, String instructions) {
        this(name, model, instructions, null, null);
    }

    public AgentConfig {
        if (instructions == null) {
            instructions = "";
        }
        if (contextWindowTokens == null) {
            contextWindowTokens = DEFAULT_CONTEXT_WINDOW_TOKENS;
        }
        if (maxOutputTokens == null) {
            maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS;
        }
    }

    public int promptBudgetTokens() {
        return contextWindowTokens - maxOutputTokens - PROMPT_SAFETY_MARGIN_TOKENS;
    }
}
