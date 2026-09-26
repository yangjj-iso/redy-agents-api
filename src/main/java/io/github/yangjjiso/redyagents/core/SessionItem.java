package io.github.yangjjiso.redyagents.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/** Durable conversation and function-call history independent of the model's compacted context. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionItem(
        @JsonProperty("id") String id,
        @JsonProperty("turn_id") String turnId,
        @JsonProperty("type") String type,
        @JsonProperty("role") String role,
        @JsonProperty("content") String content,
        @JsonProperty("name") String name,
        @JsonProperty("call_id") String callId,
        @JsonProperty("arguments_base64") String argumentsBase64,
        @JsonProperty("success") Boolean success,
        @JsonProperty("created_at") Instant createdAt) {
}
