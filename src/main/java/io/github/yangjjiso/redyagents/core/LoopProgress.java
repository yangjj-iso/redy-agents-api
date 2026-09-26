package io.github.yangjjiso.redyagents.core;

/** Completed turn or a function call awaiting an application result. */
public record LoopProgress(String status, String output, ToolCall pendingCall, LoopCheckpoint checkpoint) {
    public LoopProgress {
        if (!"completed".equals(status) && !"requires_action".equals(status)) {
            throw new IllegalArgumentException("unknown loop progress status");
        }
        if (checkpoint == null) {
            throw new IllegalArgumentException("checkpoint is required");
        }
        if ("requires_action".equals(status) && pendingCall == null) {
            throw new IllegalArgumentException("pendingCall is required when action is needed");
        }
        if ("completed".equals(status) && pendingCall != null) {
            throw new IllegalArgumentException("completed loop cannot have a pending call");
        }
    }

    public boolean isCompleted() {
        return "completed".equals(status);
    }

    public boolean requiresAction() {
        return "requires_action".equals(status);
    }
}
