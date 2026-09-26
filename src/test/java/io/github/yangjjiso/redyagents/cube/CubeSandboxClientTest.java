package io.github.yangjjiso.redyagents.cube;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

final class CubeSandboxClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void createConnectPauseAndKillUseOfficialControlRoutes() throws Exception {
        AtomicReference<JsonNode> createBody = new AtomicReference<>();
        server = server(exchange -> {
            String path = exchange.getRequestURI().getPath();
            assertEquals("Bearer secret", exchange.getRequestHeaders().getFirst("Authorization"));
            if (path.equals("/sandboxes") && exchange.getRequestMethod().equals("POST")) {
                createBody.set(json.readTree(exchange.getRequestBody().readAllBytes()));
                respond(exchange, 201, sandboxJson());
            } else if (path.equals("/sandboxes/sb1/connect")) {
                assertEquals("{}", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, sandboxJsonWithoutTraffic());
            } else if (path.equals("/sandboxes/sb1/pause")) {
                assertEquals("POST", exchange.getRequestMethod());
                respond(exchange, 204, new byte[0]);
            } else if (path.equals("/sandboxes/sb1") && exchange.getRequestMethod().equals("GET")) {
                respond(exchange, 200, "{\"state\":\"paused\"}".getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("/sandboxes/sb1")) {
                assertEquals("DELETE", exchange.getRequestMethod());
                respond(exchange, 204, new byte[0]);
            } else {
                respond(exchange, 404, new byte[0]);
            }
        });
        CubeSandboxClient client = client(Duration.ofSeconds(2));
        Sandbox created = client.create("tpl-requested");
        assertEquals("sb1", created.id());
        assertFalse(created.toString().contains("env-token"));
        JsonNode sent = createBody.get();
        assertEquals("tpl-requested", sent.path("templateID").asText());
        assertFalse(sent.path("allowInternetAccess").asBoolean(true));
        assertEquals("0.0.0.0/0", sent.path("network").path("denyOut").get(0).asText());
        assertEquals(300, sent.path("timeout").asInt());
        assertEquals("pause", sent.path("lifecycle").path("onTimeout").asText());
        assertTrue(sent.path("lifecycle").path("autoResume").asBoolean());
        Sandbox reconnected = client.connect("sb1");
        assertEquals("sb1", reconnected.id());
        assertEquals("traffic-token", reconnected.trafficAccessToken());
        client.pause("sb1");
        assertEquals("paused", client.getState("sb1"));
        client.kill("sb1");
    }

