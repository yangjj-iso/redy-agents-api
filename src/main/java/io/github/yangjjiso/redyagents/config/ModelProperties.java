package io.github.yangjjiso.redyagents.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

/** Optional OpenAI-compatible model endpoint; credentials are supplied by the deployment. */
@ConfigurationProperties(prefix = "redy.model")
public record ModelProperties(String baseUrl, String apiKey,
                              @DurationUnit(ChronoUnit.SECONDS) Duration requestTimeout) {
    public ModelProperties {
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(60) : requestTimeout;
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("redy.model.request-timeout must be positive");
        }
    }

    @Override
    public String toString() {
        return "ModelProperties[baseUrl=<redacted>, apiKey=<redacted>, requestTimeout="
                + requestTimeout + "]";
    }
}
