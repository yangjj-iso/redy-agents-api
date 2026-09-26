package io.github.yangjjiso.redyagents.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class LoopRunner implements Runner {
    private final Model model;
    private final Map<String, Tool> tools;
    private final int maxSteps;

    public LoopRunner(Model model) {
        this(model, Map.of(), 8);
    }

    public LoopRunner(Model model, Map<String, Tool> tools, int maxSteps) {
        this.model = model;
        this.tools = tools == null ? Map.of() : Map.copyOf(tools);
        this.maxSteps = maxSteps > 0 ? maxSteps : 8;
    }

    @Override
    public String run(CancellationToken cancellation, Session session, List<Message> history,
                      String input, EventEmitter emit) throws Exception {
        if (model == null) {
            throw new IllegalStateException("model adapter is not configured");
        }
        List<Message> messages = new ArrayList<>(history);
        messages.add(new Message("user", input));
        for (int step = 0; step < maxSteps; step++) {
            cancellation.throwIfCancelled();
            Decision decision = model.next(cancellation, session.agent(), List.copyOf(messages));
            if (decision == null) {
                throw new IllegalStateException("model returned no decision");
            }
            if ("final".equals(decision.kind())) {
                String response = decision.message() == null ? "" : decision.message();
                emit.emit("message.delta", Map.of("text", response));
                return response;
            }
            if ("tool_call".equals(decision.kind())) {
                ToolCall call = decision.tool();
                if (call == null || call.name() == null || call.name().isEmpty()) {
                    throw new IllegalStateException("model returned an invalid tool call");
                }
                Tool tool = tools.get(call.name());
                if (tool == null) {
                    throw new IllegalStateException("unknown tool \"" + call.name() + "\"");
                }
                emit.emit("tool.call.started", Map.of("name", call.name()));
                String result;
                try {
                    result = tool.execute(cancellation, call.arguments());
                } catch (Exception e) {
                    emit.emit("tool.call.failed", Map.of("name", call.name(), "error", e.getMessage() == null ? "" : e.getMessage()));
                    throw e;
                }
                cancellation.throwIfCancelled();
                emit.emit("tool.call.completed", Map.of("name", call.name()));
                messages.add(new Message("assistant", decision.message() == null ? "" : decision.message()));
                messages.add(new Message("tool", result == null ? "" : result, call.name()));
                continue;
            }
            throw new IllegalStateException("unknown model decision \"" + decision.kind() + "\"");
        }
        throw new IllegalStateException("run exceeded max steps");
    }
}
