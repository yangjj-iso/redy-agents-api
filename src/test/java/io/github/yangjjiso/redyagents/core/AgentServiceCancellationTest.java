package io.github.yangjjiso.redyagents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class AgentServiceCancellationTest {
    @Test
    @Timeout(10)
    void cancellationKeepsSessionBusyUntilRunnerActuallyExits() throws Exception {
        CountDownLatch enteredRunner = new CountDownLatch(1);
        CountDownLatch releaseRunner = new CountDownLatch(1);
        CountDownLatch cancelledEvent = new CountDownLatch(1);
        Runner runner = (cancellation, session, history, input, emit) -> {
            enteredRunner.countDown();
            boolean released = false;
            while (!released) {
                try {
                    releaseRunner.await();
                    released = true;
                } catch (InterruptedException ignored) {
                    // Simulate a worker that needs time to finish after cancellation.
                }
            }
            cancellation.throwIfCancelled();
            return "unexpected";
        };

        try (AgentService service = new AgentService(runner)) {
            Session session = service.createSession(new AgentConfig("test", "demo", ""));
            try (EventSubscription ignored = service.subscribe(session.id(), 0,
                    event -> {
                        if ("turn.cancelled".equals(event.type())) {
                            cancelledEvent.countDown();
                        }
                    })) {
                Turn turn = service.startTurn(session.id(), "first");
                try {
                    assertTrue(enteredRunner.await(2, TimeUnit.SECONDS), "runner did not start");
                    assertEquals("cancelling", service.cancelTurn(session.id(), turn.id()).status());
                    assertEquals("in_progress", service.getSession(session.id()).status());
                    assertEquals("cancelling", service.getTurn(session.id(), turn.id()).status());
                    AgentException conflict = assertThrows(AgentException.class,
                            () -> service.startTurn(session.id(), "second"));
                    assertEquals(AgentException.Reason.CONFLICT, conflict.reason());
                } finally {
                    releaseRunner.countDown();
                }

                assertTrue(cancelledEvent.await(2, TimeUnit.SECONDS), "cancelled event was not emitted");
                assertEquals("cancelled", service.getTurn(session.id(), turn.id()).status());
                assertEquals("idle", service.getSession(session.id()).status());
                List<String> types = service.eventsAfter(session.id(), 1)
                        .stream().map(Event::type).toList();
                assertTrue(types.contains("turn.cancelling"));
                assertTrue(types.contains("turn.cancelled"));
            }
        }
    }
}
