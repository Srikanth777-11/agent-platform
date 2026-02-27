package com.agentplatform.scheduler.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class SchedulerConfig {

    @Value("${services.orchestrator.base-url}")
    private String orchestratorUrl;

    @Value("${services.history.base-url}")
    private String historyUrl;

    @Bean
    public WebClient orchestratorClient(WebClient.Builder builder) {
        return builder.baseUrl(orchestratorUrl).build();
    }

    /*@Bean
    public WebClient historyClient(WebClient.Builder builder) {
        return builder.baseUrl(historyUrl).build();
    }*/

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
}
