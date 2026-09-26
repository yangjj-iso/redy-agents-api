package io.github.yangjjiso.redyagents.core;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

public final class LoopRunner implements Runner {
    private final Model model;
    private final Map<String, Tool> tools;
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
        this.maxSteps = maxSteps > 0 ? maxSteps : 8;
        this.contextWindow = contextWindow == null ? new ContextWindow() : contextWindow;
    }

    @Override
    public String run(CancellationToken cancellation, Session session, List<Message> history,
                      String input, EventEmitter emit) throws Exception {
        return runWithContext(cancellation, session, history, input, emit).output();
    }

    @Override
    public RunResult runWithContext(CancellationToken cancellation, Session session, List<Message> history,
                                    String input, EventEmitter emit) throws Exception {
        if (model == null) {
            throw new IllegalStateException("model adapter is not configured");
        }
        List<Message> messages = new ArrayList<>(history);
        messages.add(new Message("user", input));
        Map<String, CachedCall> callsById = new HashMap<>();
        Map<CallSignature, FailureState> failedSignatures = new HashMap<>();
        for (int step = 0; step < maxSteps; step++) {
            cancellation.throwIfCancelled();
            AgentConfig agent = session.agent();
            ContextWindow.Result context = contextWindow.fit(
                    List.copyOf(messages), agent.instructions(), agent.promptBudgetTokens());
            if (context.compacted()) {
                emit.emit("context.compacted", Map.of(
                        "step", step + 1,
                        "estimated_prompt_tokens", context.estimatedTokens(),
                        "prompt_budget_tokens", agent.promptBudgetTokens(),
                        "omitted_messages", context.omittedMessages()));
            }
            messages = new ArrayList<>(context.messages());
            Decision decision = model.next(cancellation, agent, List.copyOf(messages));
            if (decision == null) {
                throw new IllegalStateException("model returned no decision");
            }
            if ("final".equals(decision.kind())) {
                String response = decision.message() == null ? "" : decision.message();
                emit.emit("message.delta", Map.of("text", response));
                messages.add(new Message("assistant", response));
                return new RunResult(response, messages);
            }
            if ("tool_call".equals(decision.kind())) {
                ToolCall call = decision.tool();
                if (call == null || call.name() == null || call.name().isEmpty()) {
                    throw new IllegalStateException("model returned an invalid tool call");
                }
                CallSignature signature = new CallSignature(call.name(),
                        Base64.getEncoder().encodeToString(call.arguments()));
                if (call.callId() != null) {
                    CachedCall cached = callsById.get(call.callId());
                    if (cached != null) {
                        if (!cached.signature().equals(signature)) {
                            String error = "tool call ID \"" + call.callId()
                                    + "\" was reused with different tool or arguments";
                            emit.emit("tool.call.failed", Map.of("name", call.name(), "error", error));
                            throw new IllegalStateException(error);
                        }
                        cancellation.throwIfCancelled();
                        emit.emit("tool.call.replayed", Map.of(
                                "name", call.name(), "call_id", call.callId(),
                                "outcome", cached.failure() == null ? "completed" : "failed"));
                        appendToolExchange(messages, decision, call, cached.toolMessage());
                        continue;
                    }
                }
                Tool tool = tools.get(call.name());
                if (tool == null) {
                    throw new IllegalStateException("unknown tool \"" + call.name() + "\"");
                }
                FailureState previousFailure = failedSignatures.get(signature);
                if (previousFailure != null) {
                    if (!tool.isIdempotent() || !previousFailure.failure().retryable()
                            || previousFailure.retryUsed()) {
                        String error = "repeated failed tool call \"" + call.name()
                                + "\" with identical arguments is blocked; only one model-requested retry"
                                + " is allowed for a retryable failure of an idempotent tool";
                        emit.emit("tool.call.failed", Map.of("name", call.name(), "error", error));
                        String feedback = "Tool failure: " + error;
                        if (call.callId() != null) {
                            callsById.put(call.callId(), new CachedCall(signature, feedback,
                                    previousFailure.failure()));
                        }
                        appendToolExchange(messages, decision, call, feedback);
                        continue;
                    }
                    // Permit one model-requested retry. Never retry a tool automatically.
                    failedSignatures.put(signature, new FailureState(previousFailure.failure(), true));
                }
                cancellation.throwIfCancelled();
                emit.emit("tool.call.started", Map.of("name", call.name()));
                String result;
                try {
                    tool.validateArguments(call.arguments());
                    cancellation.throwIfCancelled();
                    String rawResult = tool.execute(cancellation, call.arguments());
                    cancellation.throwIfCancelled();
                    result = rawResult == null ? "" : rawResult;
                    tool.validateResult(call.arguments(), result);
                    cancellation.throwIfCancelled();
                } catch (ToolFailure failure) {
                    cancellation.throwIfCancelled();
                    failedSignatures.put(signature,
                            new FailureState(failure, previousFailure != null));
                    String feedback = "Tool failure: " + failure.getMessage()
                            + " (retryable: " + failure.retryable() + ")";
                    emit.emit("tool.call.failed", Map.of(
                            "name", call.name(), "error", failure.getMessage(),
                            "retryable", failure.retryable()));
                    if (call.callId() != null) {
                        callsById.put(call.callId(), new CachedCall(signature, feedback, failure));
                    }
                    appendToolExchange(messages, decision, call, feedback);
                    continue;
                } catch (CancellationException e) {
                    throw e;
                } catch (Exception e) {
                    emit.emit("tool.call.failed", Map.of("name", call.name(), "error", e.getMessage() == null ? "" : e.getMessage()));
                    throw e;
                }
                emit.emit("tool.call.completed", Map.of("name", call.name()));
                if (call.callId() != null) {
                    callsById.put(call.callId(), new CachedCall(signature, result, null));
                }
                appendToolExchange(messages, decision, call, result);
                continue;
            }
            throw new IllegalStateException("unknown model decision \"" + decision.kind() + "\"");
        }
        throw new IllegalStateException("run exceeded max steps");
    }

    private static void appendToolExchange(List<Message> messages, Decision decision,
                                           ToolCall call, String toolMessage) {
        messages.add(new Message("assistant", decision.message() == null ? "" : decision.message()));
        messages.add(new Message("tool", toolMessage, call.name()));
    }

    private record CallSignature(String name, String argumentsBase64) {}

    private record CachedCall(CallSignature signature, String toolMessage, ToolFailure failure) {}

    private record FailureState(ToolFailure failure, boolean retryUsed) {}
}
