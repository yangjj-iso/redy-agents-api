package io.github.yangjjiso.redyagents.config;

import io.github.yangjjiso.redyagents.cube.CubeSandboxConfig;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

/** Deployment settings; environment variables are mapped in application.properties. */
@ConfigurationProperties(prefix = "redy.cube")
public record CubeSandboxProperties(
        String apiUrl,
        String apiKey,
        String templateId,
        String proxyNodeIp,
        int proxyPort,
        String proxyScheme,
        String sandboxDomain,
        @DurationUnit(ChronoUnit.SECONDS) Duration requestTimeout,
        boolean allowInternetAccess,
        int idleTimeoutSeconds) {

    public CubeSandboxConfig toClientConfig() {
        return new CubeSandboxConfig(apiUrl, apiKey, templateId, proxyNodeIp,
                proxyPort, proxyScheme, sandboxDomain, requestTimeout,
                allowInternetAccess, idleTimeoutSeconds);
    }

    @Override
    public String toString() {
        return "CubeSandboxProperties[apiUrl=" + apiUrl + ", apiKey=<redacted>, templateId="
                + templateId + ", proxyNodeIp=" + proxyNodeIp + ", proxyPort=" + proxyPort
                + ", proxyScheme=" + proxyScheme + ", sandboxDomain=" + sandboxDomain
                + ", requestTimeout=" + requestTimeout + ", allowInternetAccess="
                + allowInternetAccess + ", idleTimeoutSeconds=" + idleTimeoutSeconds + "]";
    }
}
