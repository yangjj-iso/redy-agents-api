package io.github.yangjjiso.redyagents;

import io.github.yangjjiso.redyagents.core.AgentService;
import io.github.yangjjiso.redyagents.core.DemoModel;
import io.github.yangjjiso.redyagents.core.FileSessionStore;
import io.github.yangjjiso.redyagents.core.LoopRunner;
import io.github.yangjjiso.redyagents.core.SandboxTools;
import io.github.yangjjiso.redyagents.cube.CubeSandboxClient;
import io.github.yangjjiso.redyagents.cube.CubeSandboxConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class RedyAgentsApplication {
    public static void main(String[] args) {
        SpringApplication.run(RedyAgentsApplication.class, args);
    }

    @Bean
    @ConditionalOnProperty(name = "redy.cube.enabled", havingValue = "true")
    CubeSandboxBackend cubeSandboxBackend() {
        return new CubeSandboxBackend(new CubeSandboxClient(CubeSandboxConfig.fromEnvironment()));
    }

    @Bean
    AgentService agentService(@Value("${redy.data-dir:.redy-data}") String dataDir,
                              ObjectProvider<CubeSandboxBackend> cubeBackend) throws IOException {
        CubeSandboxBackend cube = cubeBackend.getIfAvailable();
        return new AgentService(new LoopRunner(new DemoModel(),
                cube == null ? Map.of() : SandboxTools.create(cube), 8),
                new FileSessionStore(Path.of(dataDir)), cube);
    }
}
