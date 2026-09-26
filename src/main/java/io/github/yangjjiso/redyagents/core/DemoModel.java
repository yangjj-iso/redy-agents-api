package io.github.yangjjiso.redyagents.core;

import java.util.List;

public final class DemoModel implements Model {
    @Override
    public Decision next(CancellationToken cancellation, AgentConfig agent, List<Message> messages) {
        if (!"demo".equals(agent.model())) {
            throw new IllegalStateException("model adapter is not configured");
        }
        cancellation.throwIfCancelled();
        return Decision.finalMessage("Demo response: " + messages.get(messages.size() - 1).content());
    }
}
