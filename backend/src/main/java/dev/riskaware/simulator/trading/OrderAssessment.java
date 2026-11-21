package dev.riskaware.simulator.trading;

import dev.riskaware.simulator.market.MarketBook;
import java.math.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static dev.riskaware.simulator.trading.TradingApi.*;

record OrderAssessment(MarketBook.Price quote, BigDecimal transactionUsd, Exposure current, Exposure projected,
                       BigDecimal held, BigDecimal nextHeld, RiskLimits limits, List<String> violations) {
    record Exposure(BigDecimal cash, BigDecimal btc, BigDecimal eth, BigDecimal total) {
        Exposure(BigDecimal cash, BigDecimal btc, BigDecimal eth) { this(cash, btc, eth, cash.add(btc).add(eth)); }
    }

    static OrderAssessment evaluate(Order order, BigDecimal cash, List<Holding> holdings,
                                    RiskLimits limits, MarketBook market) {
        // Capture the traded asset and every other held asset under one in-memory feed generation.
        var quantities = new EnumMap<Asset, BigDecimal>(Asset.class);
        holdings.forEach(h -> quantities.put(h.asset(), h.quantity()));
        BigDecimal held = quantities.getOrDefault(order.asset(), BigDecimal.ZERO);
        var values = new EnumMap<Asset, BigDecimal>(Asset.class);
        MarketBook.Price quote;
        synchronized (market) {
            quote = market.executable(order.asset());
            for (var holding : holdings) {
                var price = holding.asset() == order.asset() ? quote : market.executable(holding.asset());
                values.put(holding.asset(), holding.quantity().multiply(price.priceUsd()));
            }
        }
        var before = new Exposure(cash, values.getOrDefault(Asset.BTC, BigDecimal.ZERO), values.getOrDefault(Asset.ETH, BigDecimal.ZERO));
        boolean buy = order.side() == Side.BUY;
        BigDecimal gross = order.quantity().multiply(quote.priceUsd());
        if (gross.compareTo(new BigDecimal("0.01")) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Minimum order value is one virtual cent");
        }
        var amount = gross.setScale(2, buy ? RoundingMode.CEILING : RoundingMode.FLOOR);
        var nextCash = buy ? cash.subtract(amount) : cash.add(amount);
        var nextHeld = buy ? held.add(order.quantity()) : held.subtract(order.quantity());
        if (nextCash.signum() < 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient virtual cash");
        if (nextHeld.signum() < 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient " + order.asset() + " holdings");
        var nextValue = nextHeld.multiply(quote.priceUsd());
        values.put(order.asset(), nextValue);
        var after = new Exposure(nextCash, values.getOrDefault(Asset.BTC, BigDecimal.ZERO), values.getOrDefault(Asset.ETH, BigDecimal.ZERO));
        List<String> violations = new ArrayList<>();
        if (limits.maxOrderUsd() != null && amount.compareTo(limits.maxOrderUsd()) > 0) {
            violations.add("Order value $" + amount + " exceeds your $" + limits.maxOrderUsd() + " maximum.");
        }
        // Compare before rounding percentages. Sales can unwind an already concentrated holding.
        if (buy && nextValue.movePointRight(2).compareTo(after.total().multiply(limits.cap(order.asset()))) > 0) {
            var pct = nextValue.movePointRight(2).divide(after.total(), 4, RoundingMode.HALF_UP);
            violations.add(order.asset() + " would exceed your " + limits.cap(order.asset()) + "% allocation limit (projected "
                    + pct.stripTrailingZeros().toPlainString() + "%, rounded).");
        }
        return new OrderAssessment(quote, amount, before, after, held, nextHeld, limits, List.copyOf(violations));
    }
}
