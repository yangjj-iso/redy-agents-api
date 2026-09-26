package io.github.yangjjiso.redyagents.config;

import io.github.yangjjiso.redyagents.core.AgentService;
import io.github.yangjjiso.redyagents.core.DemoModel;
import io.github.yangjjiso.redyagents.core.FileSessionStore;
import io.github.yangjjiso.redyagents.core.LoopRunner;
import io.github.yangjjiso.redyagents.core.Model;
import io.github.yangjjiso.redyagents.cube.CubeSandboxBackend;
import io.github.yangjjiso.redyagents.cube.CubeSandboxClient;
import io.github.yangjjiso.redyagents.cube.SandboxTools;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring composition root; the harness and Cube client remain framework independent. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CubeSandboxProperties.class)
public class RedyAgentsConfiguration {
    @Bean
    @ConditionalOnProperty(name = "redy.cube.enabled", havingValue = "true")
    CubeSandboxBackend cubeSandboxBackend(CubeSandboxProperties properties) {
        return new CubeSandboxBackend(new CubeSandboxClient(properties.toClientConfig()));
    }

    @Bean
    AgentService agentService(@Value("${redy.data-dir:.redy-data}") String dataDir,
                              ObjectProvider<Model> models,
                              ObjectProvider<CubeSandboxBackend> cubeBackend) throws IOException {
        CubeSandboxBackend cube = cubeBackend.getIfAvailable();
        Model model = models.getIfAvailable();
        return new AgentService(new LoopRunner(model == null ? new DemoModel() : model,
                cube == null ? Map.of() : SandboxTools.create(cube), 8),
                new FileSessionStore(Path.of(dataDir)), cube);
    }
}
