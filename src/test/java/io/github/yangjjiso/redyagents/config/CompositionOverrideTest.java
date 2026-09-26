package io.github.yangjjiso.redyagents.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.yangjjiso.redyagents.core.AgentConfig;
import io.github.yangjjiso.redyagents.core.AgentService;
import io.github.yangjjiso.redyagents.core.Runner;
import io.github.yangjjiso.redyagents.core.SessionStore;
import io.github.yangjjiso.redyagents.core.Turn;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "redy.cube.enabled=false")
class CompositionOverrideTest {
    @Autowired
    AgentService service;

    @Autowired
    SessionStore store;

    @Test
    @Timeout(10)
    void suppliedRunnerAndStoreReplaceDefaults() throws Exception {
        String sessionId = service.createSession(new AgentConfig("agent", "custom", "")).id();
        Turn turn = service.startTurn(sessionId, "hello");
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            Turn current = service.getTurn(sessionId, turn.id());
            if ("completed".equals(current.status())) {
                assertEquals("runner: hello", current.output());
                assertFalse(store.loadAll().isEmpty());
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("custom runner turn did not complete");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Overrides {
        @Bean
        Runner customRunner() {
            return (cancellation, session, history, input, emit) -> "runner: " + input;
        }

        @Bean
        SessionStore customStore() {
            return new SessionStore() {
                private final Map<String, byte[]> data = new LinkedHashMap<>();

                @Override
                public synchronized void save(String sessionId, byte[] snapshot) {
                    data.put(sessionId, snapshot.clone());
                }

                @Override
                public synchronized Map<String, byte[]> loadAll() {
                    return Map.copyOf(data);
                }
            };
        }
    }
}
