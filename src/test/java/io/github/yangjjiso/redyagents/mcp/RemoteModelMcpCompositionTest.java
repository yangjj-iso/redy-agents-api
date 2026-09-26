package io.github.yangjjiso.redyagents.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.yangjjiso.redyagents.config.RedyAgentsConfiguration;
import io.github.yangjjiso.redyagents.core.AgentConfig;
import io.github.yangjjiso.redyagents.core.AgentService;
import io.github.yangjjiso.redyagents.core.Turn;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

/** Configured provider and external MCP server cooperate in an actual AgentService turn. */
class RemoteModelMcpCompositionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dataDir;

    @Test
    @Timeout(20)
    void defaultSpringCompositionCallsExternalMcpToolFromConfiguredModel() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<Map<?, ?>> firstRequest = new AtomicReference<>();
        AtomicReference<Map<?, ?>> secondRequest = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer model = localServer();
        HttpServer mcp = localServer();
        model.createContext("/plan/v3/chat/completions", exchange -> {
            try (exchange) {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                @SuppressWarnings("unchecked")
                Map<String, Object> request = JSON.readValue(
                        exchange.getRequestBody().readAllBytes(), Map.class);
                int number = requests.incrementAndGet();
                String response;
                if (number == 1) {
                    firstRequest.set(request);
                    response = "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{"
                            + "\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{"
                            + "\"id\":\"call_mcp_1\",\"type\":\"function\",\"function\":{"
                            + "\"name\":\"mcp__remote__echo\",\"arguments\":\"{\\\"value\\\":\\\"hello\\\"}\"}}]}}]}";
                } else {
                    secondRequest.set(request);
                    response = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{"
                            + "\"role\":\"assistant\",\"content\":\"MCP said: transport-ok\"}}]}";
                }
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        mcp.createContext("/mcp", McpTransportIntegrationTest::handleHttp);
        model.start();
        mcp.start();
        try {
            String modelUrl = "http://127.0.0.1:" + model.getAddress().getPort() + "/plan/v3";
            String mcpUrl = "http://127.0.0.1:" + mcp.getAddress().getPort();
            new ApplicationContextRunner()
                    .withUserConfiguration(RedyAgentsConfiguration.class)
                    .withPropertyValues(
                            "redy.data-dir=" + dataDir,
                            "redy.cube.enabled=false",
                            "redy.model.base-url=" + modelUrl,
                            "redy.model.api-key=fixture-key",
                            "redy.model.request-timeout=5s",
                            "redy.mcp.servers[0].name=remote",
                            "redy.mcp.servers[0].transport=http",
                            "redy.mcp.servers[0].url=" + mcpUrl,
                            "redy.mcp.servers[0].endpoint=/mcp",
                            "redy.mcp.servers[0].headers[X-Test-Client]=redy")
                    .run(context -> {
                        AgentService service = context.getBean(AgentService.class);
                        String sessionId = service.createSession(
                                new AgentConfig("agent", "fixture-model", "Use the echo tool.")).id();
                        Turn turn = service.startTurn(sessionId, "Echo hello");
                        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
                        while (System.nanoTime() < deadline) {
                            Turn current = service.getTurn(sessionId, turn.id());
                            if ("completed".equals(current.status())) {
                                assertEquals("MCP said: transport-ok", current.output());
                                return;
                            }
                            if ("failed".equals(current.status())) {
                                throw new AssertionError("remote model MCP turn failed: " + current.error());
                            }
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError("test interrupted", interrupted);
                            }
                        }
                        throw new AssertionError("remote model MCP turn did not finish");
                    });
            assertEquals(2, requests.get());
            assertEquals("Bearer fixture-key", authorization.get());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tools = (List<Map<String, Object>>) firstRequest.get().get("tools");
            assertNotNull(tools);
            assertTrue(tools.stream().anyMatch(tool -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> function = (Map<String, Object>) tool.get("function");
                return "mcp__remote__echo".equals(function.get("name"));
            }));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages =
                    (List<Map<String, Object>>) secondRequest.get().get("messages");
            assertTrue(messages.stream().anyMatch(message -> "tool".equals(message.get("role"))
                    && "call_mcp_1".equals(message.get("tool_call_id"))
                    && "transport-ok".equals(message.get("content"))));
        } finally {
            model.stop(0);
            mcp.stop(0);
        }
    }

    private static HttpServer localServer() throws IOException {
        return HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
    }
}
