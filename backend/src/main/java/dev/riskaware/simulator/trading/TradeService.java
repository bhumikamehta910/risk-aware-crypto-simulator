package dev.riskaware.simulator.trading;

import java.math.BigDecimal;
import dev.riskaware.simulator.market.MarketBook;
import org.springframework.transaction.annotation.Isolation;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static dev.riskaware.simulator.trading.TradingApi.*;

@Service
class TradeService {
    private final TradeRepository repository;

    private final MarketBook market;

    TradeService(TradeRepository repository, MarketBook market) {
        this.repository = repository;
        this.market = market;
    }

    @Transactional
    public Trade execute(UUID user, UUID key, Order order) {
        // All position writers take this existing row lock, even for a first purchase.
        BigDecimal cash = repository.lockCash(user);
        var prior = repository.previous(user, key);
        if (prior.isPresent()) {
            Trade trade = prior.get();
            if (trade.asset() != order.asset() || trade.side() != order.side()
                    || trade.quantity().compareTo(order.quantity()) != 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Key already used for a different order");
            }
            return trade;
        }

        var assessment = assess(user, cash, order);
        if (!assessment.violations().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, String.join(" ", assessment.violations()));
        }
        var quote = assessment.quote();
        var trade = new Trade(UUID.randomUUID(), order.asset(), order.side(), order.quantity().setScale(8), quote.priceUsd(),
                assessment.transactionUsd(), quote.source(), Instant.now().truncatedTo(ChronoUnit.MICROS), quote.priceTime());
        repository.updateCash(user, assessment.projected().cash());
        repository.updateHolding(user, order.asset(), assessment.held(), assessment.nextHeld());
        repository.append(user, key, trade);
        return trade;
    }

    private OrderAssessment assess(UUID user, BigDecimal cash, Order order) {
        return OrderAssessment.evaluate(order, cash, repository.holdings(user), repository.limits(user), market);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OrderAssessment preview(UUID user, Order order) { return assess(user, repository.cash(user), order); }

    public RiskLimits limits(UUID user) { return repository.limits(user); }

    @Transactional
    public RiskLimits updateLimits(UUID user, RiskLimits limits) {
        repository.lockCash(user);
        repository.saveLimits(user, limits);
        return limits;
    }

    public List<Holding> holdings(UUID user) { return repository.holdings(user); }

    public History history(UUID user, Asset asset, Side side, Instant from, Instant to, int page, int size) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must precede to");
        }
        return repository.history(user, asset, side, from, to, page, size);
    }
}
