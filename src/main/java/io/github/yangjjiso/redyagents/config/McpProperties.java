package io.github.yangjjiso.redyagents.config;

import io.github.yangjjiso.redyagents.mcp.McpServerConfig;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

/** Operator-owned MCP connections; no servers are started when the list is empty. */
@ConfigurationProperties(prefix = "redy.mcp")
public record McpProperties(List<Server> servers) {
    public List<McpServerConfig> toServerConfigs() {
        if (servers == null || servers.isEmpty()) {
            return List.of();
        }
        return servers.stream().map(Server::toConfig).toList();
    }

    /** Credentials in env and headers are intentionally excluded from diagnostics. */
    public record Server(String name, String transport, String command, List<String> args,
                         Map<String, String> env, String url, String endpoint,
                         Map<String, String> headers,
                         @DurationUnit(ChronoUnit.SECONDS) Duration requestTimeout) {
        McpServerConfig toConfig() {
            return new McpServerConfig(name, transport, command, args, env, url,
                    endpoint, headers, requestTimeout);
        }

        @Override
        public String toString() {
            return "Server[name=" + name + ", transport=" + transport
                    + ", credentials=<redacted>, requestTimeout=" + requestTimeout + "]";
        }
    }
}
