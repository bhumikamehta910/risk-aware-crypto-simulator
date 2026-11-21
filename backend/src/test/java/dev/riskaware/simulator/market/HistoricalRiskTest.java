package dev.riskaware.simulator.market;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.riskaware.simulator.trading.TradingApi.Asset;
import static org.assertj.core.api.Assertions.*;

class HistoricalRiskTest {
    private final LocalDate start = LocalDate.of(2026, 1, 1);
    private DailyHistory.Series series(int... values) {
        var closes = new ArrayList<ExchangeData.Close>();
        for (int i = 0; i < values.length; i++) closes.add(new ExchangeData.Close(start.plusDays(i), BigDecimal.valueOf(values[i])));
        return new DailyHistory.Series(Asset.BTC, "BINANCE_USDT", "1d UTC", closes);
    }

    @Test void knownReturnsAndPeakToTrough() {
        var metric = HistoricalRisk.measure(series(100, 110, 99, 99, 99, 99, 99), 7);
        assertThat(metric.returns()).isEqualTo(6);
        assertThat(metric.dailyVolatilityPct().doubleValue()).isCloseTo(6.32455532, within(0.00000001));
        assertThat(metric.drawdownPct()).isEqualByComparingTo("10");
        assertThat(metric.peakDate()).isEqualTo(start.plusDays(1));
        assertThat(metric.troughDate()).isEqualTo(start.plusDays(2));
    }

    @Test void risingClosesHaveNoDrawdownAndConstantReturnsHaveZeroVolatility() {
        var metric = HistoricalRisk.measure(series(10,20,40,80,160,320,640), 7);
        assertThat(metric.dailyVolatilityPct()).isZero();
        assertThat(metric.drawdownPct()).isZero();
        assertThat(metric.peakDate()).isNull();
        assertThat(metric.troughDate()).isNull();
    }

    @Test void drawdownUsesAnEarlierPeakEvenAcrossAPartialRecovery() {
        var metric = HistoricalRisk.measure(series(80,100,88,96,75,110,105), 7);
        assertThat(metric.drawdownPct()).isEqualByComparingTo("25");
        assertThat(metric.peakDate()).isEqualTo(start.plusDays(1));
        assertThat(metric.troughDate()).isEqualTo(start.plusDays(4));
    }

    @Test void missingOrDemoClosesCannotProduceStatistics() {
        var series = series(100,110,120,125,126,121,122);
        assertThatIllegalArgumentException().isThrownBy(() -> HistoricalRisk.measure(series,30));
        assertThatIllegalArgumentException().isThrownBy(() -> HistoricalRisk.measure(
                new DailyHistory.Series(Asset.BTC,"MOCK_FIXED","1d UTC",series.prices()),7));
        var gap = new ArrayList<>(series.prices());
        gap.set(6, new ExchangeData.Close(start.plusDays(7), BigDecimal.TEN));
        assertThatIllegalArgumentException().isThrownBy(() -> HistoricalRisk.measure(
                new DailyHistory.Series(Asset.BTC,"COINBASE_USD","1d UTC",gap),7));
    }
}
