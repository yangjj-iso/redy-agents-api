package io.github.yangjjiso.redyagents;

import io.github.yangjjiso.redyagents.core.AgentService;
import io.github.yangjjiso.redyagents.core.DemoModel;
import io.github.yangjjiso.redyagents.core.FileSessionStore;
import io.github.yangjjiso.redyagents.core.LoopRunner;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class RedyAgentsApplication {
    public static void main(String[] args) {
        SpringApplication.run(RedyAgentsApplication.class, args);
    }

    @Bean
    AgentService agentService(@Value("${redy.data-dir:.redy-data}") String dataDir) throws IOException {
        return new AgentService(new LoopRunner(new DemoModel()), new FileSessionStore(Path.of(dataDir)));
    }
}
