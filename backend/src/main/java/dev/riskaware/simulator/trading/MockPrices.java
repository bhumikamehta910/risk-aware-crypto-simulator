package dev.riskaware.simulator.trading;

import java.math.BigDecimal;
import static dev.riskaware.simulator.trading.TradingApi.*;

public final class MockPrices {
    private MockPrices() {}

    public static Quote quote(Asset asset) {
        var price = switch (asset) {
            case BTC -> new BigDecimal("50000.00");
            case ETH -> new BigDecimal("2500.00");
        };
        return new Quote(asset, price, "MOCK_FIXED");
    }
}
