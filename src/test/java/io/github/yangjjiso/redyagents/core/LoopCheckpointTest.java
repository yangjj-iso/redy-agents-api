package io.github.yangjjiso.redyagents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LoopCheckpointTest {
    @Test
    void externalCallSurvivesJsonRoundTripAndResumesWithoutExecutingTool() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        Model model = (cancellation, agent, messages) -> {
            if (modelCalls.incrementAndGet() == 1) {
                return Decision.toolCall("lookup", new ToolCall("lookup",
                        "{\"customer_id\":\"42\"}".getBytes(StandardCharsets.UTF_8)));
            }
            Message result = messages.get(messages.size() - 1);
            assertEquals("tool", result.role());
            assertEquals("found", result.content());
            assertEquals(Boolean.TRUE, result.success());
            assertNotNull(result.callId());
            assertEquals("{\"customer_id\":\"42\"}", new String(
                    java.util.Base64.getDecoder().decode(result.argumentsBase64()),
                    StandardCharsets.UTF_8));
            return Decision.finalMessage("done");
        };
        ExternalFunctionTool external = new ExternalFunctionTool() {
            @Override
            public void validateResult(byte[] arguments, String result) throws ToolFailure {
                if (!"found".equals(result)) {
                    throw new ToolFailure("bad lookup result");
                }
            }
        };
        LoopRunner runner = new LoopRunner(model, Map.of("lookup", external), 4);
        List<Message> recorded = new ArrayList<>();
        EventEmitter emit = new EventEmitter() {
            @Override
            public void emit(String type, Map<String, Object> data) {
            }

            @Override
            public void record(Message message) {
                recorded.add(message);
            }
        };
        LoopProgress first = runner.advance(new CancellationToken(), session(),
                runner.start(List.of(), "find customer"), emit);
        assertTrue(first.requiresAction());
        assertEquals(1, modelCalls.get());
        assertNotNull(first.pendingCall().callId());
        assertEquals(first.checkpoint(), runner.advance(new CancellationToken(), session(),
                first.checkpoint(), emit).checkpoint());
        assertEquals(1, modelCalls.get());

        ObjectMapper mapper = new ObjectMapper();
        LoopCheckpoint restored = mapper.readValue(
                mapper.writeValueAsBytes(first.checkpoint()), LoopCheckpoint.class);
        assertEquals(first.pendingCall().callId(), restored.pendingExternal().call().callId());
        LoopCheckpoint resumed = runner.resumeExternal(restored,
                new ToolResult(first.pendingCall().callId(), true, "found", null), emit);
        assertEquals(2, recorded.size());
        assertEquals(Boolean.TRUE, resumed.messages().get(resumed.messages().size() - 1).success());
        LoopProgress done = runner.advance(new CancellationToken(), session(), resumed, emit);
        assertTrue(done.isCompleted());
        assertEquals("done", done.output());
        assertEquals(2, modelCalls.get());
    }

    @Test
    void wrongCallIdIsRejectedAndFailureResultReturnsToModel() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Model model = (cancellation, agent, messages) -> {
            if (calls.incrementAndGet() == 1) {
                return Decision.toolCall("", new ToolCall("charge", "{}".getBytes(StandardCharsets.UTF_8), "call_a"));
            }
            assertTrue(messages.get(messages.size() - 1).content().contains("declined"));
            assertEquals(Boolean.FALSE, messages.get(messages.size() - 1).success());
            return Decision.finalMessage("stop");
        };
        LoopRunner runner = new LoopRunner(model, Map.of("charge", new ExternalFunctionTool() {}), 3);
        EventEmitter emit = (type, data) -> {};
        LoopProgress first = runner.advance(new CancellationToken(), session(),
                runner.start(List.of(), "pay"), emit);
        assertThrows(IllegalArgumentException.class, () ->
                runner.resumeExternal(first.checkpoint(), new ToolResult("wrong", false, null, "declined"), emit));
        LoopCheckpoint resumed = runner.resumeExternal(first.checkpoint(),
                new ToolResult("call_a", false, null, "declined"), emit);
        assertFalse(resumed.failedSignatures().isEmpty());
        assertEquals("stop", runner.advance(new CancellationToken(), session(), resumed, emit).output());
    }

    @Test
    void repeatedExternalCallIdReplaysSavedResultWithoutPausingAgain() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ToolCall sameCall = new ToolCall("lookup", "{}".getBytes(StandardCharsets.UTF_8), "call_same");
        Model model = (cancellation, agent, messages) -> calls.incrementAndGet() <= 2
                ? Decision.toolCall("lookup", sameCall)
                : Decision.finalMessage("done");
        LoopRunner runner = new LoopRunner(model, Map.of("lookup", new ExternalFunctionTool() {}), 4);
        List<String> events = new ArrayList<>();
        EventEmitter emit = (type, data) -> events.add(type);
        LoopProgress first = runner.advance(new CancellationToken(), session(),
                runner.start(List.of(), "lookup"), emit);
        assertTrue(first.requiresAction());
        LoopCheckpoint resumed = runner.resumeExternal(first.checkpoint(),
                new ToolResult("call_same", true, "found", null), emit);
        LoopProgress done = runner.advance(new CancellationToken(), session(), resumed, emit);
        assertTrue(done.isCompleted());
        assertEquals("done", done.output());
        assertEquals(3, calls.get());
        assertEquals(1, events.stream().filter("tool.call.started"::equals).count());
        assertTrue(events.contains("tool.call.replayed"));
    }

    @Test
    void invalidExternalArgumentsFailBeforeFunctionCallItemOrStartedEvent() throws Exception {
        List<byte[]> invalid = List.of(
                "not-json".getBytes(StandardCharsets.UTF_8),
                "[]".getBytes(StandardCharsets.UTF_8),
                "{} true".getBytes(StandardCharsets.UTF_8),
                new byte[] {(byte) 0xC3, (byte) 0x28});
        for (byte[] arguments : invalid) {
            AtomicInteger calls = new AtomicInteger();
            Model model = (cancellation, agent, messages) -> {
                if (calls.incrementAndGet() == 1) {
                    return Decision.toolCall("bad", new ToolCall("lookup", arguments, "call_bad"));
                }
                assertTrue(messages.get(messages.size() - 1).content().contains("UTF-8 JSON object"));
                return Decision.finalMessage("done");
            };
            LoopRunner runner = new LoopRunner(model, Map.of("lookup", new ExternalFunctionTool() {}), 3);
            List<Message> recorded = new ArrayList<>();
            List<String> events = new ArrayList<>();
            EventEmitter emit = new EventEmitter() {
                @Override
                public void emit(String type, Map<String, Object> data) {
                    events.add(type);
                }

                @Override
                public void record(Message message) {
                    recorded.add(message);
                }
            };
            LoopProgress done = runner.advance(new CancellationToken(), session(),
                    runner.start(List.of(), "lookup"), emit);
            assertTrue(done.isCompleted());
            assertFalse(events.contains("tool.call.started"));
            assertTrue(events.contains("tool.call.failed"));
            assertTrue(recorded.stream().noneMatch(message ->
                    message.tool() != null && !message.tool().isEmpty()));
            assertEquals(2, calls.get());
        }
    }

    @Test
    void steeringReceivedDuringSamplingExtendsTheCurrentTurn() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ArrayDeque<String> steering = new ArrayDeque<>();
        List<Message> recorded = new ArrayList<>();
        EventEmitter emit = new EventEmitter() {
            @Override
            public void emit(String type, Map<String, Object> data) {
            }

            @Override
            public void record(Message message) {
                recorded.add(message);
            }

            @Override
            public List<String> drainSteering() {
                List<String> drained = new ArrayList<>(steering);
                steering.clear();
                return drained;
            }
        };
        Model model = (cancellation, agent, messages) -> {
            if (calls.incrementAndGet() == 1) {
                steering.add("also include detail");
                return Decision.finalMessage("draft");
            }
            assertEquals("also include detail", messages.get(messages.size() - 1).content());
            return Decision.finalMessage("revised");
        };
        LoopProgress done = new LoopRunner(model).advance(new CancellationToken(), session(),
                new LoopRunner(model).start(List.of(), "answer"), emit);
        assertEquals("revised", done.output());
        assertEquals(2, calls.get());
        assertEquals(List.of("draft", "revised"), recorded.stream().map(Message::content).toList());
    }

    private static Session session() {
        return new Session("sess_test", new AgentConfig("test", "demo", ""),
                "running", "turn_1", Instant.now(), Instant.now());
    }
}
