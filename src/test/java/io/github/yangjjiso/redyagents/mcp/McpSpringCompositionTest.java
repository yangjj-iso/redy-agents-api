package io.github.yangjjiso.redyagents.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.yangjjiso.redyagents.config.RedyAgentsConfiguration;
import io.github.yangjjiso.redyagents.core.AgentConfig;
import io.github.yangjjiso.redyagents.core.AgentService;
import io.github.yangjjiso.redyagents.core.CancellationToken;
import io.github.yangjjiso.redyagents.core.Decision;
import io.github.yangjjiso.redyagents.core.Message;
import io.github.yangjjiso.redyagents.core.Model;
import io.github.yangjjiso.redyagents.core.ToolCall;
import io.github.yangjjiso.redyagents.core.ToolDefinition;
import io.github.yangjjiso.redyagents.core.Turn;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** A configured MCP client flows through Spring, the default loop, and a durable turn. */
class McpSpringCompositionTest {
    @TempDir
    Path dataDir;

    @Test
    @Timeout(20)
    void defaultRunnerCallsConfiguredMcpToolAndReturnsItsResult() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(
                InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/mcp", McpTransportIntegrationTest::handleHttp);
        server.start();
        try {
            Model model = new Model() {
                @Override
                public Decision next(CancellationToken cancellation, AgentConfig agent,
                                     List<Message> messages) {
                    throw new AssertionError("the default runner must pass tool definitions");
                }

                @Override
                public Decision next(CancellationToken cancellation, AgentConfig agent,
                                     List<Message> messages, List<ToolDefinition> tools) {
                    assertTrue(tools.stream().anyMatch(tool ->
                            "mcp__remote__echo".equals(tool.name())));
                    for (Message message : messages) {
                        if ("tool".equals(message.role())) {
                            return Decision.finalMessage("MCP said: " + message.content());
                        }
                    }
                    return Decision.toolCall("calling MCP", new ToolCall("mcp__remote__echo",
                            "{\"value\":\"hello\"}".getBytes(StandardCharsets.UTF_8)));
                }
            };
            new ApplicationContextRunner()
                    .withUserConfiguration(RedyAgentsConfiguration.class)
                    .withBean(Model.class, () -> model)
                    .withPropertyValues(
                            "redy.data-dir=" + dataDir,
                            "redy.cube.enabled=false",
                            "redy.mcp.servers[0].name=remote",
                            "redy.mcp.servers[0].transport=http",
                            "redy.mcp.servers[0].url=http://127.0.0.1:"
                                    + server.getAddress().getPort(),
                            "redy.mcp.servers[0].endpoint=/mcp",
                            "redy.mcp.servers[0].headers[X-Test-Client]=redy",
                            "redy.mcp.servers[0].request-timeout=5s")
                    .run(context -> {
                        assertNotNull(context.getBean(McpToolRegistry.class)
                                .tools().get("mcp__remote__echo"));
                        AgentService service = context.getBean(AgentService.class);
                        String sessionId = service.createSession(
                                new AgentConfig("agent", "fixture", "")).id();
                        Turn turn = service.startTurn(sessionId, "Use the tool");
                        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                        while (System.nanoTime() < deadline) {
                            Turn current = service.getTurn(sessionId, turn.id());
                            if ("completed".equals(current.status())) {
                                assertEquals("MCP said: transport-ok", current.output());
                                return;
                            }
                            if ("failed".equals(current.status())) {
                                throw new AssertionError("MCP turn failed: " + current);
                            }
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError("test interrupted", interrupted);
                            }
                        }
                        throw new AssertionError("MCP turn did not complete");
                    });
        } finally {
            server.stop(0);
        }
    }
}
