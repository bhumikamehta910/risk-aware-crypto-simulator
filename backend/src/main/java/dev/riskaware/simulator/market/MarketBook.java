package dev.riskaware.simulator.market;

import dev.riskaware.simulator.trading.TradingApi.Asset;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static dev.riskaware.simulator.market.MarketConfig.*;

@Service
public class MarketBook {
    public record Price(Asset asset, BigDecimal priceUsd, String source, Instant priceTime) {}
    public record Row(Asset asset, Price quote, String status) {}
    public record Snapshot(String mode, String provider, List<Row> prices) {}

    private final EnumMap<Asset, Price> latest = new EnumMap<>(Asset.class);
    private final Settings settings;
    private final Clock clock;
    private Provider provider;
    private long generation;
    private boolean connected;

    MarketBook(Settings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
        provider = settings.provider() == Provider.AUTO ? Provider.BINANCE : settings.provider();
        if (mock()) {
            for (Asset asset : Asset.values()) {
                var quote = dev.riskaware.simulator.trading.MockPrices.quote(asset);
                latest.put(asset, new Price(asset, quote.priceUsd(), quote.priceSource(), clock.instant().truncatedTo(ChronoUnit.MICROS)));
            }
        }
    }

    boolean mock() { return settings.mode() == Mode.MOCK_FIXED; }
    synchronized Provider provider() { return provider; }

    synchronized long begin(Provider next) {
        provider = next;
        latest.clear();
        connected = true;
        return ++generation;
    }

    synchronized void disconnect(long session) {
        if (session == generation) connected = false;
    }

    synchronized void accept(long session, Price quote) {
        if (!connected || session != generation) return;
        validatePrice(quote.priceUsd());
        var now = clock.instant();
        var old = latest.get(quote.asset());
        if (quote.priceTime().isAfter(now) || (old != null && quote.priceTime().isBefore(old.priceTime()))) return;
        latest.put(quote.asset(), quote);
    }

    static BigDecimal validatePrice(BigDecimal value) {
        var decimal = value.stripTrailingZeros();
        if (decimal.signum() <= 0 || decimal.scale() > 12 || (long) decimal.precision() - decimal.scale() > 16) {
            throw new IllegalArgumentException("Invalid market price");
        }
        return value;
    }

    private String status(Price price) {
        if (mock()) return "MOCK_FIXED";
        if (price == null) return "UNAVAILABLE";
        if (!connected) return "DISCONNECTED";
        return price.priceTime().isBefore(clock.instant().minusSeconds(settings.maxAgeSeconds())) ? "STALE" : "LIVE";
    }

    public synchronized Price executable(Asset asset) {
        Price quote = latest.get(asset);
        String state = status(quote);
        if (!mock() && !state.equals("LIVE")) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, asset + " price is " + state);
        }
        return quote;
    }

    public synchronized Snapshot snapshot() {
        var rows = Arrays.stream(Asset.values()).map(a -> new Row(a, latest.get(a), status(latest.get(a)))).toList();
        return new Snapshot(settings.mode().name(), mock() ? "MOCK_FIXED" : provider.name(), rows);
    }
}
