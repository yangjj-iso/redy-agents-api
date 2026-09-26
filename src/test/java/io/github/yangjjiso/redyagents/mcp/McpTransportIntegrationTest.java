package io.github.yangjjiso.redyagents.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.yangjjiso.redyagents.core.CancellationToken;
import io.github.yangjjiso.redyagents.core.Tool;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;

/** Local protocol fixtures verify both official SDK transports without an external server. */
class McpTransportIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PARENT_SENTINEL = "REDY_MCP_UNCONFIGURED_SENTINEL";
    private static final String ALLOWED_SENTINEL = "REDY_MCP_ALLOWED_SENTINEL";

    @Test
    @Timeout(20)
    void stdioInitializesDiscoversCallsAndCloses() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classPath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        McpServerConfig config = new McpServerConfig("local", "stdio", java,
                List.of("-cp", classPath, StdioFixture.class.getName()), Map.of(),
                null, null, Map.of(), Duration.ofSeconds(5));
        try (McpToolRegistry registry = new McpToolRegistry(List.of(config))) {
            assertEchoTool(registry, "mcp__local__echo");
        }
    }

    @Test
    @Timeout(20)
    void stdioChildDoesNotInheritUnconfiguredParentEnvironment() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classPath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", classPath,
                StdioEnvironmentProbe.class.getName());
        builder.environment().put(PARENT_SENTINEL, "present-in-service-process");
        builder.redirectErrorStream(true);
        Process probe = builder.start();
        try {
            assertTrue(probe.waitFor(10, TimeUnit.SECONDS), "stdio environment probe timed out");
            String output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, probe.exitValue(), output);
            assertTrue(output.contains("ENV_RESULT=hidden;allowed=explicit"), output);
        } finally {
            probe.destroyForcibly();
        }
    }

    @Test
    @Timeout(20)
    void streamableHttpInitializesDiscoversCallsAndCloses() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(
                InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/mcp", McpTransportIntegrationTest::handleHttp);
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            McpServerConfig config = new McpServerConfig("remote", "http", null,
                    List.of(), Map.of(), baseUrl, "/mcp", Map.of("X-Test-Client", "redy"),
                    Duration.ofSeconds(5));
            try (McpToolRegistry registry = new McpToolRegistry(List.of(config))) {
                assertEchoTool(registry, "mcp__remote__echo");
            }
        } finally {
            server.stop(0);
        }
    }

    private static void assertEchoTool(McpToolRegistry registry, String alias) throws Exception {
        Tool tool = registry.tools().get(alias);
        assertNotNull(tool);
        assertEquals(alias, tool.definition(alias).name());
        assertTrue(tool.definition(alias).description().contains("Echo a value"));
        assertEquals("transport-ok", tool.execute(new CancellationToken(),
                "{\"value\":\"hello\"}".getBytes(StandardCharsets.UTF_8)));
    }

    static void handleHttp(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            if (!"redy".equals(exchange.getRequestHeaders().getFirst("X-Test-Client"))) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response = reply(request);
            if (response == null) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    static String reply(String requestJson) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = JSON.readValue(requestJson, Map.class);
        Object id = request.get("id");
        if (id == null) {
            return null;
        }
        Object result = switch (String.valueOf(request.get("method"))) {
            case "initialize" -> Map.of(
                    "protocolVersion", "2025-11-25",
                    "capabilities", Map.of("tools", Map.of("listChanged", false)),
                    "serverInfo", Map.of("name", "local-test-fixture", "version", "1.0"));
            case "tools/list" -> Map.of("tools", List.of(
                    Map.of("name", "echo", "description", "Echo a value",
                            "inputSchema", Map.of("type", "object",
                                    "properties", Map.of("value", Map.of("type", "string")))),
                    Map.of("name", "check_env", "description", "Check inherited environment",
                            "inputSchema", Map.of("type", "object"))));
            case "tools/call" -> Map.of("content", List.of(Map.of(
                    "type", "text", "text", callResult(request))), "isError", false);
            default -> null;
        };
        if (result == null) {
            return JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id,
                    "error", Map.of("code", -32601, "message", "Method not found")));
        }
        return JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "result", result));
    }

    private static String callResult(Map<String, Object> request) {
        Object params = request.get("params");
        if (params instanceof Map<?, ?> map && "check_env".equals(map.get("name"))) {
            String parent = System.getenv(PARENT_SENTINEL) == null ? "hidden" : "leaked";
            String allowed = System.getenv(ALLOWED_SENTINEL);
            return parent + ";allowed=" + (allowed == null ? "missing" : allowed);
        }
        return "transport-ok";
    }

    /** A real child process whose stdout contains only MCP JSON-RPC frames. */
    public static final class StdioFixture {
        public static void main(String[] args) throws IOException {
            BufferedReader input = new BufferedReader(new InputStreamReader(
                    System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = input.readLine()) != null) {
                String response = reply(line);
                if (response != null) {
                    System.out.println(response);
                    System.out.flush();
                }
            }
        }
    }

    /** Runs in a JVM with a sentinel that must not reach its stdio MCP child. */
    public static final class StdioEnvironmentProbe {
        public static void main(String[] args) throws Exception {
            if (System.getenv(PARENT_SENTINEL) == null) {
                throw new IllegalStateException("probe parent sentinel missing");
            }
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            McpServerConfig config = new McpServerConfig("local", "stdio", java,
                    List.of("-cp", System.getProperty("java.class.path"),
                            StdioFixture.class.getName()), Map.of(ALLOWED_SENTINEL, "explicit"),
                    null, null, Map.of(), Duration.ofSeconds(5));
            try (McpToolRegistry registry = new McpToolRegistry(List.of(config))) {
                Tool tool = registry.tools().get("mcp__local__check_env");
                System.out.println("ENV_RESULT=" + tool.execute(new CancellationToken(),
                        "{}".getBytes(StandardCharsets.UTF_8)));
            }
        }
    }
}
