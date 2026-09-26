package io.github.yangjjiso.redyagents;

import io.github.yangjjiso.redyagents.core.AgentService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class RedyAgentsApplication {
    public static void main(String[] args) {
        SpringApplication.run(RedyAgentsApplication.class, args);
    }

    @Bean
    AgentService agentService() {
        return new AgentService();
    }
}
