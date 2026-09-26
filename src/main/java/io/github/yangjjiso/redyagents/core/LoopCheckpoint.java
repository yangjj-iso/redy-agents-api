package io.github.yangjjiso.redyagents.core;

import java.util.List;
import java.util.Map;

/** Immutable, JSON-serializable continuation state for one agent turn. */
public record LoopCheckpoint(
        List<Message> messages,
        int step,
        Map<String, CachedCall> callsById,
        Map<String, FailureState> failedSignatures,
        PendingExternal pendingExternal) {

    public LoopCheckpoint {
        messages = List.copyOf(messages);
        callsById = Map.copyOf(callsById);
        failedSignatures = Map.copyOf(failedSignatures);
        if (step < 0) {
            throw new IllegalArgumentException("step must be nonnegative");
        }
    }

    public ToolCall pendingCall() {
        return pendingExternal == null ? null : pendingExternal.call();
    }

    /** Whether the tool was actually dispatched distinguishes rejection from an executed call. */
    public record CachedCall(String signature, String toolMessage, boolean success, boolean dispatched) {
        public CachedCall(String signature, String toolMessage, boolean success) {
            this(signature, toolMessage, success, true);
        }
    }

    public record FailureState(String message, boolean retryable, boolean retryUsed, boolean dispatched) {
        public FailureState(String message, boolean retryable, boolean retryUsed) {
            this(message, retryable, retryUsed, true);
        }
    }

    public record PendingExternal(ToolCall call, String assistantMessage) {
        public PendingExternal {
            if (call == null || call.callId() == null) {
                throw new IllegalArgumentException("pending external call requires a callId");
            }
            assistantMessage = assistantMessage == null ? "" : assistantMessage;
        }
    }
}
