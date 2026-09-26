package io.github.yangjjiso.redyagents.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.yangjjiso.redyagents.core.AgentConfig;
import io.github.yangjjiso.redyagents.core.AgentService;
import io.github.yangjjiso.redyagents.core.Decision;
import io.github.yangjjiso.redyagents.core.Model;
import io.github.yangjjiso.redyagents.core.Turn;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "redy.cube.enabled=false")
class ModelOverrideTest {
    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("redy.data-dir", () -> dataDir.toString());
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    AgentService service;

    @Test
    @Timeout(10)
    void applicationModelReplacesDemoModel() throws Exception {
        assertEquals(1, context.getBeansOfType(Model.class).size());
        String sessionId = service.createSession(new AgentConfig("agent", "custom", "")).id();
        Turn turn = service.startTurn(sessionId, "hello");
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            Turn current = service.getTurn(sessionId, turn.id());
            if ("completed".equals(current.status())) {
                assertEquals("custom", current.output());
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("custom model turn did not complete");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CustomModelConfiguration {
        @Bean
        Model customModel() {
            return (cancellation, agent, messages) -> Decision.finalMessage("custom");
        }
    }
}
