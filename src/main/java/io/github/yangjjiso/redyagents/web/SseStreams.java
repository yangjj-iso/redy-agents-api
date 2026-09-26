package io.github.yangjjiso.redyagents.web;

import io.github.yangjjiso.redyagents.core.AgentService;
import io.github.yangjjiso.redyagents.core.Event;
import io.github.yangjjiso.redyagents.core.EventSubscription;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class SseStreams {
    private static final int QUEUE_CAPACITY = 1024;
    private final AgentService service;
    private final long heartbeatSeconds;
    private final ExecutorService writers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "redy-sse-writer");
        thread.setDaemon(true);
        return thread;
    });

    public SseStreams(AgentService service, @Value("${redy.sse.heartbeat-seconds:15}") long heartbeatSeconds) {
        this.service = service;
        this.heartbeatSeconds = Math.max(1, heartbeatSeconds);
    }

    public SseEmitter open(String sessionID, long after) {
        SseEmitter emitter = new SseEmitter(0L);
        LinkedBlockingQueue<Event> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<Future<?>> writer = new AtomicReference<>();
        EventSubscription subscription = service.subscribe(sessionID, after, event -> {
            if (!closed.get() && !queue.offer(event)) {
                emitter.completeWithError(new IllegalStateException("SSE subscriber is too slow"));
            }
        });
        Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) {
                subscription.close();
                Future<?> task = writer.get();
                if (task != null) {
                    task.cancel(true);
                }
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());
        writer.set(writers.submit(() -> {
            try {
                while (!closed.get()) {
                    Event event = queue.poll(heartbeatSeconds, TimeUnit.SECONDS);
                    if (event == null) {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                    } else {
                        emitter.send(SseEmitter.event()
                                .id(Long.toString(event.sequence()))
                                .name(event.type())
                                .data(event));
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException | IllegalStateException ignored) {
                // The client disconnected or Spring closed the emitter.
            } finally {
                cleanup.run();
            }
        }));
        return emitter;
    }

    @PreDestroy
    public void stop() {
        writers.shutdownNow();
    }
}
