package io.github.yangjjiso.redyagents.cube;

/** Tokens are deliberately omitted from the string representation. */
public record Sandbox(
        String id,
        String templateId,
        String domain,
        String envdAccessToken,
        String trafficAccessToken
) {
    @Override
    public String toString() {
        return "Sandbox[id=" + id + ", templateId=" + templateId + ", domain=" + domain + "]";
    }
}
