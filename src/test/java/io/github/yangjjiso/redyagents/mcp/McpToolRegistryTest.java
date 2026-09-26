package io.github.yangjjiso.redyagents.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.yangjjiso.redyagents.core.AgentConfig;
import io.github.yangjjiso.redyagents.core.CancellationToken;
import io.github.yangjjiso.redyagents.core.Decision;
import io.github.yangjjiso.redyagents.core.LoopRunner;
import io.github.yangjjiso.redyagents.core.Message;
import io.github.yangjjiso.redyagents.core.Model;
import io.github.yangjjiso.redyagents.core.Session;
import io.github.yangjjiso.redyagents.core.Tool;
import io.github.yangjjiso.redyagents.core.ToolCall;
import io.github.yangjjiso.redyagents.core.ToolDefinition;
import io.github.yangjjiso.redyagents.core.ToolFailure;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class McpToolRegistryTest {
    private static final Map<String, Object> ECHO_SCHEMA = Map.of(
            "type", "object", "properties", Map.of("text", Map.of("type", "string")),
            "required", List.of("text"));

    @Test
    void discoversPaginatedToolsAndPassesSchemasAndResultsThroughLoop() throws Exception {
        FakeConnection connection = new FakeConnection();
        try (McpToolRegistry registry = new McpToolRegistry(List.of(config()), ignored -> connection)) {
            assertEquals(List.of("mcp__demo__echo", "mcp__demo__second"),
                    registry.tools().keySet().stream().toList());
            ToolDefinition definition = registry.tools().get("mcp__demo__echo")
                    .definition("mcp__demo__echo");
            assertEquals(ECHO_SCHEMA, definition.inputSchema());
            assertTrue(definition.description().contains("demo"));

            AtomicInteger calls = new AtomicInteger();
            Model model = new Model() {
                @Override
                public Decision next(CancellationToken cancellation, AgentConfig agent,
                                     List<Message> messages) {
                    throw new AssertionError("tool definitions were not passed to the model");
                }

                @Override
                public Decision next(CancellationToken cancellation, AgentConfig agent,
                                     List<Message> messages, List<ToolDefinition> tools) {
                    assertEquals(ECHO_SCHEMA, tools.get(0).inputSchema());
                    if (calls.getAndIncrement() == 0) {
                        return Decision.toolCall("", new ToolCall("mcp__demo__echo",
                                "{\"text\":\"hello\"}".getBytes(StandardCharsets.UTF_8), "call_mcp"));
                    }
                    assertEquals("echo: hello", messages.get(messages.size() - 1).content());
                    return Decision.finalMessage("done");
                }
            };
            AgentConfig agent = new AgentConfig("agent", "test", "");
            Session session = new Session("sess_test", agent, "in_progress", "turn_test",
                    Instant.now(), Instant.now());
            String output = new LoopRunner(model, registry.tools(), 4)
                    .run(new CancellationToken(), session, List.of(), "say hello", (type, data) -> {});
            assertEquals("done", output);
            assertEquals("echo", connection.lastCall.get().name());
            assertEquals("hello", connection.lastCall.get().arguments().get("text"));
        }
        assertTrue(connection.closed);
    }

    @Test
    void mcpErrorContentBecomesModelFeedbackAndMalformedArgumentsNeverReachServer() throws Exception {
        FakeConnection connection = new FakeConnection();
        connection.error = true;
        try (McpToolRegistry registry = new McpToolRegistry(List.of(config()), ignored -> connection)) {
            Tool tool = registry.tools().get("mcp__demo__echo");
            ToolFailure invalid = assertThrows(ToolFailure.class,
                    () -> tool.validateArguments("[]".getBytes(StandardCharsets.UTF_8)));
            assertTrue(invalid.getMessage().contains("JSON object"));
            assertEquals(null, connection.lastCall.get());
            ToolFailure missingRequired = assertThrows(ToolFailure.class,
                    () -> tool.validateArguments("{}".getBytes(StandardCharsets.UTF_8)));
            assertTrue(missingRequired.getMessage().contains("input schema"));
            ToolFailure error = assertThrows(ToolFailure.class,
                    () -> tool.execute(new CancellationToken(),
                            "{\"text\":\"hello\"}".getBytes(StandardCharsets.UTF_8)));
            assertTrue(error.getMessage().contains("remote failure"));
            assertFalse(error.retryable());
        }
    }

    @Test
    void repeatedCursorIsRejectedAndConnectionIsClosed() {
        FakeConnection connection = new FakeConnection();
        connection.repeatCursor = true;
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new McpToolRegistry(List.of(config()), ignored -> connection));
        assertTrue(failure.getMessage().contains("cursor"));
        assertTrue(connection.closed);
    }

    @Test
    void declaredOutputSchemaRejectsAProtocolSuccessWithNoStructuredResult() {
        FakeConnection connection = new FakeConnection();
        io.modelcontextprotocol.spec.McpSchema.Tool described =
                io.modelcontextprotocol.spec.McpSchema.Tool.builder("echo")
                        .inputSchema(ECHO_SCHEMA)
                        .outputSchema(Map.of("type", "object", "required", List.of("answer"),
                                "properties", Map.of("answer", Map.of("type", "string"))))
                        .build();
        McpToolAdapter tool = new McpToolAdapter("demo", connection, described);
        ToolFailure failure = assertThrows(ToolFailure.class,
                () -> tool.execute(new CancellationToken(),
                        "{\"text\":\"hello\"}".getBytes(StandardCharsets.UTF_8)));
        assertTrue(failure.getMessage().contains("output schema"));
    }

    @Test
    void failureOpeningALaterServerClosesEarlierConnections() {
        FakeConnection first = new FakeConnection();
        McpServerConfig second = new McpServerConfig("other", "stdio", "unused",
                List.of(), Map.of(), null, null, Map.of(), Duration.ofSeconds(2));
        assertThrows(IllegalStateException.class,
                () -> new McpToolRegistry(List.of(config(), second), server -> {
                    if ("other".equals(server.name())) {
                        throw new IllegalStateException("unavailable");
                    }
                    return first;
                }));
        assertTrue(first.closed);
    }

    @Test
    void structuredContentIsReturnedAsModelVisibleJson() throws Exception {
        FakeConnection connection = new FakeConnection();
        connection.structured = true;
        try (McpToolRegistry registry = new McpToolRegistry(List.of(config()), ignored -> connection)) {
            String output = registry.tools().get("mcp__demo__echo")
                    .execute(new CancellationToken(),
                            "{\"text\":\"hello\"}".getBytes(StandardCharsets.UTF_8));
            assertTrue(output.contains("\"structured_content\""));
            assertTrue(output.contains("\"answer\":\"hello\""));
        }
    }

    @Test
    void oversizedStructuredContentRemainsValidBoundedJson() throws Exception {
        FakeConnection connection = new FakeConnection();
        connection.oversizedStructured = true;
        try (McpToolRegistry registry = new McpToolRegistry(List.of(config()), ignored -> connection)) {
            String output = registry.tools().get("mcp__demo__echo")
                    .execute(new CancellationToken(),
                            "{\"text\":\"hello\"}".getBytes(StandardCharsets.UTF_8));
            assertTrue(output.length() <= 65536);
            @SuppressWarnings("unchecked")
            Map<String, Object> decoded = new ObjectMapper().readValue(output, Map.class);
            assertEquals(true, decoded.get("truncated"));
            assertTrue(((String) decoded.get("preview")).contains("structured_content"));
        }
    }

    private static McpServerConfig config() {
        return new McpServerConfig("demo", "stdio", "unused", List.of(), Map.of(),
                null, null, Map.of(), Duration.ofSeconds(2));
    }

    private static io.modelcontextprotocol.spec.McpSchema.Tool tool(String name) {
        return io.modelcontextprotocol.spec.McpSchema.Tool.builder(name)
                .description("Echo input")
                .inputSchema(ECHO_SCHEMA)
                .build();
    }

    private static final class FakeConnection implements McpConnection {
        private final AtomicReference<CallToolRequest> lastCall = new AtomicReference<>();
        private boolean closed;
        private boolean error;
        private boolean repeatCursor;
        private boolean structured;
        private boolean oversizedStructured;

        @Override
        public ListToolsResult listTools(String cursor) {
            if (cursor == null) {
                return new ListToolsResult(List.of(tool("echo")), "page2");
            }
            return new ListToolsResult(List.of(tool("second")), repeatCursor ? "page2" : null);
        }

        @Override
        public CallToolResult callTool(CallToolRequest request) {
            lastCall.set(request);
            if (error) {
                return CallToolResult.builder().addTextContent("remote failure").isError(true).build();
            }
            if (structured) {
                return CallToolResult.builder().addTextContent("echo: hello")
                        .structuredContent(Map.of("answer", "hello"))
                        .isError(false).build();
            }
            if (oversizedStructured) {
                return CallToolResult.builder()
                        .structuredContent(Map.of("answer", "x".repeat(70_000)))
                        .isError(false).build();
            }
            return CallToolResult.builder()
                    .addTextContent("echo: " + request.arguments().get("text"))
                    .isError(false).build();
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
