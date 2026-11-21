package dev.riskaware.simulator.trading;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TradingApi {
    private TradingApi() {}

    public enum Asset { BTC, ETH }
    public enum Side { BUY, SELL }

    public record Order(@NotNull Asset asset, @NotNull Side side,
                        @NotNull @Positive @Digits(integer = 12, fraction = 8) BigDecimal quantity) {}
    public record Quote(Asset asset, BigDecimal priceUsd, String priceSource) {}
    public record Holding(Asset asset, BigDecimal quantity) {}
    public record Trade(UUID id, Asset asset, Side side, BigDecimal quantity, BigDecimal priceUsd,
                        BigDecimal totalUsd, String priceSource, Instant executedAt) {}
    public record History(List<Trade> trades, int page, int size, boolean hasNext) {}
}
