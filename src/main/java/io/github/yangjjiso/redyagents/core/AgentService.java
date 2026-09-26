package io.github.yangjjiso.redyagents.core;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import java.util.HexFormat;

/** In-memory session state, turn execution, and replayable per-session events. */
public final class AgentService implements AutoCloseable {
    private final Object lock = new Object();
    private final Map<String, SessionState> sessions = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Runner runner;
    private final ExecutorService turnExecutor = Executors.newCachedThreadPool(daemonThreads("redy-turn-"));
    private final ExecutorService eventExecutor = Executors.newCachedThreadPool(daemonThreads("redy-events-"));
    private boolean closed;

    public AgentService() {
        this(new LoopRunner(new DemoModel()));
    }

    public AgentService(Runner runner) {
        if (runner == null) {
            throw new IllegalArgumentException("runner is required");
        }
        this.runner = runner;
    }

    public Session createSession(AgentConfig agent) {
        if (agent == null || blank(agent.name()) || blank(agent.model())
                || agent.contextWindowTokens() <= AgentConfig.PROMPT_SAFETY_MARGIN_TOKENS
                || agent.maxOutputTokens() <= 0 || agent.promptBudgetTokens() <= 0) {
            throw invalid();
        }
        Instant now = Instant.now();
        Session session = new Session(newId("sess_"), agent, "idle", null, now, now);
        synchronized (lock) {
            requireOpen();
            sessions.put(session.id(), new SessionState(session));
        }
        return session;
    }

    public Session getSession(String sessionId) {
        synchronized (lock) {
            return getState(sessionId).session;
        }
    }

    public Turn startTurn(String sessionId, String input) {
        if (blank(input)) {
            throw invalid();
        }
        Session runnerSession;
        List<Message> history;
        CancellationToken cancellation = new CancellationToken();
        Turn turn;
        synchronized (lock) {
            requireOpen();
            SessionState state = getState(sessionId);
            if (state.session.activeTurnId() != null) {
                throw conflict();
            }
            Instant now = Instant.now();
            turn = new Turn(newId("turn_"), sessionId, input, "running", "", "", now, null);
            state.turns.put(turn.id(), turn);
            state.cancellation = cancellation;
            state.session = new Session(state.session.id(), state.session.agent(), "running",
                    turn.id(), state.session.createdAt(), now);
            appendEvent(state, "turn.started", turn.id(), null);
            runnerSession = state.session;
            history = state.context;
        }
        List<Message> completedHistory = List.copyOf(history);
        try {
            turnExecutor.execute(() -> runTurn(cancellation, runnerSession, completedHistory, turn));
        } catch (RuntimeException rejected) {
            finishTurn(turn, cancellation, "failed", "", "worker unavailable", null);
            throw rejected;
        }
        return turn;
    }

    public Turn getTurn(String sessionId, String turnId) {
        synchronized (lock) {
            SessionState state = getState(sessionId);
            Turn turn = state.turns.get(turnId);
            if (turn == null) {
                throw notFound();
            }
            return turn;
        }
    }

    public Turn cancelTurn(String sessionId, String turnId) {
        CancellationToken cancellation;
        Turn cancelling;
        synchronized (lock) {
            SessionState state = getState(sessionId);
            Turn current = state.turns.get(turnId);
            if (current == null) {
                throw notFound();
            }
            if (!"running".equals(current.status())) {
                throw conflict();
            }
            cancelling = new Turn(current.id(), current.sessionId(), current.input(), "cancelling",
                    current.output(), current.error(), current.createdAt(), null);
            state.turns.put(turnId, cancelling);
            state.session = new Session(state.session.id(), state.session.agent(), state.session.status(),
                    state.session.activeTurnId(), state.session.createdAt(), Instant.now());
            cancellation = state.cancellation;
            appendEvent(state, "turn.cancelling", turnId, null);
        }
        cancellation.cancel();
        return cancelling;
    }

    /** Returns an immutable snapshot of events with sequence strictly greater than after. */
    public List<Event> eventsAfter(String sessionId, long after) {
        if (after < 0) {
            throw invalid();
        }
        synchronized (lock) {
            SessionState state = getState(sessionId);
            List<Event> replay = new ArrayList<>();
            for (Event event : state.events) {
                if (event.sequence() > after) {
                    replay.add(event);
                }
            }
            return List.copyOf(replay);
        }
    }

    /** Registers the listener with its replay cursor atomically; callbacks run outside the service lock. */
    public EventSubscription subscribe(String sessionId, long after, Consumer<Event> listener) {
        if (after < 0 || listener == null) {
            throw invalid();
        }
        Subscription subscription;
        synchronized (lock) {
            requireOpen();
            SessionState state = getState(sessionId);
            subscription = new Subscription(state, listener, after);
            state.subscriptions.add(subscription);
        }
        try {
            eventExecutor.execute(subscription::dispatch);
        } catch (RuntimeException e) {
            subscription.close();
            throw e;
        }
        return subscription;
    }

