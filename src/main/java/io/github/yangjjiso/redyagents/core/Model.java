package io.github.yangjjiso.redyagents.core;

import java.util.List;

@FunctionalInterface
public interface Model {
    Decision next(CancellationToken cancellation, AgentConfig agent, List<Message> messages) throws Exception;

    /** Models that support function calling can override this to receive the active tool catalog. */
    default Decision next(CancellationToken cancellation, AgentConfig agent, List<Message> messages,
                          List<ToolDefinition> tools) throws Exception {
        return next(cancellation, agent, messages);
    }
}
