package io.github.yangjjiso.redyagents.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.yangjjiso.redyagents.core.AgentService;
import io.github.yangjjiso.redyagents.core.SandboxProvisioner;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"redy.cube.enabled=true", "redy.cube.api-url=http://127.0.0.1:39001",
                "redy.cube.template-id=test-template", "redy.cube.proxy-node-ip=127.0.0.1",
                "redy.cube.request-timeout=7", "redy.cube.idle-timeout-seconds=420"})
class CubeConfigurationTest {
    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("redy.data-dir", () -> dataDir.toString());
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    CubeSandboxProperties properties;

    @Test
    void springConfigurationBindsCubeSettingsAndWiresProviderWithoutNetworkCall() {
        assertEquals(Duration.ofSeconds(7), properties.requestTimeout());
        assertEquals(420, properties.idleTimeoutSeconds());
        assertEquals("test-template", properties.toClientConfig().templateId());
        assertEquals(1, context.getBeansOfType(SandboxProvisioner.class).size());
        assertNotNull(context.getBean(AgentService.class));
    }
}