    private void runTurn(CancellationToken cancellation, Session session, List<Message> history, Turn turn) {
        cancellation.attachWorker();
        try {
            cancellation.throwIfCancelled();
            RunResult result = runner.runWithContext(cancellation, session, history, turn.input(),
                    (type, data) -> emitTurnEvent(turn, type, data));
            finishTurn(turn, cancellation, "completed", result.output(), "", result.context());
        } catch (Throwable failure) {
            if (cancellation.isCancelled() || failure instanceof CancellationException) {
                finishTurn(turn, cancellation, "cancelled", "", "", null);
            } else {
                String message = failure.getMessage();
                finishTurn(turn, cancellation, "failed", "",
                        message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message, null);
            }
        } finally {
            cancellation.detachWorker();
            Thread.interrupted();
        }
    }

    private void emitTurnEvent(Turn turn, String type, Map<String, Object> data) {
        synchronized (lock) {
            SessionState state = sessions.get(turn.sessionId());
            if (state != null) {
                Turn current = state.turns.get(turn.id());
                if (current != null && "running".equals(current.status())) {
                    appendEvent(state, type, turn.id(), data);
                }
            }
        }
    }

    private void finishTurn(Turn turn, CancellationToken cancellation, String status, String output,
                            String error, List<Message> context) {
        synchronized (lock) {
            SessionState state = sessions.get(turn.sessionId());
            if (state == null) {
                return;
            }
            Turn current = state.turns.get(turn.id());
            if (current == null || !("running".equals(current.status()) || "cancelling".equals(current.status()))) {
                return;
            }
            if ("cancelling".equals(current.status()) || cancellation.isCancelled()) {
                status = "cancelled";
                output = "";
                error = "";
            }
            Instant now = Instant.now();
            state.turns.put(turn.id(), new Turn(current.id(), current.sessionId(), current.input(),
                    status, output, error, current.createdAt(), now));
            if ("completed".equals(status) && context != null) {
                state.context = List.copyOf(context);
            }
            state.session = new Session(state.session.id(), state.session.agent(), "idle", null,
                    state.session.createdAt(), now);
            state.cancellation = null;
            appendEvent(state, "turn." + status, turn.id(), null);
        }
    }

    private void appendEvent(SessionState state, String type, String turnId, Map<String, Object> data) {
        Event event = new Event(state.events.size() + 1L, type, state.session.id(), turnId, Instant.now(), data);
        state.events.add(event);
        lock.notifyAll();
    }

    private SessionState getState(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            throw notFound();
        }
        return state;
    }

    private String newId(String prefix) {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return prefix + HexFormat.of().formatHex(bytes);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("service is closed");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static AgentException invalid() {
        return new AgentException(AgentException.Reason.INVALID, "invalid request");
    }

    private static AgentException notFound() {
        return new AgentException(AgentException.Reason.NOT_FOUND, "resource not found");
    }

    private static AgentException conflict() {
        return new AgentException(AgentException.Reason.CONFLICT, "session already has an active turn");
    }

    private static ThreadFactory daemonThreads(String prefix) {
        return new ThreadFactory() {
            private int nextId;

            @Override
            public synchronized Thread newThread(Runnable task) {
                Thread thread = new Thread(task, prefix + ++nextId);
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    @Override
    public void close() {
        List<Subscription> subscriptions = new ArrayList<>();
        List<CancellationToken> cancellations = new ArrayList<>();
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            for (SessionState state : sessions.values()) {
                subscriptions.addAll(state.subscriptions);
                if (state.cancellation != null) {
                    cancellations.add(state.cancellation);
                }
            }
        }
        for (Subscription subscription : subscriptions) {
            subscription.close();
        }
        for (CancellationToken cancellation : cancellations) {
            cancellation.cancel();
        }
        turnExecutor.shutdownNow();
        eventExecutor.shutdownNow();
    }

    private static final class SessionState {
        private Session session;
        private final Map<String, Turn> turns = new LinkedHashMap<>();
        private List<Message> context = List.of();
        private final List<Event> events = new ArrayList<>();
        private final Set<Subscription> subscriptions = new HashSet<>();
        private CancellationToken cancellation;

        private SessionState(Session session) {
            this.session = session;
        }
    }

    private final class Subscription implements EventSubscription {
        private final SessionState state;
        private final Consumer<Event> listener;
        private long cursor;
        private volatile boolean stopped;
        private volatile Thread dispatcher;

        private Subscription(SessionState state, Consumer<Event> listener, long cursor) {
            this.state = state;
            this.listener = listener;
            this.cursor = cursor;
        }

        private void dispatch() {
            dispatcher = Thread.currentThread();
            try {
                while (!stopped) {
                    Event event;
                    synchronized (lock) {
                        while (!stopped && cursor >= state.events.size()) {
                            lock.wait();
                        }
                        if (stopped) {
                            return;
                        }
                        event = state.events.get((int) cursor);
                        cursor = event.sequence();
                    }
                    if (!stopped) {
                        listener.accept(event);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable ignored) {
                // A failed SSE writer should not affect turns or other subscribers.
            } finally {
                close();
            }
        }

        @Override
        public void close() {
            synchronized (lock) {
                if (stopped) {
                    return;
                }
                stopped = true;
                state.subscriptions.remove(this);
                lock.notifyAll();
            }
            Thread thread = dispatcher;
            if (thread != null && thread != Thread.currentThread()) {
                thread.interrupt();
            }
        }
    }
}
