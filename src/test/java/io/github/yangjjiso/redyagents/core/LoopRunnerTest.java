package io.github.yangjjiso.redyagents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
