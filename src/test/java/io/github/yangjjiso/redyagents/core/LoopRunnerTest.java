package io.github.yangjjiso.redyagents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LoopRunnerTest {
    @Test
    void passesCompletedHistoryAndToolResultBackToModel() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        Model model = (cancellation, agent, messages) -> {
            int call = modelCalls.incrementAndGet();
            assertEquals("demo", agent.model());
            if (call == 1) {
                assertEquals(List.of(
                        new Message("user", "earlier"),
                        new Message("assistant", "reply"),
                        new Message("user", "now")), messages);
                return Decision.toolCall("calling echo",
                        new ToolCall("echo", "hello".getBytes(StandardCharsets.UTF_8)));
            }
            assertEquals(2, call);
            assertEquals(5, messages.size());
            assertEquals(new Message("assistant", "calling echo"), messages.get(3));
            assertEquals(new Message("tool", "hello", "echo"), messages.get(4));
            return Decision.finalMessage("done");
        };
        Tool echo = (cancellation, arguments) -> new String(arguments, StandardCharsets.UTF_8);
        LoopRunner runner = new LoopRunner(model, Map.of("echo", echo), 4);
        AgentConfig agent = new AgentConfig("test", "demo", "Be concise.");
        Session session = new Session("sess_test", agent, "idle", null, Instant.now(), Instant.now());
        List<String> events = new ArrayList<>();

        String result = runner.run(new CancellationToken(), session,
                List.of(new Message("user", "earlier"), new Message("assistant", "reply")),
                "now", (type, data) -> events.add(type));

        assertEquals("done", result);
        assertEquals(2, modelCalls.get());
        assertEquals(List.of("tool.call.started", "tool.call.completed", "message.delta"), events);
    }

    @Test
    void enforcesContextBudgetBeforeEachModelStep() throws Exception {
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            history.add(new Message("user", "old question " + i + " ".repeat(30)));
            history.add(new Message("assistant", "old answer " + i + " ".repeat(30)));
        }
        AtomicInteger calls = new AtomicInteger();
        ContextWindow window = new ContextWindow();
        AgentConfig agent = new AgentConfig("test", "demo", "Be concise.", 1024, 128);
        Model model = (cancellation, config, messages) -> {
            assertTrue(window.fit(messages, config.instructions(), config.promptBudgetTokens())
                    .estimatedTokens() <= config.promptBudgetTokens());
            if (calls.incrementAndGet() == 1) {
                assertEquals(new Message("user", "now"), messages.get(messages.size() - 1));
                return Decision.toolCall("echo", new ToolCall("echo", "ok".getBytes(StandardCharsets.UTF_8)));
            }
            assertEquals(new Message("tool", "ok", "echo"), messages.get(messages.size() - 1));
            return Decision.finalMessage("done");
        };
        Session session = new Session("sess_test", agent, "idle", null, Instant.now(), Instant.now());
        List<String> events = new ArrayList<>();
        LoopRunner runner = new LoopRunner(model,
                Map.of("echo", (cancellation, arguments) -> new String(arguments, StandardCharsets.UTF_8)), 4);

        assertEquals("done", runner.run(new CancellationToken(), session, history, "now",
                (type, data) -> events.add(type)));
        assertEquals(2, calls.get());
        assertTrue(events.contains("context.compacted"));
    }
}
