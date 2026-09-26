package io.github.yangjjiso.redyagents.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The environment attached to a durable session; provider credentials stay outside the snapshot. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionEnvironment(
        @JsonProperty("type") String type,
        @JsonProperty("sandbox_id") String sandboxId,
        @JsonProperty("template_id") String templateId,
        @JsonProperty("status") String status) {
    public SessionEnvironment(String type, String sandboxId, String templateId) {
        this(type, sandboxId, templateId, "active");
    }

    public static SessionEnvironment none() {
        return new SessionEnvironment("none", null, null, null);
    }

    public SessionEnvironment withStatus(String nextStatus) {
        return new SessionEnvironment(type, sandboxId, templateId, nextStatus);
    }
}
