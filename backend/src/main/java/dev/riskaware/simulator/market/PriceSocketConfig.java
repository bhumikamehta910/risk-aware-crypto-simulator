package dev.riskaware.simulator.market;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
class PriceSocketConfig implements WebSocketConfigurer {
    private final PriceSocket prices;
    PriceSocketConfig(PriceSocket prices) { this.prices = prices; }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(prices, "/ws/prices");
    }
}
