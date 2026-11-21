package dev.riskaware.simulator.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    TradeService(TradeRepository repository) { this.repository = repository; }

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

        Quote quote = MockPrices.quote(order.asset());
        BigDecimal quantity = order.quantity().setScale(8);
        BigDecimal gross = quantity.multiply(quote.priceUsd());
        if (gross.compareTo(new BigDecimal("0.01")) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Minimum order value is one virtual cent");
        }

        boolean buy = order.side() == Side.BUY;
        BigDecimal total = gross.setScale(2, buy ? RoundingMode.CEILING : RoundingMode.FLOOR);
        BigDecimal held = repository.quantity(user, order.asset());
        BigDecimal nextCash = buy ? cash.subtract(total) : cash.add(total);
        BigDecimal nextHeld = buy ? held.add(quantity) : held.subtract(quantity);
        if (nextCash.signum() < 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient virtual cash");
        if (nextHeld.signum() < 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient " + order.asset() + " holdings");

        var trade = new Trade(UUID.randomUUID(), order.asset(), order.side(), quantity, quote.priceUsd(),
                total, quote.priceSource(), Instant.now().truncatedTo(ChronoUnit.MICROS));
        repository.updateCash(user, nextCash);
        repository.updateHolding(user, order.asset(), held, nextHeld);
        repository.append(user, key, trade);
        return trade;
    }

    public List<Holding> holdings(UUID user) { return repository.holdings(user); }

    public History history(UUID user, Asset asset, Side side, Instant from, Instant to, int page, int size) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must precede to");
        }
        return repository.history(user, asset, side, from, to, page, size);
    }
}
