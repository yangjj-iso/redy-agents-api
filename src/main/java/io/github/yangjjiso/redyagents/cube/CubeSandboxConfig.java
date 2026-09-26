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

    public static CubeSandboxConfig fromEnvironment() {
        String apiUrl = firstEnv("CUBE_API_URL", "E2B_API_URL");
        String apiKey = firstEnv("CUBE_API_KEY", "E2B_API_KEY");
        String templateId = firstEnv("CUBE_TEMPLATE_ID");
        String proxyNodeIp = firstEnv("CUBE_PROXY_NODE_IP");
        int proxyPort = parsePort(firstEnv("CUBE_PROXY_PORT_HTTP"));
        String proxyScheme = firstEnv("CUBE_PROXY_SCHEME");
        String sandboxDomain = firstEnv("CUBE_SANDBOX_DOMAIN");
        Duration requestTimeout = parseTimeout(firstEnv("CUBE_REQUEST_TIMEOUT"));
        boolean allowInternetAccess = Boolean.parseBoolean(firstEnv("CUBE_ALLOW_INTERNET_ACCESS"));
        int idleTimeoutSeconds = parsePositiveInt(firstEnv("CUBE_SANDBOX_IDLE_SECONDS"), 300);
        return new CubeSandboxConfig(apiUrl, apiKey, templateId, proxyNodeIp, proxyPort,
                proxyScheme, sandboxDomain, requestTimeout, allowInternetAccess, idleTimeoutSeconds);
    }

    @Override
    public String toString() {
        return "CubeSandboxConfig[apiUrl=" + apiUrl + ", apiKey=<redacted>, templateId=" + templateId
                + ", proxyNodeIp=" + proxyNodeIp + ", proxyPort=" + proxyPort
                + ", proxyScheme=" + proxyScheme + ", sandboxDomain=" + sandboxDomain
                + ", requestTimeout=" + requestTimeout + ", allowInternetAccess="
                + allowInternetAccess + ", idleTimeoutSeconds=" + idleTimeoutSeconds + "]";
    }

    private static String firstEnv(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
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

    private static int parsePort(String value) {
        return parsePositiveInt(value, 80);
    }

    private static int parsePositiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Duration parseTimeout(String value) {
        if (value.isBlank()) {
            return Duration.ofSeconds(30);
        }
        try {
            if (value.matches("[0-9]+")) {
                return Duration.ofSeconds(Long.parseLong(value));
            }
            if (value.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
            }
            if (value.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
            }
            if (value.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1)));
            }
            return Duration.parse(value);
        } catch (RuntimeException ignored) {
            return Duration.ofSeconds(30);
        }
    }
}
