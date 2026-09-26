package io.github.yangjjiso.redyagents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class HarnessReliabilityTest {
    @TempDir
    Path directory;

    @Test
    @Timeout(10)
    void failedInitialSnapshotLeavesSessionIdleAndAllowsRetry() throws Exception {
        FileSessionStore durable = new FileSessionStore(directory.resolve("snapshots"));
        AtomicBoolean failNext = new AtomicBoolean();
        SessionStore store = new SessionStore() {
            @Override
            public void save(String sessionId, byte[] snapshot) throws IOException {
                if (failNext.getAndSet(false)) {
                    throw new IOException("injected write failure");
                }
                durable.save(sessionId, snapshot);
            }

            @Override
            public Map<String, byte[]> loadAll() throws IOException {
                return durable.loadAll();
            }
        };
        String sessionId;
        try (AgentService service = new AgentService(new LoopRunner(new DemoModel()), store)) {
            sessionId = service.createSession(new AgentConfig("test", "demo", "")).id();
            failNext.set(true);
            assertThrows(IllegalStateException.class, () -> service.startTurn(sessionId, "hello"));
            assertEquals("idle", service.getSession(sessionId).status());
            assertTrue(service.listTurns(sessionId).isEmpty());
            assertTrue(service.items(sessionId).isEmpty());

            Turn retry = service.startTurn(sessionId, "retry");
            awaitCompleted(service, sessionId, retry.id());
            assertEquals(1, service.listTurns(sessionId).size());
            assertEquals(1, service.items(sessionId).stream()
                    .filter(item -> "user".equals(item.role())).count());
        }
        try (AgentService restarted = new AgentService(new LoopRunner(new DemoModel()), durable)) {
            assertEquals(1, restarted.listTurns(sessionId).size());
            assertEquals("idle", restarted.getSession(sessionId).status());
        }
    }

    @Test
    @Timeout(10)
    void externalArgumentsStayValidatedWhenToolOverridesHook() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        Model model = (cancellation, agent, messages) -> modelCalls.incrementAndGet() == 1
                ? Decision.toolCall("lookup", new ToolCall("lookup", "bad-json".getBytes(StandardCharsets.UTF_8)))
                : Decision.finalMessage("done");
        ExternalFunctionTool external = new ExternalFunctionTool() {
            @Override
            public void validateArguments(byte[] arguments) {
                // Application validation cannot bypass the harness's JSON-object requirement.
            }
        };
        LoopRunner runner = new LoopRunner(model, Map.of("lookup", external), 3);
        List<String> events = new ArrayList<>();
        List<Message> recorded = new ArrayList<>();
        EventEmitter emitter = new EventEmitter() {
            @Override
            public void emit(String type, Map<String, Object> data) {
                events.add(type);
            }

            @Override
            public void record(Message message) {
                recorded.add(message);
            }
        };
        Session session = new Session("sess_test", new AgentConfig("test", "demo", ""),
                "idle", null, java.time.Instant.now(), java.time.Instant.now());
        LoopProgress progress = runner.advance(new CancellationToken(), session,
                runner.start(List.of(), "lookup"), emitter);
        assertTrue(progress.isCompleted());
        assertFalse(events.contains("tool.call.started"));
        assertTrue(recorded.stream().noneMatch(message -> message.callId() != null));
    }

    @Test
    @Timeout(10)
    void localToolIntentIsDurableBeforeExecutionAndFailureIsPaired() throws Exception {
        AtomicReference<AgentService> serviceRef = new AtomicReference<>();
        AtomicReference<String> sessionRef = new AtomicReference<>();
        AtomicBoolean sawIntent = new AtomicBoolean();
        Tool tool = (cancellation, arguments) -> {
            sawIntent.set(serviceRef.get().items(sessionRef.get()).stream()
                    .anyMatch(item -> "function_call".equals(item.type())
                            && "call_local".equals(item.callId())));
            throw new IOException("backend unavailable");
        };
        Model model = (cancellation, agent, messages) -> Decision.toolCall("call",
                new ToolCall("write", "{}".getBytes(StandardCharsets.UTF_8), "call_local"));
        LoopRunner runner = new LoopRunner(model, Map.of("write", tool), 2);
        try (AgentService service = new AgentService(runner,
                new FileSessionStore(directory.resolve("local-tool")))) {
            serviceRef.set(service);
            String sessionId = service.createSession(new AgentConfig("test", "demo", "")).id();
            sessionRef.set(sessionId);
            Turn turn = service.startTurn(sessionId, "write");
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < deadline && "in_progress".equals(
                    service.getTurn(sessionId, turn.id()).status())) {
                Thread.sleep(10);
            }
            assertEquals("failed", service.getTurn(sessionId, turn.id()).status());
            assertTrue(sawIntent.get());
            List<SessionItem> items = service.items(sessionId);
            assertEquals(1, items.stream().filter(item -> "function_call".equals(item.type())).count());
            assertEquals(1, items.stream().filter(item -> "function_call_output".equals(item.type())).count());
            assertTrue(items.stream().anyMatch(item -> "function_call_output".equals(item.type())
                    && "call_local".equals(item.callId()) && Boolean.FALSE.equals(item.success())));
        }
    }

    private static void awaitCompleted(AgentService service, String sessionId, String turnId)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if ("completed".equals(service.getTurn(sessionId, turnId).status())) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("turn did not complete");
    }
}
