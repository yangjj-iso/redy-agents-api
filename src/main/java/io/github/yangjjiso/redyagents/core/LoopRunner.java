package io.github.yangjjiso.redyagents.core;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/** Runs a model/tool turn, pausing at application-owned function calls. */
public final class LoopRunner implements ResumableRunner {
    private final Model model;
    private final Map<String, Tool> tools;
    private final List<ToolDefinition> toolDefinitions;
    private final int maxSteps;
    private final ContextWindow contextWindow;

    public LoopRunner(Model model) {
        this(model, Map.of(), 8);
    }

    public LoopRunner(Model model, Map<String, Tool> tools, int maxSteps) {
        this(model, tools, maxSteps, new ContextWindow());
    }

    public LoopRunner(Model model, Map<String, Tool> tools, int maxSteps, ContextWindow contextWindow) {
        this.model = model;
        this.tools = tools == null ? Map.of() : Map.copyOf(tools);
        this.toolDefinitions = this.tools.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    ToolDefinition definition = Objects.requireNonNull(
                            entry.getValue().definition(entry.getKey()), "tool definition");
                    if (!entry.getKey().equals(definition.name())) {
                        throw new IllegalArgumentException("tool definition name does not match registered name");
                    }
                    return definition;
                })
                .toList();
        this.maxSteps = maxSteps > 0 ? maxSteps : 8;
        this.contextWindow = contextWindow == null ? new ContextWindow() : contextWindow;
    }

    @Override
    public LoopCheckpoint start(List<Message> history, String input) {
        Objects.requireNonNull(history, "history");
        List<Message> messages = new ArrayList<>(history);
        messages.add(new Message("user", input == null ? "" : input));
        return new LoopCheckpoint(messages, 0, Map.of(), Map.of(), null);
    }

    /** Advances until completion or until an external function result is required. */
    @Override
    public LoopProgress advance(CancellationToken cancellation, Session session,
                                LoopCheckpoint checkpoint, EventEmitter emit) throws Exception {
        return advanceInternal(cancellation, session, checkpoint, emit, false);
    }

    /** Applies a supplied result and returns a checkpoint that can be passed to advance. */
    @Override
    public LoopCheckpoint resumeExternal(LoopCheckpoint checkpoint, ToolResult result, EventEmitter emit) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(emit, "emit");
        LoopCheckpoint.PendingExternal pending = checkpoint.pendingExternal();
        if (pending == null) {
            throw new IllegalStateException("there is no pending external function call");
        }
        ToolCall call = pending.call();
        if (!call.callId().equals(result.callId())) {
            throw new IllegalArgumentException("tool result callId does not match the pending call");
        }
        Tool tool = tools.get(call.name());
        if (tool == null || !tool.isExternal()) {
            throw new IllegalStateException("pending external function is no longer registered");
        }
        List<Message> messages = new ArrayList<>(checkpoint.messages());
        Map<String, LoopCheckpoint.CachedCall> callsById = new HashMap<>(checkpoint.callsById());
        Map<String, LoopCheckpoint.FailureState> failedSignatures =
                new HashMap<>(checkpoint.failedSignatures());
        String signature = signature(call);
        boolean success = result.success();
        String error = result.error();
        String toolMessage = result.output();
        if (success) {
            try {
                tool.validateResult(call.arguments(), toolMessage);
            } catch (ToolFailure failure) {
                success = false;
                error = failure.getMessage();
            }
        }
        if (success) {
            failedSignatures.remove(signature);
            emit.emit("tool.call.completed", eventData(call, "success", true));
        } else {
            toolMessage = "Tool failure: " + error + " (retryable: false)";
            failedSignatures.put(signature, new LoopCheckpoint.FailureState(error, false, false));
            emit.emit("tool.call.failed", eventData(call, "error", error, "success", false));
        }
        callsById.put(call.callId(), new LoopCheckpoint.CachedCall(signature, toolMessage, success));
        appendToolResult(messages, call, toolMessage, success, emit, false);
        return snapshot(messages, checkpoint.step(), callsById, failedSignatures, null);
    }

    @Override
    public String run(CancellationToken cancellation, Session session, List<Message> history,
                      String input, EventEmitter emit) throws Exception {
        return runWithContext(cancellation, session, history, input, emit).output();
    }

    @Override
    public RunResult runWithContext(CancellationToken cancellation, Session session, List<Message> history,
                                    String input, EventEmitter emit) throws Exception {
        LoopProgress progress = advanceInternal(cancellation, session, start(history, input), emit, true);
        if (progress.requiresAction()) {
            throw new IllegalStateException("external function call requires resumeExternal and advance");
        }
        return new RunResult(progress.output(), progress.checkpoint().messages());
    }

    private LoopProgress advanceInternal(CancellationToken cancellation, Session session,
                                         LoopCheckpoint checkpoint, EventEmitter emit,
                                         boolean legacyMessages) throws Exception {
        if (model == null) {
            throw new IllegalStateException("model adapter is not configured");
        }
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(emit, "emit");
        cancellation.throwIfCancelled();
        if (checkpoint.pendingExternal() != null) {
            return new LoopProgress("requires_action", null, checkpoint.pendingExternal().call(), checkpoint);
        }
        List<Message> messages = new ArrayList<>(checkpoint.messages());
        Map<String, LoopCheckpoint.CachedCall> callsById = new HashMap<>(checkpoint.callsById());
        Map<String, LoopCheckpoint.FailureState> failedSignatures =
                new HashMap<>(checkpoint.failedSignatures());
        int step = checkpoint.step();
        while (step < maxSteps) {
            cancellation.throwIfCancelled();
            appendSteering(messages, emit);
            AgentConfig agent = session.agent();
            ContextWindow.Result context = contextWindow.fit(
                    List.copyOf(messages), agent.instructions(), toolDefinitions, agent.promptBudgetTokens());
            if (context.compacted()) {
                emit.emit("context.compacted", Map.of(
                        "step", step + 1,
                        "estimated_prompt_tokens", context.estimatedTokens(),
                        "prompt_budget_tokens", agent.promptBudgetTokens(),
                        "omitted_messages", context.omittedMessages()));
            }
            messages = new ArrayList<>(context.messages());
            step++;
            emit.markSteeringConsumed();
            cancellation.throwIfCancelled();
            Decision decision = model.next(cancellation, agent, List.copyOf(messages), toolDefinitions);
            cancellation.throwIfCancelled();
            if (decision == null) {
                throw new IllegalStateException("model returned no decision");
            }
            if ("final".equals(decision.kind())) {
                String response = decision.message() == null ? "" : decision.message();
                List<String> steering = step >= maxSteps ? List.of() : drainSteering(emit);
                cancellation.throwIfCancelled();
                Message assistant = new Message("assistant", response);
                messages.add(assistant);
                emit.record(assistant);
                if (!steering.isEmpty()) {
                    appendSteering(messages, steering, emit);
                    continue;
                }
                emit.emit("message.delta", Map.of("text", response));
                return new LoopProgress("completed", response, null,
                        snapshot(messages, step, callsById, failedSignatures, null));
            }
            if (!"tool_call".equals(decision.kind())) {
                throw new IllegalStateException("unknown model decision \"" + decision.kind() + "\"");
            }
            ToolCall call = decision.tool();
            if (call == null || call.name() == null || call.name().isEmpty()) {
                throw new IllegalStateException("model returned an invalid tool call");
            }
            if (call.callId() == null) {
                call = new ToolCall(call.name(), call.arguments(), "call_" + UUID.randomUUID());
            }
            String signature = signature(call);
            if (call.callId() != null) {
                LoopCheckpoint.CachedCall cached = callsById.get(call.callId());
                if (cached != null) {
                    if (!cached.signature().equals(signature)) {
                        String error = "tool call ID \"" + call.callId()
                                + "\" was reused with different tool or arguments";
                        emit.emit("tool.call.failed", eventData(call, "error", error, "success", false));
                        throw new IllegalStateException(error);
                    }
                    cancellation.throwIfCancelled();
                    emit.emit("tool.call.replayed", eventData(call, "outcome",
                            cached.success() ? "completed" : "failed", "success", cached.success()));
                    if (cached.dispatched()) {
                        appendToolExchange(messages, decision, call, cached.toolMessage(),
                                cached.success(), emit, legacyMessages);
                    } else {
                        appendRejectedCall(messages, cached.toolMessage(), emit);
                    }
                    continue;
                }
            }
            Tool tool = tools.get(call.name());
            if (tool == null) {
                throw new IllegalStateException("unknown tool \"" + call.name() + "\"");
            }
            LoopCheckpoint.FailureState previousFailure = failedSignatures.get(signature);
            if (previousFailure != null) {
                if (!tool.isIdempotent() || !previousFailure.retryable() || previousFailure.retryUsed()) {
                    String error = "repeated failed tool call \"" + call.name()
                            + "\" with identical arguments is blocked; only one model-requested retry"
                            + " is allowed for a retryable failure of an idempotent tool";
                    emit.emit("tool.call.failed", eventData(call, "error", error, "success", false));
                    String feedback = "Tool failure: " + error;
                    if (call.callId() != null) {
                        callsById.put(call.callId(), new LoopCheckpoint.CachedCall(
                                signature, feedback, false, previousFailure.dispatched()));
                    }
                    if (previousFailure.dispatched()) {
                        appendToolExchange(messages, decision, call, feedback, false, emit, legacyMessages);
                    } else {
                        appendRejectedCall(messages, feedback, emit);
                    }
                    continue;
                }
                failedSignatures.put(signature,
                        new LoopCheckpoint.FailureState(previousFailure.message(), true, true,
                                previousFailure.dispatched()));
            }
            cancellation.throwIfCancelled();
            try {
                if (tool.isExternal()) {
                    ExternalFunctionTool.validateJsonArguments(call.arguments());
                }
                tool.validateArguments(call.arguments());
            } catch (ToolFailure failure) {
                failedSignatures.put(signature, new LoopCheckpoint.FailureState(
                        failure.getMessage(), failure.retryable(), previousFailure != null, false));
                String feedback = "Tool failure: " + failure.getMessage()
                        + " (retryable: " + failure.retryable() + ")";
                emit.emit("tool.call.failed", eventData(call, "error", failure.getMessage(),
                        "retryable", failure.retryable(), "success", false));
                if (call.callId() != null) {
                    callsById.put(call.callId(), new LoopCheckpoint.CachedCall(signature, feedback, false, false));
                }
                appendRejectedCall(messages, feedback, emit);
                continue;
            } catch (RuntimeException failure) {
                emit.emit("tool.call.failed", eventData(call, "error",
                        failure.getMessage() == null ? "" : failure.getMessage(), "success", false));
                throw failure;
            }
            cancellation.throwIfCancelled();
            try {
                if (tool.isExternal()) {
                    appendToolCall(messages, decision, call, emit, legacyMessages);
                    emit.emit("tool.call.started", eventData(call));
                    LoopCheckpoint.PendingExternal pending =
                            new LoopCheckpoint.PendingExternal(call, decision.message());
                    LoopCheckpoint next = snapshot(messages, step, callsById, failedSignatures, pending);
                    return new LoopProgress("requires_action", null, call, next);
                }
                appendToolCall(messages, decision, call, emit, legacyMessages);
                emit.emit("tool.call.started", eventData(call));
                String rawResult = tool.execute(cancellation,
                        new ToolExecutionContext(session, call.callId()), call.arguments());
                cancellation.throwIfCancelled();
                String result = rawResult == null ? "" : rawResult;
                tool.validateResult(call.arguments(), result);
                cancellation.throwIfCancelled();
                if (call.callId() != null) {
                    callsById.put(call.callId(), new LoopCheckpoint.CachedCall(signature, result, true));
                }
                failedSignatures.remove(signature);
                appendToolResult(messages, call, result, true, emit, legacyMessages);
                emit.emit("tool.call.completed", eventData(call, "success", true));
            } catch (ToolFailure failure) {
                cancellation.throwIfCancelled();
                failedSignatures.put(signature,
                        new LoopCheckpoint.FailureState(failure.getMessage(), failure.retryable(),
                                previousFailure != null));
                String feedback = "Tool failure: " + failure.getMessage()
                        + " (retryable: " + failure.retryable() + ")";
                if (call.callId() != null) {
                    callsById.put(call.callId(), new LoopCheckpoint.CachedCall(signature, feedback, false));
                }
                appendToolResult(messages, call, feedback, false, emit, legacyMessages);
                emit.emit("tool.call.failed", eventData(call, "error", failure.getMessage(),
                        "retryable", failure.retryable(), "success", false));
            } catch (CancellationException e) {
                throw e;
            } catch (Exception e) {
                emit.emit("tool.call.failed", eventData(call, "error",
                        e.getMessage() == null ? "" : e.getMessage(), "success", false));
                throw e;
            }
        }
        throw new IllegalStateException("run exceeded max steps");
    }

    private static LoopCheckpoint snapshot(List<Message> messages, int step,
                                            Map<String, LoopCheckpoint.CachedCall> callsById,
                                            Map<String, LoopCheckpoint.FailureState> failedSignatures,
                                            LoopCheckpoint.PendingExternal pending) {
        return new LoopCheckpoint(messages, step, callsById, failedSignatures, pending);
    }

    private static String signature(ToolCall call) {
        return call.name().length() + ":" + call.name() + ":"
                + Base64.getEncoder().encodeToString(call.arguments());
    }

    private static void appendToolExchange(List<Message> messages, Decision decision, ToolCall call,
                                           String toolMessage, boolean success, EventEmitter emit,
                                           boolean legacyMessages) {
        appendToolCall(messages, decision, call, emit, legacyMessages);
        appendToolResult(messages, call, toolMessage, success, emit, legacyMessages);
    }

    private static void appendRejectedCall(List<Message> messages, String feedback, EventEmitter emit) {
        Message message = new Message("assistant", feedback);
        messages.add(message);
        emit.record(message);
    }

    private static void appendToolCall(List<Message> messages, Decision decision, ToolCall call,
                                       EventEmitter emit, boolean legacyMessages) {
        Message message = legacyMessages
                ? new Message("assistant", decision.message() == null ? "" : decision.message())
                : new Message("assistant", decision.message() == null ? "" : decision.message(),
                        call.name(), call.callId(), Base64.getEncoder().encodeToString(call.arguments()), null);
        messages.add(message);
        emit.record(message);
    }

    private static void appendToolResult(List<Message> messages, ToolCall call, String toolMessage,
                                         boolean success, EventEmitter emit, boolean legacyMessages) {
        Message message = legacyMessages
                ? new Message("tool", toolMessage, call.name())
                : new Message("tool", toolMessage, call.name(), call.callId(),
                        Base64.getEncoder().encodeToString(call.arguments()), success);
        messages.add(message);
        emit.record(message);
    }

    private static void appendSteering(List<Message> messages, EventEmitter emit) {
        appendSteering(messages, drainSteering(emit), emit);
    }

    private static List<String> drainSteering(EventEmitter emit) {
        List<String> steering = emit.drainSteering();
        return steering == null ? List.of() : List.copyOf(steering);
    }

    private static void appendSteering(List<Message> messages, List<String> steering, EventEmitter emit) {
        for (String input : steering) {
            Message message = new Message("user", input == null ? "" : input);
            messages.add(message);
        }
    }

    private static Map<String, Object> eventData(ToolCall call, Object... extra) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", call.name());
        if (call.callId() != null) {
            data.put("call_id", call.callId());
        }
        for (int i = 0; i < extra.length; i += 2) {
            data.put((String) extra[i], extra[i + 1]);
        }
        return Map.copyOf(data);
    }
}
