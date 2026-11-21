package dev.riskaware.simulator.trading;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static dev.riskaware.simulator.trading.TradingApi.*;

@Service
class PortfolioService {
    record Position(Asset asset, BigDecimal quantity, BigDecimal costUsd, BigDecimal averagePriceUsd) {}
    record Portfolio(BigDecimal cashUsd, List<Position> positions) {}

    private final TradeRepository repository;
    PortfolioService(TradeRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Portfolio load(UUID owner) {
        BigDecimal cash = repository.cash(owner);
        var positions = new EnumMap<Asset, Position>(Asset.class);
        for (Trade trade : repository.ledger(owner)) {
            var prior = positions.getOrDefault(trade.asset(),
                    new Position(trade.asset(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
            boolean buy = trade.side() == Side.BUY;
            var quantity = buy ? prior.quantity().add(trade.quantity()) : prior.quantity().subtract(trade.quantity());
            if (quantity.signum() == 0) {
                positions.remove(trade.asset());
                continue;
            }
            var cost = buy ? prior.costUsd().add(trade.totalUsd())
                    : prior.costUsd().multiply(quantity).divide(prior.quantity(), MathContext.DECIMAL128);
            var average = cost.divide(quantity, MathContext.DECIMAL128);
            positions.put(trade.asset(), new Position(trade.asset(), quantity, cost, average));
        }
        return new Portfolio(cash, List.copyOf(positions.values()));
    }
}
