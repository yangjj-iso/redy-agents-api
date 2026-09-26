package io.github.yangjjiso.redyagents.core;

import java.util.List;

@FunctionalInterface
public interface Runner {
    String run(CancellationToken cancellation, Session session, List<Message> history,
               String input, EventEmitter emit) throws Exception;
}
