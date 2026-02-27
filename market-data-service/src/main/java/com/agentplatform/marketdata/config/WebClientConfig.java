package com.agentplatform.marketdata.config;

import com.agentplatform.marketdata.client.MarketDataWebClient;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class WebClientConfig {

    // ── Active provider (switch comment to toggle) ────────────────────────────
    // TWELVE DATA (inactive):
    // @Value("${twelve-data.base-url:https://api.twelvedata.com}")
    // private String baseUrl;

    // ALPHA VANTAGE (active):
    @Value("${alpha-vantage.base-url:https://www.alphavantage.co}")
    private String baseUrl;

    @Bean
    public WebClient marketDataWebClient(WebClient.Builder builder) {
        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
            .responseTimeout(Duration.ofSeconds(15))
            .doOnConnected(conn ->
                conn.addHandlerLast(new ReadTimeoutHandler(15, TimeUnit.SECONDS))
            );

        return builder
            .baseUrl(baseUrl)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .filter(retryFilter())
            .filter(loggingFilter())
            .build();
    }

    @Bean
    public MarketDataWebClient marketDataClient(WebClient marketDataWebClient, ObjectMapper objectMapper) {
        return new MarketDataWebClient(marketDataWebClient, objectMapper);
    }

    // ── Angel One auth client (separate bean — base URL is the same Angel One host) ──
    @Value("${angel-one.base-url:https://apiconnect.angelone.in}")
    private String angelOneBaseUrl;

    @Bean
    public WebClient angelOneAuthClient(WebClient.Builder builder) {
        return builder.baseUrl(angelOneBaseUrl).build();
    }

    private ExchangeFilterFunction retryFilter() {
        return ExchangeFilterFunction.ofResponseProcessor(clientResponse -> {
            if (clientResponse.statusCode().is5xxServerError()) {
                return Mono.error(new RuntimeException("Market data server error: " + clientResponse.statusCode()));
            }
            return Mono.just(clientResponse);
        });
    }

    private ExchangeFilterFunction loggingFilter() {
        return ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
            String uri = clientRequest.url().toString();
            String sanitized = uri.replaceAll("apikey=[^&]+", "apikey=***");
            org.slf4j.LoggerFactory.getLogger(WebClientConfig.class)
                .debug("Outbound request: {} {}", clientRequest.method(), sanitized);
            return Mono.just(clientRequest);
        });
    }
}
