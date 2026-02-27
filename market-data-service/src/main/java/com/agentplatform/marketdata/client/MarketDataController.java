package com.agentplatform.marketdata.client;

import com.agentplatform.marketdata.model.MarketDataQuote;
import com.agentplatform.marketdata.provider.MarketDataProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/market-data")
public class MarketDataController {

    private final MarketDataProvider service;

    public MarketDataController(MarketDataProvider service) {
        this.service = service;
    }

    @GetMapping("/quote/{symbol}")
    public Mono<ResponseEntity<MarketDataQuote>> getQuote(@PathVariable String symbol) {
        return service.getQuote(symbol)
            .map(ResponseEntity::ok)
            .onErrorResume(e -> Mono.just(ResponseEntity.internalServerError().build()));
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
