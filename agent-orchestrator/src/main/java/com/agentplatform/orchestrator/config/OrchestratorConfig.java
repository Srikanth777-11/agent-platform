package com.agentplatform.orchestrator.config;

import com.agentplatform.common.consensus.ConsensusEngine;
import com.agentplatform.common.consensus.PerformanceWeightedConsensusStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class OrchestratorConfig {

    @Value("${services.market-data.base-url}")
    private String marketDataUrl;

    @Value("${services.analysis-engine.base-url}")
    private String analysisEngineUrl;

    @Value("${services.notification.base-url}")
    private String notificationUrl;

    @Value("${services.history.base-url}")
    private String historyUrl;

    @Bean
    public WebClient marketDataClient(WebClient.Builder builder) {
        return builder.baseUrl(marketDataUrl).build();
    }

    @Bean
    public WebClient analysisEngineClient(WebClient.Builder builder) {
        return builder.baseUrl(analysisEngineUrl).build();
    }

    @Bean
    public WebClient notificationClient(WebClient.Builder builder) {
        return builder.baseUrl(notificationUrl).build();
    }

    @Bean
    public WebClient historyClient(WebClient.Builder builder) {
        return builder.baseUrl(historyUrl).build();
    }

    @Bean
    public ConsensusEngine consensusEngine() {
        return new PerformanceWeightedConsensusStrategy();
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
}
