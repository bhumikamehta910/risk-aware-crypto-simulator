package dev.riskaware.simulator.market;

import dev.riskaware.simulator.trading.TradingApi.Asset;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

record HistoricalRisk(Asset asset, String source, LocalDate from, LocalDate through, int closes, int returns,
                      BigDecimal dailyVolatilityPct, BigDecimal drawdownPct, LocalDate peakDate, LocalDate troughDate) {
    private static final MathContext MC = MathContext.DECIMAL128;

    static HistoricalRisk measure(DailyHistory.Series series, int days) {
        List<ExchangeData.Close> prices = series.prices();
        if (prices.size() != days || days < 3 || series.source().equals("MOCK_FIXED")) {
            throw new IllegalArgumentException("A complete window of observed market closes is required");
        }
        var returns = new ArrayList<BigDecimal>();
        var peak = prices.getFirst();
        var maxDrop = BigDecimal.ZERO;
        LocalDate peakDate = null, troughDate = null;
        for (int i = 0; i < days; i++) {
            var current = prices.get(i);
            if (current.close().signum() <= 0) throw new IllegalArgumentException("Invalid close");
            if (i > 0) {
                var prior = prices.get(i - 1);
                if (!prior.date().plusDays(1).equals(current.date())) throw new IllegalArgumentException("Missing daily close");
                returns.add(current.close().subtract(prior.close()).divide(prior.close(), MC));
            }
            if (current.close().compareTo(peak.close()) > 0) peak = current;
            var drop = peak.close().subtract(current.close()).divide(peak.close(), MC);
            if (drop.compareTo(maxDrop) > 0) {
                maxDrop = drop;
                peakDate = peak.date();
                troughDate = current.date();
            }
        }
        var mean = returns.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(returns.size()), MC);
        var squared = returns.stream().map(r -> r.subtract(mean).pow(2)).reduce(BigDecimal.ZERO, BigDecimal::add);
        var deviation = squared.divide(BigDecimal.valueOf(returns.size() - 1), MC).sqrt(MC);
        return new HistoricalRisk(series.asset(), series.source(), prices.getFirst().date(), prices.getLast().date(),
                days, returns.size(), deviation.movePointRight(2), maxDrop.movePointRight(2), peakDate, troughDate);
    }
}
