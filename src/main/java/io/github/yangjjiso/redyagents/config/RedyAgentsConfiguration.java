package io.github.yangjjiso.redyagents.config;

import io.github.yangjjiso.redyagents.core.AgentService;
import io.github.yangjjiso.redyagents.core.DemoModel;
import io.github.yangjjiso.redyagents.core.FileSessionStore;
import io.github.yangjjiso.redyagents.core.LoopRunner;
import io.github.yangjjiso.redyagents.core.Model;
import io.github.yangjjiso.redyagents.core.OpenAiCompatibleModel;
import io.github.yangjjiso.redyagents.core.Runner;
import io.github.yangjjiso.redyagents.core.SandboxProvisioner;
import io.github.yangjjiso.redyagents.core.SessionStore;
import io.github.yangjjiso.redyagents.core.Tool;
import io.github.yangjjiso.redyagents.cube.CubeSandboxBackend;
import io.github.yangjjiso.redyagents.cube.CubeSandboxClient;
import io.github.yangjjiso.redyagents.cube.SandboxTools;
import io.github.yangjjiso.redyagents.mcp.McpToolRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring composition root; the harness and Cube client remain framework independent. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CubeSandboxProperties.class, McpProperties.class,
        ModelProperties.class})
public class RedyAgentsConfiguration {
    @Bean
    @ConditionalOnProperty(name = "redy.model.base-url")
    @ConditionalOnMissingBean(Model.class)
    Model openAiCompatibleModel(ModelProperties properties) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalArgumentException("redy.model.api-key is required when base-url is set");
        }
        return new OpenAiCompatibleModel(properties.baseUrl(), properties.apiKey(),
                properties.requestTimeout());
    }

    @Bean
    @ConditionalOnProperty(name = "redy.cube.enabled", havingValue = "true")
    CubeSandboxBackend cubeSandboxBackend(CubeSandboxProperties properties) {
        return new CubeSandboxBackend(new CubeSandboxClient(properties.toClientConfig()));
    }

    @Bean(destroyMethod = "close")
    McpToolRegistry mcpToolRegistry(McpProperties properties) {
        return new McpToolRegistry(properties.toServerConfigs());
    }

    @Bean
    AgentService agentService(@Value("${redy.data-dir:.redy-data}") String dataDir,
                              ObjectProvider<Model> models,
                              ObjectProvider<Runner> runners,
                              ObjectProvider<SessionStore> stores,
                              ObjectProvider<SandboxProvisioner> provisioners,
                              ObjectProvider<CubeSandboxBackend> cubeBackend,
                              McpToolRegistry mcpTools) throws IOException {
        CubeSandboxBackend cube = cubeBackend.getIfAvailable();
        Runner runner = runners.getIfAvailable();
        if (runner == null) {
            Model model = models.getIfAvailable();
            Map<String, Tool> tools = new LinkedHashMap<>();
            if (cube != null) {
                tools.putAll(SandboxTools.create(cube));
            }
            mcpTools.tools().forEach((name, tool) -> {
                if (tools.putIfAbsent(name, tool) != null) {
                    throw new IllegalArgumentException("duplicate tool name: " + name);
                }
            });
            runner = new LoopRunner(model == null ? new DemoModel() : model,
                    tools, 8);
        }
        SessionStore store = stores.getIfAvailable();
        if (store == null) {
            store = new FileSessionStore(Path.of(dataDir));
        }
        return new AgentService(runner, store, provisioners.getIfAvailable());
    }
}
