package io.github.yangjjiso.redyagents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class OpenAiCompatibleModelTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AgentConfig AGENT = new AgentConfig(
            "agent", "remote-model", "Be concise.", 4096, 123);

    @Test
    @Timeout(10)
    void sendsInstructionsHistorySchemasAndNonStreamingOptions() throws Exception {
        AtomicReference<Map<?, ?>> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        try (Fixture fixture = new Fixture(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(object(JSON.readValue(exchange.getRequestBody().readAllBytes(), Object.class)));
            respond(exchange, 200, finalResponse("hello"));
        })) {
            OpenAiCompatibleModel model = new OpenAiCompatibleModel(fixture.baseUrl() + "/v1/",
                    "test-key", Duration.ofSeconds(2));
            List<Message> messages = List.of(
                    new Message("summary", "Earlier context"),
                    new Message("user", "question"));
            ToolDefinition tool = new ToolDefinition("mcp__remote__lookup", "Lookup a record",
                    Map.of("type", "object", "properties", Map.of("id", Map.of("type", "string"))));

            Decision decision = model.next(new CancellationToken(), AGENT, messages, List.of(tool));

            assertEquals(Decision.finalMessage("hello"), decision);
            assertEquals("/v1/chat/completions", path.get());
            assertEquals("Bearer test-key", authorization.get());
            Map<?, ?> request = requestBody.get();
            assertEquals("remote-model", request.get("model"));
            assertEquals(false, request.get("stream"));
            assertEquals(false, request.get("parallel_tool_calls"));
            assertEquals(123, request.get("max_tokens"));
            assertFalse(request.containsKey("max_completion_tokens"));
            List<?> sentMessages = array(request.get("messages"));
            assertEquals(Map.of("role", "system", "content", "Be concise."), sentMessages.get(0));
            assertEquals(Map.of("role", "system", "content", "Conversation summary:\nEarlier context"),
                    sentMessages.get(1));
            assertEquals(Map.of("role", "user", "content", "question"), sentMessages.get(2));
            Map<?, ?> sentTool = object(array(request.get("tools")).get(0));
            assertEquals("function", sentTool.get("type"));
            Map<?, ?> function = object(sentTool.get("function"));
            assertEquals("mcp__remote__lookup", function.get("name"));
            assertEquals("Lookup a record", function.get("description"));
            assertEquals(tool.inputSchema(), function.get("parameters"));
        }
    }

    @Test
    @Timeout(10)
    void completesMcpToolRoundTripWithOriginalCallIdAndArguments() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<Map<?, ?>> secondRequest = new AtomicReference<>();
        String arguments = "{ \"query\": \"a\" }";
        try (Fixture fixture = new Fixture(exchange -> {
            Map<?, ?> request = object(JSON.readValue(exchange.getRequestBody().readAllBytes(), Object.class));
            if (requests.incrementAndGet() == 1) {
                respond(exchange, 200, toolResponse("call_remote_123", "mcp__demo__echo", arguments));
            } else {
                secondRequest.set(request);
                respond(exchange, 200, finalResponse("done"));
            }
        })) {
            Model model = new OpenAiCompatibleModel(fixture.baseUrl() + "/v1", "",
                    Duration.ofSeconds(2), Duration.ofSeconds(2));
            Tool echo = new Tool() {
                @Override
                public ToolDefinition definition(String registeredName) {
                    return new ToolDefinition(registeredName, "Echo", Map.of("type", "object"));
                }

                @Override
                public String execute(CancellationToken cancellation, byte[] bytes) {
                    assertEquals(arguments, new String(bytes, StandardCharsets.UTF_8));
                    return "{\"ok\":true}";
                }
            };
            LoopRunner runner = new LoopRunner(model, Map.of("mcp__demo__echo", echo), 3);
            Session session = new Session("sess_test", AGENT, "idle", null,
                    Instant.now(), Instant.now());

            LoopProgress progress = runner.advance(new CancellationToken(), session,
                    runner.start(List.of(), "echo a"), (type, data) -> {});

            assertEquals("done", progress.output());
            assertEquals(2, requests.get());
            List<?> sentMessages = array(secondRequest.get().get("messages"));
            Map<?, ?> assistant = object(sentMessages.get(2));
            assertEquals("assistant", assistant.get("role"));
            assertEquals("private-tool-reasoning", assistant.get("reasoning_content"));
            assertEquals(List.of(Map.of("type", "text", "text", "private-tool-detail",
                    "signature", "sig-tool")), assistant.get("reasoning_details"));
            Map<?, ?> sentCall = object(array(assistant.get("tool_calls")).get(0));
            assertEquals("call_remote_123", sentCall.get("id"));
            assertEquals(arguments, object(sentCall.get("function")).get("arguments"));
            Map<?, ?> result = object(sentMessages.get(3));
            assertEquals("tool", result.get("role"));
            assertEquals("call_remote_123", result.get("tool_call_id"));
            assertEquals("{\"ok\":true}", result.get("content"));
            assertEquals("call_remote_123", progress.checkpoint().messages().get(1).callId());
            assertEquals("private-tool-reasoning",
                    progress.checkpoint().messages().get(1).modelState().get("reasoning_content"));
        }
    }

    @Test
    @Timeout(10)
    void keepsFinalReasoningAcrossRestartWithoutPublishingIt(@TempDir Path directory) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<Map<?, ?>> secondRequest = new AtomicReference<>();
        try (Fixture fixture = new Fixture(exchange -> {
            Map<?, ?> request = object(JSON.readValue(exchange.getRequestBody().readAllBytes(), Object.class));
            if (requests.incrementAndGet() == 1) {
                respond(exchange, 200, statefulFinalResponse("first answer"));
            } else {
                secondRequest.set(request);
                respond(exchange, 200, finalResponse("second answer"));
            }
        })) {
            AgentConfig kimi = new AgentConfig("agent", "kimi-k3", "Be concise.", 4096, 123);
            LoopRunner runner = new LoopRunner(new OpenAiCompatibleModel(fixture.baseUrl(), "",
                    Duration.ofSeconds(2)));
            String sessionId;
            FileSessionStore store = new FileSessionStore(directory);
            try (AgentService service = new AgentService(runner, store)) {
                sessionId = service.createSession(kimi).id();
                Turn first = service.startTurn(sessionId, "first question");
                assertEquals("first answer", awaitCompleted(service, sessionId, first.id()).output());
                assertFalse(JSON.writeValueAsString(service.items(sessionId)).contains("private-final"));
                assertFalse(JSON.writeValueAsString(service.eventsAfter(sessionId, 0))
                        .contains("private-final"));
                assertTrue(new String(store.loadAll().get(sessionId), StandardCharsets.UTF_8)
                        .contains("private-final"));
            }
            try (AgentService restored = new AgentService(runner, new FileSessionStore(directory))) {
                Turn second = restored.startTurn(sessionId, "follow up");
                assertEquals("second answer", awaitCompleted(restored, sessionId, second.id()).output());
            }
            assertEquals(2, requests.get());
            Map<?, ?> request = secondRequest.get();
            assertEquals(123, request.get("max_completion_tokens"));
            assertFalse(request.containsKey("max_tokens"));
            Map<?, ?> assistant = object(array(request.get("messages")).get(2));
            assertEquals("first answer", assistant.get("content"));
            assertEquals("private-final-reasoning", assistant.get("reasoning_content"));
            assertEquals(List.of(Map.of("type", "text", "text", "private-final-detail",
                    "signature", "sig-final")), assistant.get("reasoning_details"));
        }
    }

    @Test
    void rejectsRemotePlainHttpWithoutLeakingUrlOrKey() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new OpenAiCompatibleModel("http://example.com/v1", "private-key",
                        Duration.ofSeconds(1)));
        assertEquals("invalid model base URL", failure.getMessage());
        assertFalse(failure.toString().contains("example.com"));
        assertFalse(failure.toString().contains("private-key"));
    }

    @Test
    @Timeout(10)
    void rejectsMultipleCallsAndMalformedResponses() throws Exception {
        String oneCall = "{\"id\":\"call_1\",\"type\":\"function\",\"function\":"
                + "{\"name\":\"lookup\",\"arguments\":\"{}\"}}";
        String[] responses = {
                "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\","
                        + "\"tool_calls\":[" + oneCall + "," + oneCall + "]}}]}",
                "{\"choices\":[]}",
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\"}}]}",
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"partial\"}}]}",
                "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\","
                        + "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":"
                        + "{\"name\":\"lookup\",\"arguments\":\"[]\"}}]}}]}",
                "not-json"
        };
        AtomicInteger index = new AtomicInteger();
        try (Fixture fixture = new Fixture(exchange ->
                respond(exchange, 200, responses[index.getAndIncrement()]))) {
            OpenAiCompatibleModel model = new OpenAiCompatibleModel(fixture.baseUrl(), "",
                    Duration.ofSeconds(2));
            for (int i = 0; i < responses.length; i++) {
                IOException failure = assertThrows(IOException.class,
                        () -> model.next(new CancellationToken(), AGENT,
                                List.of(new Message("user", "hello"))));
                assertFalse(failure.getMessage().contains("not-json"));
            }
            assertEquals(responses.length, index.get());
        }
    }

    @Test
    @Timeout(10)
    void hidesErrorBodyAndKeyAndLimitsResponseToTwoMib() throws Exception {
        try (Fixture fixture = new Fixture(exchange ->
                respond(exchange, 429, "provider-secret-in-body"))) {
            OpenAiCompatibleModel model = new OpenAiCompatibleModel(fixture.baseUrl(),
                    "provider-secret-key", Duration.ofSeconds(2));
            IOException failure = assertThrows(IOException.class,
                    () -> model.next(new CancellationToken(), AGENT, List.of(new Message("user", "hi"))));
            assertEquals("Model HTTP status 429", failure.getMessage());
            assertFalse(failure.toString().contains("provider-secret"));
        }
        try (Fixture fixture = new Fixture(exchange ->
                respond(exchange, 200, "x".repeat(2 * 1024 * 1024 + 1)))) {
            OpenAiCompatibleModel model = new OpenAiCompatibleModel(fixture.baseUrl(), "",
                    Duration.ofSeconds(2));
            IOException failure = assertThrows(IOException.class,
                    () -> model.next(new CancellationToken(), AGENT, List.of(new Message("user", "hi"))));
            assertTrue(failure.getMessage().contains("2 MiB"), failure.getMessage());
        }
    }

    @Test
    @Timeout(10)
    void timesOutAndCancelsAnInFlightRequest() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch received = new CountDownLatch(1);
        try (Fixture fixture = new Fixture(exchange -> {
            received.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
                respond(exchange, 200, finalResponse("late"));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // A timed out or cancelled request closes its connection.
            }
        })) {
            OpenAiCompatibleModel timeoutModel = new OpenAiCompatibleModel(fixture.baseUrl(), "",
                    Duration.ofSeconds(1), Duration.ofMillis(100));
            IOException timeout = assertThrows(IOException.class,
                    () -> timeoutModel.next(new CancellationToken(), AGENT,
                            List.of(new Message("user", "hi"))));
            assertTrue(timeout.getMessage().contains("timed out"), timeout.getMessage());
            assertTrue(received.await(1, TimeUnit.SECONDS));

            CountDownLatch cancelledRequest = new CountDownLatch(1);
            try (Fixture cancelFixture = new Fixture(exchange -> {
                cancelledRequest.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                    respond(exchange, 200, finalResponse("late"));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    // The cancelled request closes its connection.
                }
            })) {
                OpenAiCompatibleModel model = new OpenAiCompatibleModel(cancelFixture.baseUrl(), "",
                        Duration.ofSeconds(2));
                CancellationToken cancellation = new CancellationToken();
                ExecutorService worker = Executors.newSingleThreadExecutor();
                try {
                    Future<Throwable> outcome = worker.submit(() -> {
                        cancellation.attachWorker();
                        try {
                            model.next(cancellation, AGENT, List.of(new Message("user", "hi")));
                            return null;
                        } catch (Throwable failure) {
                            return failure;
                        } finally {
                            cancellation.detachWorker();
                        }
                    });
                    assertTrue(cancelledRequest.await(2, TimeUnit.SECONDS));
                    cancellation.cancel();
                    assertInstanceOf(java.util.concurrent.CancellationException.class,
                            outcome.get(3, TimeUnit.SECONDS));
                } finally {
                    worker.shutdownNow();
                }
            }
        } finally {
            release.countDown();
        }
    }

    private static String finalResponse(String content) {
        return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                + "\"content\":" + quote(content) + "}}]}";
    }

    private static String toolResponse(String id, String name, String arguments) {
        return "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\","
                + "\"content\":null,\"reasoning_content\":\"private-tool-reasoning\","
                + "\"reasoning_details\":[{\"type\":\"text\",\"text\":\"private-tool-detail\","
                + "\"signature\":\"sig-tool\"}],\"tool_calls\":[{\"id\":" + quote(id)
                + ",\"type\":\"function\",\"function\":{\"name\":" + quote(name)
                + ",\"arguments\":" + quote(arguments) + "}}]}}]}";
    }

    private static String statefulFinalResponse(String content) {
        Map<String, Object> assistant = Map.of(
                "role", "assistant", "content", content,
                "reasoning_content", "private-final-reasoning",
                "reasoning_details", List.of(Map.of("type", "text",
                        "text", "private-final-detail", "signature", "sig-final")));
        return JSON.writeValueAsString(Map.of("choices", List.of(Map.of(
                "finish_reason", "stop", "message", assistant))));
    }

    private static Turn awaitCompleted(AgentService service, String sessionId, String turnId)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            Turn turn = service.getTurn(sessionId, turnId);
            if ("completed".equals(turn.status())) {
                return turn;
            }
            if ("failed".equals(turn.status())) {
                throw new AssertionError("model turn failed: " + turn.error());
            }
            Thread.sleep(10);
        }
        throw new AssertionError("model turn did not complete");
    }

    private static String quote(String value) {
        return JSON.writeValueAsString(value);
    }

    private static void respond(HttpExchange exchange, int status, String text) throws IOException {
        try (exchange) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private static Map<?, ?> object(Object value) {
        return (Map<?, ?>) value;
    }

    private static List<?> array(Object value) {
        return (List<?>) value;
    }

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService workers = Executors.newCachedThreadPool();

        Fixture(HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", handler);
            server.setExecutor(workers);
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
            workers.shutdownNow();
        }
    }
}
