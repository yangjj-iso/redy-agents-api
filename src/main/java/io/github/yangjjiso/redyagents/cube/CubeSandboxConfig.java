package io.github.yangjjiso.redyagents.cube;

import java.time.Duration;
import java.util.Locale;

/** Connection settings for CubeAPI and CubeProxy. No remote connection is made by this record. */
public record CubeSandboxConfig(
        String apiUrl,
        String apiKey,
        String templateId,
        String proxyNodeIp,
        int proxyPort,
        String proxyScheme,
        String sandboxDomain,
        Duration requestTimeout,
        boolean allowInternetAccess,
        int idleTimeoutSeconds
) {
    public CubeSandboxConfig {
        apiUrl = normalizedApiUrl(apiUrl);
        apiKey = apiKey == null ? "" : apiKey.trim();
        templateId = templateId == null ? "" : templateId.trim();
        proxyNodeIp = proxyNodeIp == null ? "" : proxyNodeIp.trim();
        if (proxyNodeIp.contains("/") || proxyNodeIp.contains("@")) {
            throw new IllegalArgumentException("proxyNodeIp must be a hostname or IP address");
        }
        proxyPort = proxyPort > 0 && proxyPort <= 65535 ? proxyPort : 80;
        proxyScheme = proxyScheme == null || proxyScheme.isBlank()
                ? (proxyPort == 443 ? "https" : "http")
                : proxyScheme.trim().toLowerCase(Locale.ROOT);
        if (!proxyScheme.equals("http") && !proxyScheme.equals("https")) {
            throw new IllegalArgumentException("proxyScheme must be http or https");
        }
        sandboxDomain = sandboxDomain == null || sandboxDomain.isBlank()
                ? "cube.app" : sandboxDomain.trim();
        requestTimeout = requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
                ? Duration.ofSeconds(30) : requestTimeout;
        idleTimeoutSeconds = idleTimeoutSeconds > 0 ? idleTimeoutSeconds : 300;
    }

    public CubeSandboxConfig(String apiUrl, String apiKey, String templateId, String proxyNodeIp,
                             int proxyPort, String proxyScheme, String sandboxDomain,
                             Duration requestTimeout, boolean allowInternetAccess) {
        this(apiUrl, apiKey, templateId, proxyNodeIp, proxyPort, proxyScheme, sandboxDomain,
                requestTimeout, allowInternetAccess, 300);
    }

    @Override
    public String toString() {
        return "CubeSandboxConfig[apiUrl=" + apiUrl + ", apiKey=<redacted>, templateId=" + templateId
                + ", proxyNodeIp=" + proxyNodeIp + ", proxyPort=" + proxyPort
                + ", proxyScheme=" + proxyScheme + ", sandboxDomain=" + sandboxDomain
                + ", requestTimeout=" + requestTimeout + ", allowInternetAccess="
                + allowInternetAccess + ", idleTimeoutSeconds=" + idleTimeoutSeconds + "]";
    }

    private static String normalizedApiUrl(String url) {
        String value = url == null || url.isBlank() ? "http://127.0.0.1:3000" : url.trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            throw new IllegalArgumentException("apiUrl must start with http:// or https://");
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

}
