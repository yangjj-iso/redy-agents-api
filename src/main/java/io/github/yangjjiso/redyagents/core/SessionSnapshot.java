package io.github.yangjjiso.redyagents.core;

import java.util.List;
import java.util.Map;

/** Stored session state. Its record components are the on-disk snapshot schema. */
record SessionSnapshot(Session session, List<Turn> turns, List<Message> context,
                       List<Message> executionMessages, List<Event> events,
                       List<SessionItem> items, LoopCheckpoint checkpoint,
                       Map<String, ToolResult> toolResults, List<String> steering,
                       List<SteeringClaim> claimedSteering, Boolean safeToResume) {
    SessionSnapshot(Session session, List<Turn> turns, List<Message> context,
                    List<Message> executionMessages, List<Event> events,
                    List<SessionItem> items, LoopCheckpoint checkpoint,
                    Map<String, ToolResult> toolResults, List<String> steering,
                    Boolean safeToResume) {
        this(session, turns, context, executionMessages, events, items, checkpoint,
                toolResults, steering, List.of(), safeToResume);
    }

    SessionSnapshot {
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

    record SteeringClaim(String input, int messageIndex, boolean consumed) {
        SteeringClaim(String input, int messageIndex) {
            this(input, messageIndex, false);
        }

        SteeringClaim {
            if (input == null || input.isBlank() || messageIndex < 0) {
                throw new IllegalArgumentException("invalid steering claim");
            }
        }
    }
}
