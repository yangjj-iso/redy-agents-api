package io.github.yangjjiso.redyagents.core;

import java.util.List;

@FunctionalInterface
public interface Model {
    Decision next(CancellationToken cancellation, AgentConfig agent, List<Message> messages) throws Exception;
}
