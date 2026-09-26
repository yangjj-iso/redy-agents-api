package io.github.yangjjiso.redyagents.core;

import java.util.List;

/** A completed turn and the model-facing context to use for the next turn. */
public record RunResult(String output, List<Message> context) {
    public RunResult {
        output = output == null ? "" : output;
        context = List.copyOf(context);
    }
}
