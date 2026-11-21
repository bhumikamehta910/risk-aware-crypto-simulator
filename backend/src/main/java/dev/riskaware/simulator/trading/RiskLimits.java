package dev.riskaware.simulator.trading;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

record RiskLimits(@Positive @Digits(integer = 17, fraction = 2) BigDecimal maxOrderUsd,
                  @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal btcPct,
                  @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal ethPct) {
    BigDecimal cap(TradingApi.Asset asset) { return asset == TradingApi.Asset.BTC ? btcPct : ethPct; }
}
