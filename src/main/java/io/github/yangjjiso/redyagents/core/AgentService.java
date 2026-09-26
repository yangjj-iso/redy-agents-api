package io.github.yangjjiso.redyagents.core;

import java.security.SecureRandom;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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
import tools.jackson.databind.ObjectMapper;

/** Session state, turn execution, and replayable per-session events. */
public final class AgentService implements AutoCloseable {
    private final Object lock = new Object();
    private final Map<String, SessionState> sessions = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Runner runner;
    private final SessionStore store;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService turnExecutor = Executors.newCachedThreadPool(daemonThreads("redy-turn-"));
    private final ExecutorService eventExecutor = Executors.newCachedThreadPool(daemonThreads("redy-events-"));
    private boolean closed;

    public AgentService() {
        this(new LoopRunner(new DemoModel()), null);
    }

    public AgentService(Runner runner) {
        this(runner, null);
    }

    public AgentService(Runner runner, SessionStore store) {
        if (runner == null) {
            throw new IllegalArgumentException("runner is required");
        }
        this.runner = runner;
        this.store = store;
        restore();
    }

    public Session createSession(AgentConfig agent) {
        return createSession(agent, null);
    }

    public Session createSession(AgentConfig agent, String initialInput) {
        if (agent == null || blank(agent.name()) || blank(agent.model())
                || agent.contextWindowTokens() <= AgentConfig.PROMPT_SAFETY_MARGIN_TOKENS
                || agent.maxOutputTokens() <= 0 || agent.promptBudgetTokens() <= 0) {
            throw invalid();
        }
        Instant now = Instant.now();
        Session session = new Session(newId("sess_"), agent, "idle", null, now, now);
        synchronized (lock) {
            requireOpen();
            SessionState state = new SessionState(session);
            sessions.put(session.id(), state);
            state.events.add(newEvent(state, "agent.session.created", null,
                    Map.of("session", state.session)));
            try {
                persist(state);
            } catch (RuntimeException failure) {
                sessions.remove(session.id());
                throw failure;
            }
            lock.notifyAll();
        }
        if (initialInput != null) {
            startTurn(session.id(), initialInput);
            return getSession(session.id());
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
        LoopCheckpoint checkpoint;
        CancellationToken cancellation = new CancellationToken();
        Turn turn;
        synchronized (lock) {
            requireOpen();
            SessionState state = getState(sessionId);
            if (state.session.activeTurnId() != null) {
                throw conflict();
            }
            Instant now = Instant.now();
            turn = new Turn(newId("turn_"), sessionId, input, "in_progress", "", "", now, null);
            Session previousSession = state.session;
            List<Message> previousExecution = state.executionMessages;
            LoopCheckpoint previousCheckpoint = state.checkpoint;
            CancellationToken previousCancellation = state.cancellation;
            boolean previousSafeToResume = state.safeToResume;
            int previousEvents = state.events.size();
            int previousItems = state.items.size();
            LoopCheckpoint nextCheckpoint = null;
            if (runner instanceof LoopRunner loop) {
                nextCheckpoint = loop.start(state.context, input);
            }
            state.turns.put(turn.id(), turn);
            state.cancellation = cancellation;
            state.session = new Session(state.session.id(), state.session.agent(), "in_progress",
                    turn.id(), state.session.createdAt(), now);
            state.executionMessages = new ArrayList<>(state.context);
            Message user = new Message("user", input);
            state.executionMessages.add(user);
            state.checkpoint = nextCheckpoint;
            state.safeToResume = nextCheckpoint != null;
            state.events.add(newEvent(state, "agent.session.in_progress", turn.id(),
                    Map.of("session", state.session)));
            state.events.add(newEvent(state, "agent.session.turn.created", turn.id(),
                    Map.of("turn", turn)));
            state.events.add(newEvent(state, "agent.session.turn.in_progress", turn.id(),
                    Map.of("turn", turn)));
            state.events.add(newEvent(state, "turn.started", turn.id(), null));
            SessionItem item = newItem(turn.id(), user);
            state.items.add(item);
            state.events.add(newEvent(state, "agent.session.item.created", turn.id(),
                    Map.of("item_id", item.id(), "type", item.type())));
            try {
                persist(state);
            } catch (RuntimeException failure) {
                state.turns.remove(turn.id());
                state.session = previousSession;
                state.executionMessages = previousExecution;
                state.checkpoint = previousCheckpoint;
                state.cancellation = previousCancellation;
                state.safeToResume = previousSafeToResume;
                truncate(state.events, previousEvents);
                truncate(state.items, previousItems);
                throw failure;
            }
            lock.notifyAll();
            runnerSession = state.session;
            history = state.context;
            checkpoint = state.checkpoint;
        }
        List<Message> completedHistory = List.copyOf(history);
        try {
            if (runner instanceof LoopRunner) {
                turnExecutor.execute(() -> runLoop(cancellation, runnerSession, checkpoint, turn));
            } else {
                turnExecutor.execute(() -> runTurn(cancellation, runnerSession, completedHistory, turn));
            }
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

    public List<Turn> listTurns(String sessionId) {
        synchronized (lock) {
            return List.copyOf(getState(sessionId).turns.values());
        }
    }

    public List<SessionItem> items(String sessionId) {
        synchronized (lock) {
            return List.copyOf(getState(sessionId).items);
        }
    }

    /** An idle input starts a turn; an active input is delivered at the next model boundary. */
    public Session submitMessage(String sessionId, String input) {
        if (blank(input)) {
            throw invalid();
        }
        synchronized (lock) {
            SessionState state = getState(sessionId);
            if (state.session.activeTurnId() == null && state.steering.isEmpty()) {
                startTurn(sessionId, input);
            } else {
                state.steering.add(input);
                try {
                    appendEvent(state, "agent.session.input.message", state.session.activeTurnId(),
                            Map.of("text", input));
                } catch (RuntimeException failure) {
                    state.steering.remove(state.steering.size() - 1);
                    throw failure;
                }
                if (state.session.activeTurnId() == null) {
                    startQueuedSteering(state);
                }
            }
            return state.session;
        }
    }

    /** Accept an application function result once and resume the waiting turn. */
    public Session submitToolResult(String sessionId, String turnId, String callId,
                                    boolean success, String output, String error) {
        if (blank(turnId) || blank(callId) || (success && !blank(error))
                || (!success && blank(error))) {
            throw invalid();
        }
        if (!(runner instanceof LoopRunner loop)) {
            throw conflict();
        }
        ToolResult result = new ToolResult(callId, success, output, error);
        String key = turnId + ":" + callId;
        LoopCheckpoint waitingCheckpoint;
        synchronized (lock) {
            SessionState state = getState(sessionId);
            ToolResult previous = state.toolResults.get(key);
            if (previous != null) {
                if (!previous.equals(result)) {
                    throw conflict();
                }
                return state.session;
            }
            if (state.resultInFlight || !"requires_action".equals(state.session.status())
                    || !turnId.equals(state.session.activeTurnId())
                    || state.checkpoint == null || state.checkpoint.pendingExternal() == null
                    || !callId.equals(state.checkpoint.pendingExternal().call().callId())) {
                throw conflict();
            }
            state.resultInFlight = true;
            waitingCheckpoint = state.checkpoint;
        }

        List<BufferedEffect> resumedEffects = new ArrayList<>();
        EventEmitter buffered = new EventEmitter() {
            @Override
            public void emit(String type, Map<String, Object> data) {
                resumedEffects.add(new BufferedEffect(type, data, null));
            }

            @Override
            public void record(Message message) {
                resumedEffects.add(new BufferedEffect(null, null, message));
            }
        };
        LoopCheckpoint checkpoint;
        try {
            checkpoint = loop.resumeExternal(waitingCheckpoint, result, buffered);
        } catch (RuntimeException | Error failure) {
            synchronized (lock) {
                getState(sessionId).resultInFlight = false;
                lock.notifyAll();
            }
            throw failure;
        }

        CancellationToken cancellation = new CancellationToken();
        Turn turn;
        Session runnerSession;
        synchronized (lock) {
            SessionState state = getState(sessionId);
            if (!"requires_action".equals(state.session.status())
                    || !turnId.equals(state.session.activeTurnId())
                    || state.checkpoint != waitingCheckpoint) {
                state.resultInFlight = false;
                throw conflict();
            }
            turn = state.turns.get(turnId);
            Session oldSession = state.session;
            LoopCheckpoint oldCheckpoint = state.checkpoint;
            CancellationToken oldCancellation = state.cancellation;
            boolean oldSafeToResume = state.safeToResume;
            int oldMessages = state.executionMessages.size();
            int oldItems = state.items.size();
            int oldEvents = state.events.size();
            state.toolResults.put(key, result);
            state.checkpoint = checkpoint;
            state.safeToResume = true;
            Instant now = Instant.now();
            state.turns.put(turnId, new Turn(turn.id(), turn.sessionId(), turn.input(),
                    "in_progress", turn.output(), turn.error(), turn.createdAt(), null));
            state.session = new Session(state.session.id(), state.session.agent(), "in_progress",
                    turnId, state.session.createdAt(), now);
            state.cancellation = cancellation;
            state.events.add(newEvent(state, "agent.session.in_progress", turnId,
                    Map.of("session", state.session)));
            state.events.add(newEvent(state, "agent.session.turn.in_progress", turnId,
                    Map.of("turn", state.turns.get(turnId))));
            for (BufferedEffect effect : resumedEffects) {
                if (effect.message() != null) {
                    state.executionMessages.add(effect.message());
                    SessionItem item = newItem(turnId, effect.message());
                    int outputIndex = outputItemCount(state, turnId);
                    state.items.add(item);
                    state.events.add(newEvent(state, "agent.session.item.created", turnId,
                            Map.of("item_id", item.id(), "type", item.type())));
                    if (!"user".equals(item.role())) {
                        appendOutputItemEvents(state, turnId, item, outputIndex);
                    }
                } else {
                    state.events.add(newEvent(state, effect.type(), turnId, effect.data()));
                }
            }
            state.events.add(newEvent(state, "agent.session.input.tool_result", turnId,
                    Map.of("call_id", callId, "success", success)));
            try {
                persist(state);
            } catch (RuntimeException failure) {
                state.toolResults.remove(key);
                state.checkpoint = oldCheckpoint;
                state.safeToResume = oldSafeToResume;
                state.session = oldSession;
                state.turns.put(turnId, turn);
                state.cancellation = oldCancellation;
                truncate(state.executionMessages, oldMessages);
                truncate(state.items, oldItems);
                truncate(state.events, oldEvents);
                state.resultInFlight = false;
                throw failure;
            }
            state.resultInFlight = false;
            lock.notifyAll();
            runnerSession = state.session;
        }
        LoopCheckpoint resumeFrom = checkpoint;
        try {
            turnExecutor.execute(() -> runLoop(cancellation, runnerSession, resumeFrom, turn));
        } catch (RuntimeException rejected) {
            finishTurn(turn, cancellation, "failed", "", "worker unavailable", null);
            throw rejected;
        }
        return getSession(sessionId);
    }

    private static <T> void truncate(List<T> values, int size) {
        values.subList(size, values.size()).clear();
    }

    public Session cancelActiveTurn(String sessionId) {
        synchronized (lock) {
            SessionState state = getState(sessionId);
            if (state.session.activeTurnId() == null) {
                throw conflict();
            }
            cancelTurn(sessionId, state.session.activeTurnId());
            return state.session;
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
            if (!"in_progress".equals(current.status()) && !"waiting".equals(current.status())) {
                throw conflict();
            }
            if ("waiting".equals(current.status())) {
                int oldMessages = state.executionMessages.size();
                int oldItems = state.items.size();
                int oldEvents = state.events.size();
                try {
                    if (state.checkpoint != null && state.checkpoint.pendingExternal() != null) {
                        ToolCall call = state.checkpoint.pendingExternal().call();
                        Message cancelledResult = new Message("tool", "Tool failure: call cancelled before result",
                                call.name(), call.callId(),
                                Base64.getEncoder().encodeToString(call.arguments()), false);
                        state.executionMessages.add(cancelledResult);
                        SessionItem item = newItem(turnId, cancelledResult);
                        int outputIndex = outputItemCount(state, turnId);
                        state.items.add(item);
                        state.events.add(newEvent(state, "agent.session.item.created", turnId,
                                Map.of("item_id", item.id(), "type", item.type())));
                        appendOutputItemEvents(state, turnId, item, outputIndex);
                    }
                    CancellationToken stopped = new CancellationToken();
                    stopped.cancel();
                    finishTurn(current, stopped, "cancelled", "", "", null);
                } catch (RuntimeException failure) {
                    truncate(state.executionMessages, oldMessages);
                    truncate(state.items, oldItems);
                    truncate(state.events, oldEvents);
                    throw failure;
                }
                return state.turns.get(turnId);
            }
            cancelling = new Turn(current.id(), current.sessionId(), current.input(), "cancelling",
                    current.output(), current.error(), current.createdAt(), null);
            Session oldSession = state.session;
            state.turns.put(turnId, cancelling);
            state.session = new Session(state.session.id(), state.session.agent(), state.session.status(),
                    state.session.activeTurnId(), state.session.createdAt(), Instant.now());
            cancellation = state.cancellation;
            try {
                appendEvent(state, "turn.cancelling", turnId, null);
            } catch (RuntimeException failure) {
                state.turns.put(turnId, current);
                state.session = oldSession;
                throw failure;
            }
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
            RunResult result = runner.runWithContext(cancellation, session, history, turn.input(), emitter(turn));
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

    private void runLoop(CancellationToken cancellation, Session session,
                         LoopCheckpoint checkpoint, Turn turn) {
        cancellation.attachWorker();
        try {
            cancellation.throwIfCancelled();
            synchronized (lock) {
                SessionState state = sessions.get(turn.sessionId());
                if (state != null && turn.id().equals(state.session.activeTurnId())
                        && state.safeToResume) {
                    state.safeToResume = false;
                    persist(state);
                }
            }
            LoopProgress progress = ((LoopRunner) runner).advance(
                    cancellation, session, checkpoint, emitter(turn));
            cancellation.throwIfCancelled();
            if (progress.isCompleted()) {
                finishTurn(turn, cancellation, "completed", progress.output(), "",
                        progress.checkpoint().messages());
            } else {
                pauseForFunction(turn, cancellation, progress);
            }
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

    private void pauseForFunction(Turn turn, CancellationToken cancellation, LoopProgress progress) {
        synchronized (lock) {
            SessionState state = sessions.get(turn.sessionId());
            if (state == null) {
                return;
            }
            Turn current = state.turns.get(turn.id());
            if (current == null) {
                return;
            }
            if ("cancelling".equals(current.status()) || cancellation.isCancelled()) {
                finishTurn(turn, cancellation, "cancelled", "", "", null);
                return;
            }
            if (!"in_progress".equals(current.status())) {
                return;
            }
            ToolCall call = progress.pendingCall();
            RequiredAction action = new RequiredAction("function_call", turn.id(), call.callId(),
                    call.name(), functionArguments(call));
            LoopCheckpoint oldCheckpoint = state.checkpoint;
            boolean oldSafeToResume = state.safeToResume;
            Session oldSession = state.session;
            CancellationToken oldCancellation = state.cancellation;
            state.checkpoint = progress.checkpoint();
            state.safeToResume = false;
            Instant now = Instant.now();
            state.turns.put(turn.id(), new Turn(current.id(), current.sessionId(), current.input(),
                    "waiting", "", "", current.createdAt(), null));
            state.session = new Session(state.session.id(), state.session.agent(), "requires_action",
                    turn.id(), state.session.createdAt(), now, List.of(action));
            state.cancellation = null;
            try {
                appendEvent(state, "agent.session.requires_action", turn.id(),
                        Map.of("session", state.session, "call_id", call.callId(), "name", call.name()));
            } catch (RuntimeException failure) {
                state.checkpoint = oldCheckpoint;
                state.safeToResume = oldSafeToResume;
                state.turns.put(turn.id(), current);
                state.session = oldSession;
                state.cancellation = oldCancellation;
                throw failure;
            }
        }
    }

    private Map<String, Object> functionArguments(ToolCall call) {
        try {
            Object parsed = json.readValue(call.arguments(), Object.class);
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("function arguments must be a JSON object");
            }
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("function argument keys must be strings");
                }
                values.put(key, entry.getValue());
            }
            return values;
        } catch (Exception e) {
            throw new IllegalArgumentException("function arguments must be a JSON object", e);
        }
    }

    private EventEmitter emitter(Turn turn) {
        return new EventEmitter() {
            @Override
            public void emit(String type, Map<String, Object> data) {
                emitTurnEvent(turn, type, data);
            }

            @Override
            public void record(Message message) {
                recordTurnMessage(turn, message);
            }

            @Override
            public List<String> drainSteering() {
                return drainTurnSteering(turn);
            }

            @Override
            public void markSteeringConsumed() {
                markTurnSteeringConsumed(turn);
            }
        };
    }

    private void recordTurnMessage(Turn turn, Message message) {
        synchronized (lock) {
            SessionState state = sessions.get(turn.sessionId());
            if (state == null) {
                return;
            }
            Turn current = state.turns.get(turn.id());
            if (current == null || !("in_progress".equals(current.status())
                    || "waiting".equals(current.status()))) {
                return;
            }
            state.executionMessages.add(message);
            try {
                appendItem(state, turn.id(), message);
            } catch (RuntimeException failure) {
                state.executionMessages.remove(state.executionMessages.size() - 1);
                throw failure;
            }
        }
    }

    private List<String> drainTurnSteering(Turn turn) {
        synchronized (lock) {
            SessionState state = sessions.get(turn.sessionId());
            if (state == null || state.steering.isEmpty()) {
                return List.of();
            }
            Turn current = state.turns.get(turn.id());
            if (current == null || !"in_progress".equals(current.status())
                    || state.cancellation == null || state.cancellation.isCancelled()) {
                return List.of();
            }
            int oldMessages = state.executionMessages.size();
            int oldItems = state.items.size();
            int oldEvents = state.events.size();
            int oldClaims = state.claimedSteering.size();
            List<String> drained = List.copyOf(state.steering);
            state.steering.clear();
            for (String text : drained) {
                Message message = new Message("user", text);
                state.claimedSteering.add(new SteeringClaim(text, state.executionMessages.size()));
                state.executionMessages.add(message);
                SessionItem item = newItem(turn.id(), message);
                state.items.add(item);
                state.events.add(newEvent(state, "agent.session.item.created", turn.id(),
                        Map.of("item_id", item.id(), "type", item.type())));
            }
            try {
                persist(state);
            } catch (RuntimeException failure) {
                state.steering.addAll(drained);
                truncate(state.executionMessages, oldMessages);
                truncate(state.items, oldItems);
                truncate(state.events, oldEvents);
                truncate(state.claimedSteering, oldClaims);
                throw failure;
            }
            lock.notifyAll();
            return drained;
        }
    }

    private void markTurnSteeringConsumed(Turn turn) {
        synchronized (lock) {
            SessionState state = sessions.get(turn.sessionId());
            if (state == null || state.claimedSteering.isEmpty()) {
                return;
            }
            Turn current = state.turns.get(turn.id());
            if (current == null || !"in_progress".equals(current.status())
                    || state.cancellation == null || state.cancellation.isCancelled()) {
                return;
            }
            List<SteeringClaim> previous = List.copyOf(state.claimedSteering);
            boolean changed = false;
            for (int i = 0; i < state.claimedSteering.size(); i++) {
                SteeringClaim claim = state.claimedSteering.get(i);
                if (!claim.consumed()) {
                    state.claimedSteering.set(i, new SteeringClaim(
                            claim.input(), claim.messageIndex(), true));
                    changed = true;
                }
            }
            if (!changed) {
                return;
            }
            try {
                persist(state);
            } catch (RuntimeException failure) {
                state.claimedSteering.clear();
                state.claimedSteering.addAll(previous);
                throw failure;
            }
        }
    }

    private void emitTurnEvent(Turn turn, String type, Map<String, Object> data) {
        synchronized (lock) {
            SessionState state = sessions.get(turn.sessionId());
            if (state != null) {
                Turn current = state.turns.get(turn.id());
                if (current != null && "in_progress".equals(current.status())) {
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
            if (current == null || !("in_progress".equals(current.status())
                    || "cancelling".equals(current.status()) || "waiting".equals(current.status()))) {
                return;
            }
            if ("cancelling".equals(current.status()) || cancellation.isCancelled()) {
                status = "cancelled";
                output = "";
                error = "";
            }
            Session oldSession = state.session;
            List<Message> oldContext = state.context;
            CancellationToken oldCancellation = state.cancellation;
            LoopCheckpoint oldCheckpoint = state.checkpoint;
            boolean oldSafeToResume = state.safeToResume;
            List<String> oldSteering = List.copyOf(state.steering);
            List<SteeringClaim> oldClaims = List.copyOf(state.claimedSteering);
            int oldMessages = state.executionMessages.size();
            int oldItems = state.items.size();
            int oldEvents = state.events.size();
            try {
                boolean completed = "completed".equals(status);
                if (!completed) {
                    pairUnfinishedToolCalls(state, turn.id(), status);
                    state.context = contextWithoutClaimedSteering(state);
                    requeueClaimedSteering(state);
                } else {
                    state.context = context == null
                            ? List.copyOf(state.executionMessages) : List.copyOf(context);
                    state.claimedSteering.clear();
                }
                if ("failed".equals(status) && !blank(error)) {
                    List<Message> recovered = new ArrayList<>(state.context);
                    recovered.add(new Message("summary", "Previous turn failed: " + error));
                    state.context = List.copyOf(recovered);
                }
                Instant now = Instant.now();
                Turn terminal = new Turn(current.id(), current.sessionId(), current.input(),
                        status, output, error, current.createdAt(), now);
                state.turns.put(turn.id(), terminal);
                state.session = new Session(state.session.id(), state.session.agent(), "idle", null,
                        state.session.createdAt(), now);
                state.cancellation = null;
                state.checkpoint = null;
                state.safeToResume = false;
                state.events.add(newEvent(state, "turn." + status, turn.id(), null));
                state.events.add(newEvent(state, "agent.session.turn." + status, turn.id(),
                        Map.of("turn", terminal)));
                state.events.add(newEvent(state, "agent.session.idle", turn.id(),
                        Map.of("session", state.session)));
                persist(state);
            } catch (RuntimeException failure) {
                state.turns.put(turn.id(), current);
                state.session = oldSession;
                state.context = oldContext;
                state.cancellation = oldCancellation;
                state.checkpoint = oldCheckpoint;
                state.safeToResume = oldSafeToResume;
                state.steering.clear();
                state.steering.addAll(oldSteering);
                state.claimedSteering.clear();
                state.claimedSteering.addAll(oldClaims);
                truncate(state.executionMessages, oldMessages);
                truncate(state.items, oldItems);
                truncate(state.events, oldEvents);
                throw failure;
            }
            lock.notifyAll();
            startQueuedSteering(state);
        }
    }

    private static List<Message> contextWithoutClaimedSteering(SessionState state) {
        Set<Integer> claimedIndices = new HashSet<>();
        for (SteeringClaim claim : state.claimedSteering) {
            if (!claim.consumed()) {
                claimedIndices.add(claim.messageIndex());
            }
        }
        List<Message> context = new ArrayList<>();
        for (int i = 0; i < state.executionMessages.size(); i++) {
            if (!claimedIndices.contains(i)) {
                context.add(state.executionMessages.get(i));
            }
        }
        return List.copyOf(context);
    }

    private static void requeueClaimedSteering(SessionState state) {
        if (state.claimedSteering.isEmpty()) {
            return;
        }
        List<String> reclaimed = new ArrayList<>(state.claimedSteering.size());
        for (SteeringClaim claim : state.claimedSteering) {
            if (!claim.consumed()) {
                reclaimed.add(claim.input());
            }
        }
        state.steering.addAll(0, reclaimed);
        state.claimedSteering.clear();
    }

    private void startQueuedSteering(SessionState state) {
        if (closed || state.steering.isEmpty()) {
            return;
        }
        String next = state.steering.remove(0);
        try {
            startTurn(state.session.id(), next);
        } catch (RuntimeException failure) {
            if (state.session.activeTurnId() == null) {
                state.steering.add(0, next);
                try {
                    persist(state);
                } catch (RuntimeException ignored) {
                    // The committed terminal snapshot still contains this pending input.
                }
                lock.notifyAll();
            }
        }
    }

    private void pairUnfinishedToolCalls(SessionState state, String turnId, String outcome) {
        List<SessionItem> unanswered = new ArrayList<>();
        for (SessionItem item : state.items) {
            if (!turnId.equals(item.turnId()) || item.callId() == null) {
                continue;
            }
            if ("function_call".equals(item.type())) {
                unanswered.add(item);
            } else if ("function_call_output".equals(item.type())) {
                for (int i = unanswered.size() - 1; i >= 0; i--) {
                    if (item.callId().equals(unanswered.get(i).callId())) {
                        unanswered.remove(i);
                        break;
                    }
                }
            }
        }
        for (SessionItem call : unanswered) {
            String feedback = "Tool failure: turn " + outcome + " before the function result was available";
            Message missing = new Message("tool", feedback, call.name(), call.callId(),
                    call.argumentsBase64(), false);
            state.executionMessages.add(missing);
            int outputIndex = outputItemCount(state, turnId);
            SessionItem item = newItem(turnId, missing);
            state.items.add(item);
            state.events.add(newEvent(state, "agent.session.item.created", turnId,
                    Map.of("item_id", item.id(), "type", item.type())));
            appendOutputItemEvents(state, turnId, item, outputIndex);
            state.events.add(newEvent(state, "tool.call.failed", turnId,
                    Map.of("name", call.name(), "call_id", call.callId(),
                            "error", feedback, "success", false)));
        }
    }

    private void appendEvent(SessionState state, String type, String turnId, Map<String, Object> data) {
        state.events.add(newEvent(state, type, turnId, data));
        try {
            persist(state);
        } catch (RuntimeException failure) {
            state.events.remove(state.events.size() - 1);
            throw failure;
        }
        lock.notifyAll();
    }

    private Event newEvent(SessionState state, String type, String turnId, Map<String, Object> data) {
        return new Event(state.events.size() + 1L, type, state.session.id(), turnId, Instant.now(), data);
    }

    private void appendItem(SessionState state, String turnId, Message message) {
        SessionItem item = newItem(turnId, message);
        int outputIndex = outputItemCount(state, turnId);
        int oldEvents = state.events.size();
        state.items.add(item);
        state.events.add(newEvent(state, "agent.session.item.created", turnId,
                Map.of("item_id", item.id(), "type", item.type())));
        if (!"user".equals(item.role())) {
            appendOutputItemEvents(state, turnId, item, outputIndex);
        }
        try {
            persist(state);
        } catch (RuntimeException failure) {
            state.items.remove(state.items.size() - 1);
            truncate(state.events, oldEvents);
            throw failure;
        }
        lock.notifyAll();
    }

    private int outputItemCount(SessionState state, String turnId) {
        int count = 0;
        for (SessionItem existing : state.items) {
            if (turnId.equals(existing.turnId()) && !"user".equals(existing.role())) {
                count++;
            }
        }
        return count;
    }

    private void appendOutputItemEvents(SessionState state, String turnId,
                                        SessionItem item, int outputIndex) {
        Map<String, Object> itemPayload = Map.of("item", item, "output_index", outputIndex);
        state.events.add(newEvent(state, "agent.session.turn.item.added", turnId, itemPayload));
        if ("message".equals(item.type()) && "assistant".equals(item.role())) {
            String text = item.content() == null ? "" : item.content();
            Map<String, Object> textPayload = Map.of(
                    "item_id", item.id(), "output_index", outputIndex,
                    "content_index", 0, "delta", text);
            state.events.add(newEvent(state, "agent.session.turn.output_text.delta", turnId,
                    textPayload));
            state.events.add(newEvent(state, "agent.session.turn.output_text.done", turnId,
                    Map.of("item_id", item.id(), "output_index", outputIndex,
                            "content_index", 0, "text", text)));
        }
        state.events.add(newEvent(state, "agent.session.turn.item.done", turnId, itemPayload));
    }

    private SessionItem newItem(String turnId, Message message) {
        String type = "message";
        if ("tool".equals(message.role())) {
            type = "function_call_output";
        } else if (message.tool() != null && !message.tool().isEmpty()) {
            type = "function_call";
        }
        String name = message.tool() == null || message.tool().isEmpty() ? null : message.tool();
        return new SessionItem(newId("item_"), turnId, type, message.role(), message.content(),
                name, message.callId(), message.argumentsBase64(), message.success(), Instant.now());
    }

    private void restore() {
        if (store == null) {
            return;
        }
        synchronized (lock) {
            try {
                for (Map.Entry<String, byte[]> entry : store.loadAll().entrySet()) {
                    Snapshot snapshot = json.readValue(entry.getValue(), Snapshot.class);
                    if (snapshot == null || snapshot.session() == null
                            || !entry.getKey().equals(snapshot.session().id())) {
                        throw new IOException("invalid session snapshot: " + entry.getKey());
                    }
                    SessionState state = new SessionState(snapshot.session());
                    for (Turn turn : snapshot.turns()) {
                        state.turns.put(turn.id(), turn);
                    }
                    state.context = snapshot.context();
                    state.executionMessages = new ArrayList<>(snapshot.executionMessages());
                    state.events.addAll(snapshot.events());
                    state.items.addAll(snapshot.items());
                    state.checkpoint = snapshot.checkpoint();
                    state.safeToResume = snapshot.safeToResume();
                    state.toolResults.putAll(snapshot.toolResults());
                    state.steering.addAll(snapshot.steering());
                    state.claimedSteering.addAll(snapshot.claimedSteering());
                    sessions.put(state.session.id(), state);
                    recoverInterruptedTurn(state);
                }
            } catch (Exception failure) {
                throw new IllegalStateException("cannot restore session store", failure);
            }
        }
    }

    private void recoverInterruptedTurn(SessionState state) {
        String activeId = state.session.activeTurnId();
        if (activeId == null) {
            startQueuedSteering(state);
            return;
        }
        Turn turn = state.turns.get(activeId);
        if (turn == null) {
            throw new IllegalStateException("snapshot active turn is missing");
        }
        if ("requires_action".equals(state.session.status())
                && "waiting".equals(turn.status()) && state.checkpoint != null
                && state.checkpoint.pendingExternal() != null) {
            return;
        }
        if ("in_progress".equals(turn.status()) && state.safeToResume
                && state.checkpoint != null && state.checkpoint.pendingExternal() == null
                && runner instanceof LoopRunner) {
            CancellationToken cancellation = new CancellationToken();
            state.cancellation = cancellation;
            Session session = state.session;
            LoopCheckpoint checkpoint = state.checkpoint;
            turnExecutor.execute(() -> runLoop(cancellation, session, checkpoint, turn));
            return;
        }
        boolean wasCancelling = "cancelling".equals(turn.status());
        String outcome = wasCancelling ? "cancelled" : "failed";
        String error = wasCancelling ? "" : "server restarted during execution; tool effects may need reconciliation";
        Session oldSession = state.session;
        List<Message> oldContext = state.context;
        LoopCheckpoint oldCheckpoint = state.checkpoint;
        boolean oldSafeToResume = state.safeToResume;
        List<String> oldSteering = List.copyOf(state.steering);
        List<SteeringClaim> oldClaims = List.copyOf(state.claimedSteering);
        int oldMessages = state.executionMessages.size();
        int oldItems = state.items.size();
        int oldEvents = state.events.size();
        try {
            pairUnfinishedToolCalls(state, activeId, outcome);
            state.context = contextWithoutClaimedSteering(state);
            requeueClaimedSteering(state);
            if (!wasCancelling) {
                List<Message> recovered = new ArrayList<>(state.context);
                recovered.add(new Message("summary", "Previous turn failed: " + error));
                state.context = List.copyOf(recovered);
            }
            Instant now = Instant.now();
            Turn terminal = new Turn(turn.id(), turn.sessionId(), turn.input(),
                    outcome, "", error, turn.createdAt(), now);
            state.turns.put(activeId, terminal);
            state.session = new Session(state.session.id(), state.session.agent(), "idle", null,
                    state.session.createdAt(), now);
            state.checkpoint = null;
            state.safeToResume = false;
            state.events.add(newEvent(state, "turn." + outcome, activeId,
                    Map.of("recovered", true)));
            state.events.add(newEvent(state, "agent.session.turn." + outcome, activeId,
                    Map.of("turn", terminal, "recovered", true)));
            state.events.add(newEvent(state, "agent.session.idle", activeId,
                    Map.of("session", state.session, "recovered", true)));
            persist(state);
        } catch (RuntimeException failure) {
            state.turns.put(activeId, turn);
            state.session = oldSession;
            state.context = oldContext;
            state.checkpoint = oldCheckpoint;
            state.safeToResume = oldSafeToResume;
            state.steering.clear();
            state.steering.addAll(oldSteering);
            state.claimedSteering.clear();
            state.claimedSteering.addAll(oldClaims);
            truncate(state.executionMessages, oldMessages);
            truncate(state.items, oldItems);
            truncate(state.events, oldEvents);
            throw failure;
        }
        lock.notifyAll();
        startQueuedSteering(state);
    }

    private void persist(SessionState state) {
        if (store == null) {
            return;
        }
        Snapshot snapshot = new Snapshot(state.session, List.copyOf(state.turns.values()),
                state.context, List.copyOf(state.executionMessages), List.copyOf(state.events),
                List.copyOf(state.items), state.checkpoint, Map.copyOf(state.toolResults),
                List.copyOf(state.steering), List.copyOf(state.claimedSteering), state.safeToResume);
        boolean restoreInterrupt = Thread.interrupted();
        try {
            byte[] bytes = json.writeValueAsBytes(snapshot);
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    store.save(state.session.id(), bytes);
                    return;
                } catch (ClosedByInterruptException interrupted) {
                    restoreInterrupt = true;
                    Thread.interrupted();
                    if (attempt == 1) {
                        throw interrupted;
                    }
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException("cannot persist session " + state.session.id(), failure);
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public record Snapshot(Session session, List<Turn> turns, List<Message> context,
                           List<Message> executionMessages, List<Event> events,
                           List<SessionItem> items, LoopCheckpoint checkpoint,
                           Map<String, ToolResult> toolResults, List<String> steering,
                           List<SteeringClaim> claimedSteering, Boolean safeToResume) {
        public Snapshot(Session session, List<Turn> turns, List<Message> context,
                        List<Message> executionMessages, List<Event> events,
                        List<SessionItem> items, LoopCheckpoint checkpoint,
                        Map<String, ToolResult> toolResults, List<String> steering,
                        Boolean safeToResume) {
            this(session, turns, context, executionMessages, events, items, checkpoint,
                    toolResults, steering, List.of(), safeToResume);
        }

        public Snapshot {
            turns = turns == null ? List.of() : List.copyOf(turns);
            context = context == null ? List.of() : List.copyOf(context);
            executionMessages = executionMessages == null ? List.of() : List.copyOf(executionMessages);
            events = events == null ? List.of() : List.copyOf(events);
            items = items == null ? List.of() : List.copyOf(items);
            toolResults = toolResults == null ? Map.of() : Map.copyOf(toolResults);
            steering = steering == null ? List.of() : List.copyOf(steering);
            claimedSteering = claimedSteering == null ? List.of() : List.copyOf(claimedSteering);
            safeToResume = safeToResume != null && safeToResume;
        }
    }

    public record SteeringClaim(String input, int messageIndex, boolean consumed) {
        public SteeringClaim(String input, int messageIndex) {
            this(input, messageIndex, false);
        }

        public SteeringClaim {
            if (input == null || input.isBlank() || messageIndex < 0) {
                throw new IllegalArgumentException("invalid steering claim");
            }
        }
    }

    private record BufferedEffect(String type, Map<String, Object> data, Message message) {}

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
        private List<Message> executionMessages = new ArrayList<>();
        private final List<Event> events = new ArrayList<>();
        private final List<SessionItem> items = new ArrayList<>();
        private LoopCheckpoint checkpoint;
        private boolean safeToResume;
        private boolean resultInFlight;
        private final Map<String, ToolResult> toolResults = new HashMap<>();
        private final List<String> steering = new ArrayList<>();
        private final List<SteeringClaim> claimedSteering = new ArrayList<>();
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
