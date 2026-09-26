package io.github.yangjjiso.redyagents.core;

import java.util.List;

/** A runner whose turn can be checkpointed and resumed after an external function result. */
public interface ResumableRunner extends Runner {
    LoopCheckpoint start(List<Message> history, String input);

    LoopProgress advance(CancellationToken cancellation, Session session,
                         LoopCheckpoint checkpoint, EventEmitter emit) throws Exception;

    LoopCheckpoint resumeExternal(LoopCheckpoint checkpoint, ToolResult result, EventEmitter emit);
}
