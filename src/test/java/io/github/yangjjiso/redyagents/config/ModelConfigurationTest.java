package io.github.yangjjiso.redyagents.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.yangjjiso.redyagents.core.Decision;
import io.github.yangjjiso.redyagents.core.Model;
import io.github.yangjjiso.redyagents.core.OpenAiCompatibleModel;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ModelConfigurationTest {
    @TempDir
    Path dataDir;

    @Test
    void bindsOptionalRemoteModelWithoutExposingKeyInDiagnostics() {
        new ApplicationContextRunner()
                .withUserConfiguration(RedyAgentsConfiguration.class)
                .withPropertyValues(
                        "redy.data-dir=" + dataDir,
                        "redy.model.base-url=http://127.0.0.1:9999/plan/v3",
                        "redy.model.api-key=fixture-secret",
                        "redy.model.request-timeout=7s")
                .run(context -> {
                    assertNotNull(context.getBean(Model.class));
                    assertInstanceOf(OpenAiCompatibleModel.class, context.getBean(Model.class));
                    ModelProperties properties = context.getBean(ModelProperties.class);
                    assertEquals(Duration.ofSeconds(7), properties.requestTimeout());
                    assertFalse(properties.toString().contains("fixture-secret"));
                });
    }

    @Test
    void applicationModelCanOverrideConfiguredProvider() {
        Model custom = (cancellation, agent, messages) -> Decision.finalMessage("custom");
        new ApplicationContextRunner()
                .withUserConfiguration(RedyAgentsConfiguration.class)
                .withBean(Model.class, () -> custom)
                .withPropertyValues(
                        "redy.data-dir=" + dataDir,
                        "redy.model.base-url=http://127.0.0.1:9999/plan/v3",
                        "redy.model.api-key=fixture-secret")
                .run(context -> assertEquals(custom, context.getBean(Model.class)));
    }
}
