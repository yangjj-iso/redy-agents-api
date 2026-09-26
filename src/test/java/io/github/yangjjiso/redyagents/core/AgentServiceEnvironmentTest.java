package io.github.yangjjiso.redyagents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class AgentServiceEnvironmentTest {
    @TempDir
    Path directory;

    private static final AgentConfig AGENT = new AgentConfig("environment", "demo", "");

    @Test
    @Timeout(10)
    void cubeEnvironmentSurvivesTurnsAndRestartWithoutReprovisioning() throws Exception {
        Path snapshots = directory.resolve("sessions");
        FakeProvisioner provider = new FakeProvisioner();
        String sessionId;
        try (AgentService service = new AgentService(new LoopRunner(new DemoModel()),
                new FileSessionStore(snapshots), provider)) {
            Session session = service.createSession(AGENT, null,
                    new SessionEnvironment("cube", null, "template_1", null));
            sessionId = session.id();
            assertEquals("cube_1", session.environment().sandboxId());
            assertEquals("template_1", session.environment().templateId());
            assertEquals("active", session.environment().status());
            assertEquals(1, provider.creates.get());

            Turn turn = service.startTurn(sessionId, "hello");
            awaitTurn(service, sessionId, turn.id());
            assertEquals("cube_1", service.getSession(sessionId).environment().sandboxId());
        }
        try (AgentService recovered = new AgentService(new LoopRunner(new DemoModel()),
                new FileSessionStore(snapshots), provider)) {
            assertEquals(1, provider.creates.get(), "restore must not create a second sandbox");
            assertEquals("cube_1", recovered.getSession(sessionId).environment().sandboxId());
            assertEquals("active", recovered.getSession(sessionId).environment().status());
        }
    }

    @Test
    @Timeout(10)
    void pauseResumeAndKillGateTurnsAndKeepTheSessionHistory() throws Exception {
        FakeProvisioner provider = new FakeProvisioner();
        try (AgentService service = new AgentService(new LoopRunner(new DemoModel()),
                new FileSessionStore(directory.resolve("lifecycle")), provider)) {
            String id = service.createSession(AGENT, null,
                    new SessionEnvironment("cube", null, null, null)).id();
            assertEquals("paused", service.pauseEnvironment(id).environment().status());
            assertEquals(1, provider.pauses.get());
            assertThrows(AgentException.class, () -> service.startTurn(id, "blocked while paused"));
            assertEquals("active", service.resumeEnvironment(id).environment().status());
            assertEquals(1, provider.resumes.get());
            Turn turn = service.startTurn(id, "allowed");
            awaitTurn(service, id, turn.id());
            assertEquals("killed", service.killEnvironment(id).environment().status());
            assertEquals(1, provider.kills.get());
            assertThrows(AgentException.class, () -> service.startTurn(id, "blocked after kill"));
            assertEquals("completed", service.getTurn(id, turn.id()).status());

            String another = service.createSession(AGENT, null,
                    new SessionEnvironment("cube", null, null, null)).id();
            service.pauseEnvironment(another);
            assertEquals("killed", service.killEnvironment(another).environment().status());
        }
    }

    @Test
    @Timeout(10)
    void durablePauseIntentBlocksTurnsAndCanBeRetriedAfterPersistenceFailure() throws Exception {
        Path snapshots = directory.resolve("failed-commit");
        FailingStore store = new FailingStore(new FileSessionStore(snapshots));
        FakeProvisioner provider = new FakeProvisioner();
        String id;
        try (AgentService service = new AgentService(new LoopRunner(new DemoModel()), store, provider)) {
            id = service.createSession(AGENT, null,
                    new SessionEnvironment("cube", null, null, null)).id();
            store.failAfterSuccessfulSaves(1);
            assertThrows(IllegalStateException.class, () -> service.pauseEnvironment(id));
            assertEquals(1, provider.pauses.get(), "remote pause happened before final save failed");
            assertEquals("pausing", service.getSession(id).environment().status());
            assertThrows(AgentException.class, () -> service.startTurn(id, "must stay blocked"));
        }
        try (AgentService recovered = new AgentService(new LoopRunner(new DemoModel()),
                new FileSessionStore(snapshots), provider)) {
            assertEquals("pausing", recovered.getSession(id).environment().status());
            assertEquals(1, provider.creates.get());
            assertEquals("paused", recovered.pauseEnvironment(id).environment().status());
            assertEquals(2, provider.pauses.get(), "retry is an idempotent provider action");
            assertEquals("active", recovered.resumeEnvironment(id).environment().status());
        }
    }

    @Test
    @Timeout(10)
    void failedSessionPersistenceReleasesNewSandbox() throws Exception {
        FailingStore store = new FailingStore(new FileSessionStore(directory.resolve("create")));
        store.failAfterSuccessfulSaves(0);
        FakeProvisioner provider = new FakeProvisioner();
        try (AgentService service = new AgentService(new LoopRunner(new DemoModel()), store, provider)) {
            assertThrows(IllegalStateException.class, () -> service.createSession(AGENT, null,
                    new SessionEnvironment("cube", null, null, null)));
            assertEquals(1, provider.creates.get());
            assertEquals(1, provider.kills.get());
            assertTrue(store.loadAll().isEmpty());
        }
    }

    @Test
    @Timeout(10)
    void providerFailureKeepsDurableIntentAndUsesSafeUnavailableError() throws Exception {
        FakeProvisioner provider = new FakeProvisioner();
        try (AgentService service = new AgentService(new LoopRunner(new DemoModel()),
                new FileSessionStore(directory.resolve("provider-failure")), provider)) {
            String id = service.createSession(AGENT, null,
                    new SessionEnvironment("cube", null, null, null)).id();
            provider.failPause = true;
            AgentException error = assertThrows(AgentException.class,
                    () -> service.pauseEnvironment(id));
            assertEquals(AgentException.Reason.UNAVAILABLE, error.reason());
            assertTrue(!error.getMessage().contains("secret"));
            assertEquals("pausing", service.getSession(id).environment().status());
            assertThrows(AgentException.class, () -> service.startTurn(id, "blocked"));
            provider.failPause = false;
            assertEquals("paused", service.pauseEnvironment(id).environment().status());
        }
    }

    private static Turn awaitTurn(AgentService service, String sessionId, String turnId)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            Turn turn = service.getTurn(sessionId, turnId);
            if ("completed".equals(turn.status()) || "failed".equals(turn.status())) {
                assertEquals("completed", turn.status(), turn.error());
                return turn;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("turn did not complete");
    }

    private static final class FakeProvisioner implements SandboxProvisioner {
        private final AtomicInteger creates = new AtomicInteger();
        private final AtomicInteger pauses = new AtomicInteger();
        private final AtomicInteger resumes = new AtomicInteger();
        private final AtomicInteger kills = new AtomicInteger();
        private boolean failPause;

        @Override
        public String create(String templateId) {
            return "cube_" + creates.incrementAndGet();
        }

        @Override
        public void pause(String sandboxId) {
            assertNotNull(sandboxId);
            pauses.incrementAndGet();
            if (failPause) {
                throw new IllegalStateException("secret provider response body");
            }
        }

        @Override
        public void resume(String sandboxId) {
            assertNotNull(sandboxId);
            resumes.incrementAndGet();
        }

        @Override
        public void kill(String sandboxId) {
            assertNotNull(sandboxId);
            kills.incrementAndGet();
        }
    }

    private static final class FailingStore implements SessionStore {
        private final SessionStore delegate;
        private int savesUntilFailure = -1;

        private FailingStore(SessionStore delegate) {
            this.delegate = delegate;
        }

        void failAfterSuccessfulSaves(int count) {
            savesUntilFailure = count;
        }

        @Override
        public void save(String sessionId, byte[] snapshot) throws IOException {
            if (savesUntilFailure == 0) {
                savesUntilFailure = -1;
                throw new IOException("injected save failure");
            }
            if (savesUntilFailure > 0) {
                savesUntilFailure--;
            }
            delegate.save(sessionId, snapshot);
        }

        @Override
        public Map<String, byte[]> loadAll() throws IOException {
            return delegate.loadAll();
        }
    }
}
