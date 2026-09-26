package io.github.yangjjiso.redyagents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class AgentServiceContextTest {
    @Test
    @Timeout(5)
    void savesModelFacingContextForNextTurn() throws Exception {
        List<Message> compacted = List.of(
                new Message("summary", "earlier work"),
                new Message("user", "first"),
                new Message("assistant", "done"));
        AtomicInteger calls = new AtomicInteger();
        Runner runner = new Runner() {
            @Override
            public String run(CancellationToken cancellation, Session session, List<Message> history,
                              String input, EventEmitter emit) {
                throw new AssertionError("AgentService should use runWithContext");
            }

            @Override
            public RunResult runWithContext(CancellationToken cancellation, Session session,
                                            List<Message> history, String input, EventEmitter emit) {
                if (calls.incrementAndGet() == 1) {
                    assertEquals(List.of(), history);
                    return new RunResult("done", compacted);
                }
                assertEquals(compacted, history);
                return new RunResult("again", List.of(new Message("summary", "updated work")));
            }
        };

        try (AgentService service = new AgentService(runner)) {
            Session session = service.createSession(new AgentConfig("test", "demo", ""));
            Turn first = service.startTurn(session.id(), "first");
            assertEquals("completed", awaitTerminal(service, session.id(), first.id()).status());
            Turn second = service.startTurn(session.id(), "second");
            assertEquals("completed", awaitTerminal(service, session.id(), second.id()).status());
            assertEquals(2, calls.get());
        }
    }

    private static Turn awaitTerminal(AgentService service, String sessionId, String turnId)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            Turn turn = service.getTurn(sessionId, turnId);
            if (!"running".equals(turn.status()) && !"cancelling".equals(turn.status())) {
                return turn;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("turn did not finish");
    }
}
