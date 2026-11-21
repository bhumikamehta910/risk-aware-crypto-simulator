package dev.riskaware.simulator.market;

import dev.riskaware.simulator.trading.TradingApi.Asset;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static dev.riskaware.simulator.market.ExchangeData.Close;
import static dev.riskaware.simulator.market.MarketConfig.Provider;

@Service
class DailyHistory {
    record Series(Asset asset, String source, String interval, List<Close> prices) {}
    private record Key(Provider provider, Asset asset) {}
    private record Cached(LocalDate until, List<Close> prices, Instant expires) {}
    private final Map<Key, Cached> cache = new HashMap<>();
    private final Map<Provider, Instant> cooldown = new EnumMap<>(Provider.class);
    private final HttpClient http;
    private final MarketBook book;
    private final Clock clock;

    DailyHistory(HttpClient http, MarketBook book, Clock clock) {
        this.http = http;
        this.book = book;
        this.clock = clock;
    }

    // Only four cache keys. Serialize cache fills to avoid bursts of identical upstream requests.
    synchronized Series history(Asset asset, int days) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (book.mock()) {
            var quote = book.executable(asset);
            var rows = today.minusDays(days).datesUntil(today).map(d -> new Close(d, quote.priceUsd())).toList();
            return new Series(asset, quote.source(), "1d UTC", rows);
        }

        Provider provider = book.provider();
        var key = new Key(provider, asset);
        var cached = cache.get(key);
        if (cached == null || !cached.until().equals(today) || !clock.instant().isBefore(cached.expires())) {
            if (cooldown.getOrDefault(provider, Instant.MIN).isAfter(clock.instant())) {
                throw unavailable("Daily prices are cooling down after a provider failure");
            }
            var prices = fetch(provider, asset, today);
            cached = new Cached(today, prices, prices.size() == 90 ? Instant.MAX : clock.instant().plusSeconds(60));
            cache.put(key, cached);
        }
        var rows = cached.prices().stream().filter(c -> !c.date().isBefore(today.minusDays(days))).toList();
        if (rows.size() != days) throw unavailable("Provider daily history is incomplete for this window");
        return new Series(asset, ExchangeData.source(provider), "1d UTC", rows);
    }

    private List<Close> fetch(Provider provider, Asset asset, LocalDate today) {
        try {
            var request = HttpRequest.newBuilder(ExchangeData.candles(provider, asset, today))
                    .timeout(Duration.ofSeconds(8)).header("Accept", "application/json").GET().build();
            var result = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (result.statusCode() != 200) {
                long delay = result.statusCode() == 429 || result.statusCode() == 418 ? 60 : 10;
                String retry = result.headers().firstValue("Retry-After").orElse("");
                if (retry.matches("[0-9]{1,9}")) delay = Math.max(delay, Long.parseLong(retry));
                cooldown.put(provider, clock.instant().plusSeconds(delay));
                throw unavailable("Daily price provider returned HTTP " + result.statusCode());
            }
            var rows = ExchangeData.closes(provider, ExchangeData.JSON.readTree(result.body()), today);
            if (rows.isEmpty()) throw new IllegalArgumentException("Empty daily history");
            return rows;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            cooldown.put(provider, clock.instant().plusSeconds(10));
            throw unavailable("Daily price provider unavailable or returned invalid candles");
        }
    }

    private static ResponseStatusException unavailable(String reason) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reason);
    }
}
