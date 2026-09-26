package io.github.yangjjiso.redyagents.core;

import java.util.List;
import java.util.ArrayList;

@FunctionalInterface
public interface Runner {
    String run(CancellationToken cancellation, Session session, List<Message> history,
               String input, EventEmitter emit) throws Exception;

    default RunResult runWithContext(CancellationToken cancellation, Session session,
                                     List<Message> history, String input, EventEmitter emit) throws Exception {
        String output = run(cancellation, session, history, input, emit);
        List<Message> context = new ArrayList<>(history);
        context.add(new Message("user", input));
        context.add(new Message("assistant", output == null ? "" : output));
        return new RunResult(output, context);
    }
}
