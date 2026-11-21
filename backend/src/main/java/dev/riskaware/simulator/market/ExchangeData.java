package dev.riskaware.simulator.market;

import com.fasterxml.jackson.databind.*;
import dev.riskaware.simulator.trading.TradingApi.Asset;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static dev.riskaware.simulator.market.MarketConfig.Provider;

final class ExchangeData {
    private ExchangeData() {}
    static final ObjectReader JSON = new ObjectMapper().reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    static final String COINBASE_SUBSCRIPTION = """
            {"type":"subscribe","product_ids":["BTC-USD","ETH-USD"],"channels":["ticker"]}
            """;

    static URI stream(Provider provider) {
        return URI.create(provider == Provider.BINANCE
                ? "wss://data-stream.binance.vision/stream?streams=btcusdt@aggTrade/ethusdt@aggTrade"
                : "wss://ws-feed.exchange.coinbase.com");
    }

    static String source(Provider provider) { return provider == Provider.BINANCE ? "BINANCE_USDT" : "COINBASE_USD"; }

    static MarketBook.Price tick(Provider provider, JsonNode message) {
        boolean binance = provider == Provider.BINANCE;
        JsonNode data = binance ? message.path("data") : message;
        if (!data.path(binance ? "e" : "type").asText().equals(binance ? "aggTrade" : "ticker")) return null;
        Asset asset = switch (data.path(binance ? "s" : "product_id").asText()) {
            case "BTCUSDT", "BTC-USD" -> Asset.BTC;
            case "ETHUSDT", "ETH-USD" -> Asset.ETH;
            default -> null;
        };
        if (asset == null || !data.path(binance ? "s" : "product_id").asText()
                .equals(asset.name() + (binance ? "USDT" : "-USD"))) return null;
        Instant time = binance ? Instant.ofEpochMilli(integer(data.required("T")))
                : Instant.parse(data.required("time").asText());
        return new MarketBook.Price(asset, decimal(data.required(binance ? "p" : "price")), source(provider),
                time.truncatedTo(ChronoUnit.MICROS));
    }

    private static long integer(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong()) throw new IllegalArgumentException("Invalid timestamp");
        return node.longValue();
    }

    private static BigDecimal decimal(JsonNode node) {
        return MarketBook.validatePrice(new BigDecimal(node.asText()));
    }

    static URI candles(Provider provider, Asset asset, LocalDate end) {
        long until = end.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long start = until - 90 * 86400L;
        if (provider == Provider.BINANCE) {
            return URI.create("https://data-api.binance.vision/api/v3/klines?symbol=" + asset
                    + "USDT&interval=1d&limit=90&startTime=" + start * 1000 + "&endTime=" + (until * 1000 - 1));
        }
        return URI.create("https://api.exchange.coinbase.com/products/" + asset
                + "-USD/candles?granularity=86400&start=" + Instant.ofEpochSecond(start) + "&end=" + Instant.ofEpochSecond(until));
    }

    record Close(LocalDate date, BigDecimal close) {}

    static List<Close> closes(Provider provider, JsonNode data, LocalDate end) {
        if (!data.isArray()) throw new IllegalArgumentException("Expected candle array");
        var sorted = new TreeMap<LocalDate, BigDecimal>();
        for (JsonNode row : data) {
            long epoch = integer(row.required(0));
            Instant open = provider == Provider.BINANCE ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
            var day = open.atZone(ZoneOffset.UTC).toLocalDate();
            if (!day.atStartOfDay(ZoneOffset.UTC).toInstant().equals(open)) throw new IllegalArgumentException("Off-grid daily candle");
            if (day.isBefore(end.minusDays(90)) || !day.isBefore(end)) continue;
            if (sorted.put(day, decimal(row.required(4))) != null) throw new IllegalArgumentException("Duplicate daily candle");
        }
        return sorted.entrySet().stream().map(e -> new Close(e.getKey(), e.getValue())).toList();
    }
}
