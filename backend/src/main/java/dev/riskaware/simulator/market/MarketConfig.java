package dev.riskaware.simulator.market;

import jakarta.validation.constraints.*;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.*;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.validation.annotation.Validated;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(MarketConfig.Settings.class)
class MarketConfig {
    enum Mode { LIVE, MOCK_FIXED }
    enum Provider { AUTO, BINANCE, COINBASE }

    @Validated
    @ConfigurationProperties("market")
    record Settings(@NotNull Mode mode, @NotNull Provider provider, @Min(1) @Max(120) int maxAgeSeconds) {}

    @Bean Clock marketClock() { return Clock.systemUTC(); }
    @Bean HttpClient marketHttp() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    }
}
