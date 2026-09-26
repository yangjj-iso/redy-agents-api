package io.github.yangjjiso.redyagents.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Deployment-owned connection settings for one external MCP server. */
public record McpServerConfig(String name, String transport, String command, List<String> args,
                              Map<String, String> env, String url, String endpoint,
                              Map<String, String> headers, Duration requestTimeout) {
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,15}");

    public McpServerConfig {
        String configuredEndpoint = endpoint;
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("MCP server name must be 1-16 letters, digits, _ or - and start with a letter");
        }
        if (!"stdio".equals(transport) && !"http".equals(transport)) {
            throw new IllegalArgumentException("MCP transport must be stdio or http");
        }
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        endpoint = "http".equals(transport) && (endpoint == null || endpoint.isBlank())
                ? "/mcp" : endpoint;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(20) : requestTimeout;
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("MCP request timeout must be positive");
        }
        if ("stdio".equals(transport)) {
            if (command == null || command.isBlank()) {
                throw new IllegalArgumentException("MCP stdio command is required");
            }
            if (url != null && !url.isBlank()) {
                throw new IllegalArgumentException("MCP stdio server cannot have an HTTP URL");
            }
            if (!headers.isEmpty()) {
                throw new IllegalArgumentException("MCP stdio server cannot have HTTP headers");
            }
            if (configuredEndpoint != null && !configuredEndpoint.isBlank()) {
                throw new IllegalArgumentException("MCP stdio server cannot have an HTTP endpoint");
            }
        } else {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("MCP HTTP URL is required");
            }
            URI parsed = URI.create(url);
            if ((!"http".equals(parsed.getScheme()) && !"https".equals(parsed.getScheme()))
                    || parsed.getHost() == null || parsed.getUserInfo() != null
                    || parsed.getRawQuery() != null || parsed.getRawFragment() != null
                    || (parsed.getRawPath() != null && !parsed.getRawPath().isEmpty()
                        && !"/".equals(parsed.getRawPath()))) {
                throw new IllegalArgumentException("MCP HTTP URL must be an absolute http(s) base URL");
            }
            URI endpointUri = URI.create(endpoint);
            URI resolved = parsed.resolve(endpointUri);
            if (!endpoint.startsWith("/") || endpointUri.getRawAuthority() != null
                    || endpointUri.getScheme() != null || endpointUri.getRawQuery() != null
                    || endpointUri.getRawFragment() != null
                    || !Objects.equals(parsed.getScheme(), resolved.getScheme())
                    || !Objects.equals(parsed.getHost(), resolved.getHost())
                    || parsed.getPort() != resolved.getPort()) {
                throw new IllegalArgumentException("MCP HTTP endpoint must be an absolute path");
            }
            if (command != null && !command.isBlank()) {
                throw new IllegalArgumentException("MCP HTTP server cannot have a stdio command");
            }
            if (!args.isEmpty()) {
                throw new IllegalArgumentException("MCP HTTP server cannot have stdio arguments");
            }
            if (!env.isEmpty()) {
                throw new IllegalArgumentException("MCP HTTP server cannot have child environment variables");
            }
        }
        for (Map.Entry<String, String> entry : env.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "MCP environment variable name");
            Objects.requireNonNull(entry.getValue(), "MCP environment variable value");
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "MCP header name");
            Objects.requireNonNull(entry.getValue(), "MCP header value");
        }
    }

    @Override
    public String toString() {
        return "McpServerConfig[name=" + name + ", transport=" + transport
                + ", command=<redacted>, args=<redacted>, env=<redacted>, url=<redacted>"
                + ", endpoint=<redacted>, headers=<redacted>, requestTimeout="
                + requestTimeout + "]";
    }
}
