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

class ToolReliabilityTest {
    @Test
    void recoverableToolFailureLetsModelCorrectArguments() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        Model model = (cancellation, agent, messages) -> switch (modelCalls.incrementAndGet()) {
            case 1 -> Decision.toolCall("lookup", call("lookup", "bad"));
            case 2 -> {
                assertEquals("tool", last(messages).role());
                assertTrue(last(messages).content().contains("unknown customer"));
                yield Decision.toolCall("lookup", call("lookup", "good"));
            }
            default -> {
                assertEquals("found", last(messages).content());
                yield Decision.finalMessage("done");
            }
        };
        Tool tool = (cancellation, args) -> {
            executions.incrementAndGet();
            if (text(args).equals("bad")) {
                throw new ToolFailure("unknown customer", false);
            }
            return "found";
        };
        List<String> events = new ArrayList<>();

        assertEquals("done", new LoopRunner(model, Map.of("lookup", tool), 5)
                .run(new CancellationToken(), session(), List.of(), "find customer",
                        (type, data) -> events.add(type)));
        assertEquals(2, executions.get());
        assertTrue(events.contains("tool.call.failed"));
        assertTrue(events.contains("tool.call.completed"));
    }

    @Test
    void repeatedNonIdempotentFailureIsBlockedAndModelCanRecover() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        Model model = (cancellation, agent, messages) -> {
            if (modelCalls.incrementAndGet() <= 2) {
                return Decision.toolCall("charge", call("charge", "same"));
            }
            assertTrue(last(messages).content().contains("is blocked"));
            return Decision.finalMessage("need a different action");
        };
        Tool charge = (cancellation, args) -> {
            executions.incrementAndGet();
            throw new ToolFailure("payment state unknown", true);
        };

        assertEquals("need a different action", new LoopRunner(model, Map.of("charge", charge), 5)
                .run(new CancellationToken(), session(), List.of(), "pay", (type, data) -> {}));
        assertEquals(1, executions.get());
    }

    @Test
    void idempotentRetryableFailureCanBeRetriedOnce() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        Model model = (cancellation, agent, messages) -> modelCalls.incrementAndGet() <= 2
                ? Decision.toolCall("read", call("read", "same"))
                : Decision.finalMessage("done");
        Tool read = new Tool() {
            @Override
            public String execute(CancellationToken cancellation, byte[] arguments) throws Exception {
                if (executions.incrementAndGet() == 1) {
                    throw new ToolFailure("temporary unavailable", true);
                }
                return "ready";
            }

            @Override
            public boolean isIdempotent() {
                return true;
            }
        };

        assertEquals("done", new LoopRunner(model, Map.of("read", read), 5)
                .run(new CancellationToken(), session(), List.of(), "read", (type, data) -> {}));
        assertEquals(2, executions.get());
    }

    @Test
    void validationRejectsTechnicallySuccessfulButWrongResult() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        Model model = (cancellation, agent, messages) -> switch (calls.incrementAndGet()) {
            case 1 -> Decision.toolCall("lookup", call("lookup", "wrong"));
            case 2 -> {
                assertTrue(last(messages).content().contains("customer ID mismatch"));
                yield Decision.toolCall("lookup", call("lookup", "right"));
            }
            default -> Decision.finalMessage("done");
        };
        Tool lookup = new Tool() {
            @Override
            public String execute(CancellationToken cancellation, byte[] arguments) {
                executions.incrementAndGet();
                return text(arguments).equals("wrong") ? "found: someone else" : "found: right customer";
            }

            @Override
            public void validateResult(byte[] arguments, String result) throws ToolFailure {
                if (!result.contains(text(arguments))) {
                    throw new ToolFailure("customer ID mismatch", false);
                }
            }
        };
        List<String> events = new ArrayList<>();

        assertEquals("done", new LoopRunner(model, Map.of("lookup", lookup), 5)
                .run(new CancellationToken(), session(), List.of(), "lookup",
                        (type, data) -> events.add(type)));
        assertEquals(2, executions.get());
        assertTrue(events.contains("tool.call.failed"));
    }

    @Test
    void repeatedCallIdReplaysResultWithoutExecutingTool() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        ToolCall sameCall = new ToolCall("echo", "same".getBytes(StandardCharsets.UTF_8), "call_1");
        Model model = (cancellation, agent, messages) -> modelCalls.incrementAndGet() <= 2
                ? Decision.toolCall("echo", sameCall)
                : Decision.finalMessage("done");
        Tool echo = (cancellation, args) -> {
            executions.incrementAndGet();
            return text(args);
        };
        List<String> events = new ArrayList<>();

        assertEquals("done", new LoopRunner(model, Map.of("echo", echo), 5)
                .run(new CancellationToken(), session(), List.of(), "echo",
                        (type, data) -> events.add(type)));
        assertEquals(1, executions.get());
        assertTrue(events.contains("tool.call.replayed"));
    }

    private static Message last(List<Message> messages) {
        return messages.get(messages.size() - 1);
    }

    private static ToolCall call(String name, String arguments) {
        return new ToolCall(name, arguments.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Session session() {
        AgentConfig agent = new AgentConfig("test", "demo", "");
        return new Session("sess_test", agent, "idle", null, Instant.now(), Instant.now());
    }
}
