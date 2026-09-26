package io.github.yangjjiso.redyagents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "redy.sse.heartbeat-seconds=1"})
class AgentsHttpIntegrationTest {
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Test
    @Timeout(10)
    void demoTurnCompletesAndEventsCanBeReplayedAsJsonAndSse() throws Exception {
        HttpResponse<String> created = post("/v1/agents/sessions",
                "{\"agent\":{\"name\":\"example\",\"model\":\"demo\"}}", "application/json");
        assertEquals(201, created.statusCode(), created.body());
        JsonNode session = json.readTree(created.body());
        String sessionId = session.path("id").asText();
        assertTrue(sessionId.startsWith("sess_"));
        assertEquals("idle", session.path("status").asText());
        assertFalse(session.has("active_turn_id"));
        assertEquals(4096, session.path("agent").path("context_window_tokens").asInt());
        assertEquals(512, session.path("agent").path("max_output_tokens").asInt());

        String turnsPath = "/v1/agents/sessions/" + sessionId + "/turns";
        HttpResponse<String> started = post(turnsPath, "{\"input\":\"hello\"}", "application/json");
        assertEquals(202, started.statusCode(), started.body());
        JsonNode turn = json.readTree(started.body());
        String turnId = turn.path("id").asText();
        assertTrue(turnId.startsWith("turn_"));
        assertEquals(sessionId, turn.path("session_id").asText());
        assertFalse(turn.has("output"));

        JsonNode completed = awaitTerminalTurn(sessionId, turnId);
        assertEquals("completed", completed.path("status").asText(), completed.toString());
        assertEquals("Demo response: hello", completed.path("output").asText());
        assertEquals("idle", json.readTree(get("/v1/agents/sessions/" + sessionId).body())
                .path("status").asText());

        String eventsPath = "/v1/agents/sessions/" + sessionId + "/events";
        HttpResponse<String> replay = get(eventsPath + "?after=1");
        assertEquals(200, replay.statusCode(), replay.body());
        JsonNode events = json.readTree(replay.body()).path("events");
        assertEquals(2, events.size(), replay.body());
        assertEquals(2, events.get(0).path("sequence").asInt());
        assertEquals("message.delta", events.get(0).path("type").asText());
        assertEquals(3, events.get(1).path("sequence").asInt());
        assertEquals("turn.completed", events.get(1).path("type").asText());

        HttpRequest sseRequest = HttpRequest.newBuilder(uri(eventsPath + "?after=1"))
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<java.io.InputStream> stream = client.send(sseRequest, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, stream.statusCode());
        assertTrue(stream.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        try (var body = stream.body();
             var lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            assertEquals("2", lines.readLine().substring(3).trim());
            assertEquals("message.delta", lines.readLine().substring(6).trim());
            assertTrue(lines.readLine().startsWith("data:"));
            assertEquals("", lines.readLine());
            assertEquals("3", lines.readLine().substring(3).trim());
        }
    }

    @Test
    @Timeout(10)
    void invalidRequestsAndTerminalCancellationReturnContractStatuses() throws Exception {
        assertEquals(415, post("/v1/agents/sessions", "{}", "text/plain").statusCode());
        assertEquals(400, post("/v1/agents/sessions", "{broken", "application/json").statusCode());
        assertEquals(400, post("/v1/agents/sessions",
                "{\"agent\":{\"name\":\"x\",\"model\":\"demo\",\"unknown\":true}}", "application/json").statusCode());
        assertEquals(400, post("/v1/agents/sessions",
                "{\"agent\":{\"name\":\"x\",\"model\":\"demo\"}} true", "application/json").statusCode());
        assertEquals(400, post("/v1/agents/sessions",
                "{\"agent\":{\"name\":\"  \",\"model\":\"demo\"}}", "application/json").statusCode());
        assertEquals(400, post("/v1/agents/sessions",
                "{\"agent\":{\"name\":\"x\",\"model\":\"demo\",\"context_window_tokens\":256,\"max_output_tokens\":128}}",
                "application/json").statusCode());
        assertEquals(400, post("/v1/agents/sessions",
                "{\"agent\":{\"name\":\"x\",\"model\":\"demo\",\"max_output_tokens\":0}}",
                "application/json").statusCode());
        assertEquals(404, get("/v1/agents/sessions/sess_missing").statusCode());

        HttpResponse<String> created = post("/v1/agents/sessions",
                "{\"agent\":{\"name\":\"example\",\"model\":\"demo\"}}", "application/json");
        assertEquals(201, created.statusCode(), created.body());
        String sessionId = json.readTree(created.body()).path("id").asText();
        String turnsPath = "/v1/agents/sessions/" + sessionId + "/turns";
        assertEquals(400, post(turnsPath, "{\"input\":\" \"}", "application/json").statusCode());
        assertEquals(400, get("/v1/agents/sessions/" + sessionId + "/events?after=-1").statusCode());

        HttpResponse<String> started = post(turnsPath, "{\"input\":\"done\"}", "application/json");
        assertEquals(202, started.statusCode(), started.body());
        String turnId = json.readTree(started.body()).path("id").asText();
        JsonNode completed = awaitTerminalTurn(sessionId, turnId);
        assertEquals("completed", completed.path("status").asText(), completed.toString());
        assertEquals(409, post(turnsPath + "/" + turnId + "/cancel", "", "application/json").statusCode());
        assertEquals(404, get(turnsPath + "/turn_missing").statusCode());

        HttpResponse<String> emptyReplay = get("/v1/agents/sessions/" + sessionId + "/events?after=999");
        assertEquals(200, emptyReplay.statusCode());
        assertEquals(0, json.readTree(emptyReplay.body()).path("events").size());
    }

    @Test
    @Timeout(10)
    void oversizedLatestInputFailsWithinTheConfiguredPromptBudget() throws Exception {
        HttpResponse<String> created = post("/v1/agents/sessions",
                "{\"agent\":{\"name\":\"limited\",\"model\":\"demo\",\"context_window_tokens\":256,\"max_output_tokens\":64}}",
                "application/json");
        assertEquals(201, created.statusCode(), created.body());
        String sessionId = json.readTree(created.body()).path("id").asText();
        HttpResponse<String> started = post("/v1/agents/sessions/" + sessionId + "/turns",
                "{\"input\":\"" + "x".repeat(120) + "\"}", "application/json");
        assertEquals(202, started.statusCode(), started.body());
        String turnId = json.readTree(started.body()).path("id").asText();

        JsonNode failed = awaitTerminalTurn(sessionId, turnId);
        assertEquals("failed", failed.path("status").asText());
        assertTrue(failed.path("error").asText().contains("latest user input"));
    }

    private JsonNode awaitTerminalTurn(String sessionId, String turnId) throws Exception {
        String path = "/v1/agents/sessions/" + sessionId + "/turns/" + turnId;
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = get(path);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode turn = json.readTree(response.body());
            String status = turn.path("status").asText();
            if (status.equals("completed") || status.equals("failed") || status.equals("cancelled")) {
                return turn;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Turn did not reach a terminal status within five seconds");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String contentType) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", contentType)
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
