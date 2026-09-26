package io.github.yangjjiso.redyagents.core;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Thread> worker = new AtomicReference<>();

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void throwIfCancelled() {
        if (isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("turn cancelled");
        }
    }

    void attachWorker() {
        Thread current = Thread.currentThread();
        worker.set(current);
        if (isCancelled()) {
            current.interrupt();
        }
    }

    void detachWorker() {
        worker.compareAndSet(Thread.currentThread(), null);
    }

    void cancel() {
        cancelled.set(true);
        Thread current = worker.get();
        if (current != null) {
            current.interrupt();
        }
    }
}
