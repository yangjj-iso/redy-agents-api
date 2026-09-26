package io.github.yangjjiso.redyagents.core;

import java.util.List;
import java.util.concurrent.CancellationException;

/** Runs a turn on a worker thread and reports each state transition to the session owner. */
final class TurnExecution {
    interface State {
        EventEmitter emitter(Turn turn);

        void beforeAdvance(Turn turn);

        void pauseForFunction(Turn turn, CancellationToken cancellation, LoopProgress progress);

        void finishTurn(Turn turn, CancellationToken cancellation, String status, String output,
                        String error, List<Message> context);
    }

    private final Runner runner;
    private final ResumableRunner resumableRunner;
    private final State state;

    TurnExecution(Runner runner, ResumableRunner resumableRunner, State state) {
        this.runner = runner;
        this.resumableRunner = resumableRunner;
        this.state = state;
    }

    void runPlain(CancellationToken cancellation, Session session, List<Message> history, Turn turn) {
        execute(cancellation, turn, () -> {
            RunResult result = runner.runWithContext(
                    cancellation, session, history, turn.input(), state.emitter(turn));
            state.finishTurn(turn, cancellation, "completed", result.output(), "", result.context());
        });
    }

    void runResumable(CancellationToken cancellation, Session session,
                      LoopCheckpoint checkpoint, Turn turn) {
        execute(cancellation, turn, () -> {
            state.beforeAdvance(turn);
            LoopProgress progress = resumableRunner.advance(
                    cancellation, session, checkpoint, state.emitter(turn));
            cancellation.throwIfCancelled();
            if (progress.isCompleted()) {
                state.finishTurn(turn, cancellation, "completed", progress.output(), "",
                        progress.checkpoint().messages());
            } else {
                state.pauseForFunction(turn, cancellation, progress);
            }
        });
    }

    private void execute(CancellationToken cancellation, Turn turn, Work work) {
        cancellation.attachWorker();
        try {
            cancellation.throwIfCancelled();
            work.run();
        } catch (Throwable failure) {
            if (cancellation.isCancelled() || failure instanceof CancellationException) {
                state.finishTurn(turn, cancellation, "cancelled", "", "", null);
            } else {
                String message = failure.getMessage();
                state.finishTurn(turn, cancellation, "failed", "",
                        message == null || message.isEmpty()
                                ? failure.getClass().getSimpleName() : message, null);
            }
        } finally {
            cancellation.detachWorker();
            Thread.interrupted();
        }
    }

    @FunctionalInterface
    private interface Work {
        void run() throws Exception;
    }
}
