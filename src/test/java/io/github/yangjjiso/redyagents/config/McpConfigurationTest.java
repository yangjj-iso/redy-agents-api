package io.github.yangjjiso.redyagents.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.yangjjiso.redyagents.mcp.McpServerConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class McpConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(McpBindingConfiguration.class);

    @Test
    void emptyConfigurationCreatesNoServerConnections() {
        contextRunner.run(context -> {
            assertFalse(context.getBean(McpProperties.class).toServerConfigs().iterator().hasNext());
        });
    }

    @Test
    void bindsMultipleServersAndRedactsCredentials() {
        contextRunner.withPropertyValues(
                "redy.mcp.servers[0].name=files",
                "redy.mcp.servers[0].transport=stdio",
                "redy.mcp.servers[0].command=/opt/mcp/files",
                "redy.mcp.servers[0].args[0]=--read-only",
                "redy.mcp.servers[0].env[TEST_TOKEN]=stdio-secret",
                "redy.mcp.servers[0].request-timeout=7",
                "redy.mcp.servers[1].name=search",
                "redy.mcp.servers[1].transport=http",
                "redy.mcp.servers[1].url=https://mcp.example.test",
                "redy.mcp.servers[1].endpoint=/tools",
                "redy.mcp.servers[1].headers[Authorization]=Bearer http-secret")
                .run(context -> {
                    McpProperties properties = context.getBean(McpProperties.class);
                    List<McpServerConfig> servers = properties.toServerConfigs();
                    assertEquals(2, servers.size());
                    assertEquals("stdio-secret", servers.get(0).env().get("TEST_TOKEN"));
                    assertEquals(List.of("--read-only"), servers.get(0).args());
                    assertEquals(Duration.ofSeconds(7), servers.get(0).requestTimeout());
                    assertEquals("Bearer http-secret", servers.get(1).headers().get("Authorization"));
                    assertEquals("/tools", servers.get(1).endpoint());
                    assertEquals(Duration.ofSeconds(20), servers.get(1).requestTimeout());
                    assertFalse(properties.toString().contains("stdio-secret"));
                    assertFalse(properties.toString().contains("http-secret"));
                    assertFalse(servers.get(0).toString().contains("stdio-secret"));
                    assertFalse(servers.get(1).toString().contains("http-secret"));
                });
    }

    @Test
    void domainConfigRejectsInvalidTransportSettings() {
        McpProperties invalid = new McpProperties(List.of(
                new McpProperties.Server("remote", "http", null, List.of(), Map.of(),
                        "ftp://example.test", null, Map.of(), null)));
        assertThrows(IllegalArgumentException.class, invalid::toServerConfigs);
    }

    @Test
    void httpEndpointCannotRedirectConfiguredHeadersToAnotherOrigin() {
        assertThrows(IllegalArgumentException.class, () -> new McpServerConfig(
                "remote", "http", null, List.of(), Map.of(),
                "https://trusted.example.test", "//other.example.test/mcp",
                Map.of("Authorization", "Bearer secret"), Duration.ofSeconds(2)));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(McpProperties.class)
    static class McpBindingConfiguration {
    }
}
