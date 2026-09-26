package io.github.yangjjiso.redyagents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LoopRunnerTest {
    @Test
    void passesRegisteredToolNamesAndSchemasToToolAwareModelOnEveryStep() throws Exception {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("query"));
        Tool search = new Tool() {
            @Override
            public ToolDefinition definition(String registeredName) {
                return new ToolDefinition(registeredName, "Search the index", schema);
            }

            @Override
            public String execute(CancellationToken cancellation, byte[] arguments) {
                return "found";
            }
        };
        AtomicInteger calls = new AtomicInteger();
        Model model = new Model() {
            @Override
            public Decision next(CancellationToken cancellation, AgentConfig agent, List<Message> messages) {
                throw new AssertionError("tool-aware overload should be used");
            }

            @Override
            public Decision next(CancellationToken cancellation, AgentConfig agent, List<Message> messages,
                                 List<ToolDefinition> tools) {
                assertEquals(List.of("search", "simple"), tools.stream().map(ToolDefinition::name).toList());
                assertEquals("Search the index", tools.get(0).description());
                assertEquals(Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")),
                        "required", List.of("query")), tools.get(0).inputSchema());
                assertEquals(Map.of("type", "object", "additionalProperties", true),
                        tools.get(1).inputSchema());
                assertThrows(UnsupportedOperationException.class,
                        () -> tools.get(0).inputSchema().put("unexpected", true));
                return calls.incrementAndGet() == 1
                        ? Decision.toolCall("searching", new ToolCall("search", "{\"query\":\"x\"}"
                                .getBytes(StandardCharsets.UTF_8)))
                        : Decision.finalMessage("done");
            }
        };
        LoopRunner runner = new LoopRunner(model, Map.of("search", search,
                "simple", (cancellation, arguments) -> "unused"), 4);
        properties.put("injected", Map.of("type", "boolean"));
        Session session = new Session("sess_test", new AgentConfig("test", "demo", ""),
                "idle", null, Instant.now(), Instant.now());

        LoopProgress result = runner.advance(new CancellationToken(), session,
                runner.start(List.of(), "find x"), (type, data) -> {});

        assertEquals("done", result.output());
        assertEquals(2, calls.get());
    }

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
        Model model = new Model() {
            @Override
            public Decision next(CancellationToken cancellation, AgentConfig config, List<Message> messages) {
                throw new AssertionError("tool-aware overload should be used");
            }

            @Override
            public Decision next(CancellationToken cancellation, AgentConfig config, List<Message> messages,
                                 List<ToolDefinition> tools) {
                assertTrue(window.fit(messages, config.instructions(), tools, config.promptBudgetTokens())
                        .estimatedTokens() <= config.promptBudgetTokens());
                if (calls.incrementAndGet() == 1) {
                    assertEquals(new Message("user", "now"), messages.get(messages.size() - 1));
                    return Decision.toolCall("echo", new ToolCall("echo", "ok".getBytes(StandardCharsets.UTF_8)));
                }
                assertEquals(new Message("tool", "ok", "echo"), messages.get(messages.size() - 1));
                return Decision.finalMessage("done");
            }
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