    @Test
    void commandUsesConnectFramesAndRequiresProcessEnd() throws Exception {
        AtomicReference<JsonNode> commandBody = new AtomicReference<>();
        server = server(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/sandboxes/sb1/connect")) {
                respond(exchange, 200, sandboxJson());
                return;
            }
            assertEquals("/sandbox/sb1/49983/process.Process/Start", exchange.getRequestURI().getPath());
            assertEquals("application/connect+json", exchange.getRequestHeaders().getFirst("Content-Type"));
            assertEquals("1", exchange.getRequestHeaders().getFirst("Connect-Protocol-Version"));
            assertEquals("root:", new String(Base64.getDecoder().decode(
                    exchange.getRequestHeaders().getFirst("Authorization").substring(6)), StandardCharsets.UTF_8));
            assertEquals("env-token", exchange.getRequestHeaders().getFirst("X-Access-Token"));
            assertEquals("traffic-token", exchange.getRequestHeaders().getFirst("cube-traffic-access-token"));
            assertEquals("1500", exchange.getRequestHeaders().getFirst("Connect-Timeout-Ms"));
            byte[] request = exchange.getRequestBody().readAllBytes();
            assertEquals(0, request[0]);
            assertEquals(request.length - 5, ByteBuffer.wrap(request, 1, 4).getInt());
            commandBody.set(json.readTree(Arrays.copyOfRange(request, 5, request.length)));
            respond(exchange, 200, concat(
                    frame(0, "{\"event\":{\"data\":{\"stdout\":\"aGk=\",\"stderr\":\"b29wcw==\"}}}"),
                    frame(0, "{\"event\":{\"end\":{\"exitCode\":7}}}"),
                    frame(2, "")));
        });
        CommandResult result = client(Duration.ofSeconds(2)).run("sb1", "echo hi", Duration.ofMillis(1500));
        assertEquals("hi", result.stdout());
        assertEquals("oops", result.stderr());
        assertEquals(7, result.exitCode());
        assertEquals("/bin/bash", commandBody.get().path("process").path("cmd").asText());
        assertEquals("-l", commandBody.get().path("process").path("args").get(0).asText());
        assertEquals("-c", commandBody.get().path("process").path("args").get(1).asText());
        assertEquals("echo hi", commandBody.get().path("process").path("args").get(2).asText());
        assertFalse(commandBody.get().path("stdin").asBoolean(true));
    }

    @Test
    void missingEndAndProtocolErrorAreFailures() throws Exception {
        AtomicReference<byte[]> responseBody = new AtomicReference<>(frame(0,
                "{\"event\":{\"data\":{\"stdout\":\"aGk=\"}}}"));
        server = server(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/connect")) {
                respond(exchange, 200, sandboxJson());
            } else {
                exchange.getRequestBody().readAllBytes();
                respond(exchange, 200, responseBody.get());
            }
        });
        CubeSandboxClient client = client(Duration.ofSeconds(2));
        CubeSandboxException missing = assertThrows(CubeSandboxException.class,
                () -> client.run("sb1", "echo hi", Duration.ofSeconds(1)));
        assertTrue(missing.getMessage().contains("without EndEvent"));

        responseBody.set(concat(frame(0, "{\"event\":{\"end\":{\"exited\":true}}}"),
                frame(2, "{\"error\":{\"code\":\"unavailable\",\"message\":\"lost\"}}")));
        CubeSandboxException protocol = assertThrows(CubeSandboxException.class,
                () -> client.run("sb1", "echo hi", Duration.ofSeconds(1)));
        assertTrue(protocol.getMessage().contains("unavailable lost"));

        responseBody.set(frame(0, "{\"event\":{\"end\":{\"error\":\"start failed\"}}}"));
        CubeSandboxException process = assertThrows(CubeSandboxException.class,
                () -> client.run("sb1", "echo hi", Duration.ofSeconds(1)));
        assertTrue(process.getMessage().contains("start failed"));
    }

    @Test
    void filesUsePathRouteAndPreserveRawBytes() throws Exception {
        byte[] file = new byte[]{0, 1, (byte) 0xff, 10};
        AtomicReference<byte[]> uploaded = new AtomicReference<>();
        server = server(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/connect")) {
                respond(exchange, 200, sandboxJson());
                return;
            }
            assertEquals("/sandbox/sb1/49983/files", exchange.getRequestURI().getPath());
            assertEquals("path=%2Ftmp%2Fx+%CE%B1.bin", exchange.getRequestURI().getRawQuery());
            assertEquals("env-token", exchange.getRequestHeaders().getFirst("X-Access-Token"));
            if (exchange.getRequestMethod().equals("GET")) {
                respond(exchange, 200, file);
            } else {
                assertEquals("application/octet-stream", exchange.getRequestHeaders().getFirst("Content-Type"));
                uploaded.set(exchange.getRequestBody().readAllBytes());
                respond(exchange, 200, new byte[0]);
            }
        });
        CubeSandboxClient client = client(Duration.ofSeconds(2));
        assertArrayEquals(file, client.readFile("sb1", "/tmp/x α.bin"));
        client.writeFile("sb1", "/tmp/x α.bin", file);
        assertArrayEquals(file, uploaded.get());
    }

    @Test
    void fileUploadFallsBackToMultipartOnlyForUnsupportedRawFormat() throws Exception {
        AtomicReference<byte[]> multipart = new AtomicReference<>();
        server = server(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/connect")) {
                respond(exchange, 200, sandboxJson());
                return;
            }
            String type = exchange.getRequestHeaders().getFirst("Content-Type");
            if (type.equals("application/octet-stream")) {
                exchange.getRequestBody().readAllBytes();
                respond(exchange, 415, new byte[0]);
            } else {
                assertTrue(type.startsWith("multipart/form-data; boundary="));
                multipart.set(exchange.getRequestBody().readAllBytes());
                respond(exchange, 200, new byte[0]);
            }
        });
        client(Duration.ofSeconds(2)).writeFile("sb1", "/tmp/test.bin", new byte[]{0, 1, 2});
        byte[] upload = multipart.get();
        assertNotNull(upload);
        assertTrue(new String(upload, StandardCharsets.ISO_8859_1).contains("filename=\"/tmp/test.bin\""));
        assertTrue(indexOf(upload, new byte[]{0, 1, 2}) >= 0);
    }

    @Test
    void commandTimeoutFailsInsteadOfReturningPartialSuccess() throws Exception {
        server = server(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/connect")) {
                respond(exchange, 200, sandboxJson());
            } else {
                exchange.getRequestBody().readAllBytes();
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                respond(exchange, 200, frame(0, "{\"event\":{\"end\":{\"exited\":true}}}"));
            }
        });
        CubeSandboxException error = assertThrows(CubeSandboxException.class,
                () -> client(Duration.ofMillis(100)).run("sb1", "sleep 1", Duration.ofMillis(50)));
        assertEquals(-1, error.statusCode());
    }

    private CubeSandboxClient client(Duration timeout) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new CubeSandboxClient(new CubeSandboxConfig(base, "secret", "tpl-default", "127.0.0.1",
                server.getAddress().getPort(), "http", "cube.app", timeout, false));
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private static byte[] sandboxJson() {
        return "{\"sandboxID\":\"sb1\",\"templateID\":\"tpl-default\",\"domain\":\"cube.app\","
                .concat("\"envdAccessToken\":\"env-token\",\"trafficAccessToken\":\"traffic-token\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sandboxJsonWithoutTraffic() {
        return "{\"sandboxID\":\"sb1\",\"templateID\":\"tpl-default\",\"domain\":\"cube.app\","
                .concat("\"envdAccessToken\":\"env-token\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] frame(int flags, String json) {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(5 + payload.length).put((byte) flags).putInt(payload.length).put(payload).array();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            output.writeBytes(part);
        }
        return output.toByteArray();
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, status == 204 ? -1 : body.length);
        if (status != 204) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }
}
