package dev.riskaware.simulator.market;

import dev.riskaware.simulator.trading.TradingApi.Asset;
import java.math.BigDecimal;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static dev.riskaware.simulator.market.MarketConfig.*;
import static dev.riskaware.simulator.market.ExchangeData.JSON;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketDataTest {
    private final Instant now = Instant.parse("2026-02-03T00:01:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final Settings settings = new Settings(Mode.LIVE, Provider.AUTO, 20);
    private final MarketBook book = new MarketBook(settings, clock);

    @Test
    void parsesExchangeTradeTimeAndExactPriceFromBothFeeds() throws Exception {
        var binance = ExchangeData.tick(Provider.BINANCE, JSON.readTree("""
                {"data":{"e":"aggTrade","s":"BTCUSDT","p":"65000.12345678","T":1704067200123,"E":1704067200200}}
                """));
        assertThat(binance).isEqualTo(new MarketBook.Price(Asset.BTC, new BigDecimal("65000.12345678"),
                "BINANCE_USDT", Instant.parse("2024-01-01T00:00:00.123Z")));
        var coinbase = ExchangeData.tick(Provider.COINBASE, JSON.readTree("""
                {"type":"ticker","product_id":"ETH-USD","price":"3500.12","time":"2026-02-03T00:01:00Z"}
                """));
        assertThat(coinbase.asset()).isEqualTo(Asset.ETH);
        assertThat(coinbase.priceTime()).isEqualTo(now);
        assertThat(ExchangeData.tick(Provider.COINBASE, JSON.readTree("{\"type\":\"subscriptions\"}"))).isNull();
        for (String value : List.of("0", "-3", "0.0000000000001", "1E16")) {
            assertThatThrownBy(() -> MarketBook.validatePrice(new BigDecimal(value))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private MarketBook.Price price(Instant time) {
        return new MarketBook.Price(Asset.BTC, new BigDecimal("45000"), "BINANCE_USDT", time);
    }

    @Test
    void freshnessUsesExchangeTimeAndDisconnectInvalidatesEvenFreshQuotes() {
        long session = book.begin(Provider.BINANCE);
        assertThatThrownBy(() -> book.executable(Asset.BTC)).hasMessageContaining("UNAVAILABLE");
        book.accept(session, price(now.minusSeconds(21)));
        assertThatThrownBy(() -> book.executable(Asset.BTC)).hasMessageContaining("STALE");
        book.accept(session, price(now));
        book.accept(session, price(now.minusSeconds(1)));
        book.accept(session, price(now.plusSeconds(30)));
        assertThat(book.executable(Asset.BTC)).isEqualTo(price(now));
        book.disconnect(session);
        assertThatThrownBy(() -> book.executable(Asset.BTC)).hasMessageContaining("DISCONNECTED");
        book.begin(Provider.COINBASE);
        book.accept(session, price(now));
        assertThatThrownBy(() -> book.executable(Asset.BTC)).hasMessageContaining("UNAVAILABLE");
    }

    @Test
    void failedHandshakeRetriesWithAlternativeAndSubscribes() throws Exception {
        HttpClient http = mock();
        WebSocket.Builder builder = mock(RETURNS_SELF);
        WebSocket socket = mock();
        when(http.newWebSocketBuilder()).thenReturn(builder);
        when(builder.buildAsync(eq(ExchangeData.stream(Provider.BINANCE)), any()))
                .thenReturn(CompletableFuture.failedFuture(new java.io.IOException("feed unavailable")));
        when(builder.buildAsync(eq(ExchangeData.stream(Provider.COINBASE)), any()))
                .thenReturn(CompletableFuture.completedFuture(socket));
        when(socket.sendText(anyString(), eq(true))).thenReturn(CompletableFuture.completedFuture(socket));
        var feed = new ExchangeFeed(book, settings, http);
        feed.start();
        try {
            verify(socket, timeout(5000)).sendText(ExchangeData.COINBASE_SUBSCRIPTION, true);
            assertThat(book.snapshot().mode()).isEqualTo("LIVE");
            assertThatThrownBy(() -> book.executable(Asset.ETH)).hasMessageContaining("UNAVAILABLE");
        } finally { feed.stop(); }
    }

    @SuppressWarnings("unchecked")
    @Test
    void dailySeriesSortsCompletedUtcDaysAndCachesTheResponse() throws Exception {
        book.begin(Provider.COINBASE);
        var http = mock(HttpClient.class);
        HttpResponse<String> response = mock();
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        long today = LocalDate.now(clock).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        when(response.body()).thenReturn("[[" + today + ",1,2,1,2],[" + (today-86400)
                + ",1,2,1,1.234567890123],[" + (today-172800) + ",1,2,1,1.1]]");
        var history = new DailyHistory(http, book, clock);
        var rows = history.history(Asset.BTC, 2).prices();
        assertThat(rows).extracting(ExchangeData.Close::date)
                .containsExactly(LocalDate.parse("2026-02-01"), LocalDate.parse("2026-02-02"));
        assertThat(rows.get(1).close()).isEqualByComparingTo("1.234567890123");
        assertThat(history.history(Asset.BTC, 1).prices()).hasSize(1);
        assertThatThrownBy(() -> history.history(Asset.BTC, 3)).hasMessageContaining("incomplete");
        verify(http).send(any(), any(HttpResponse.BodyHandler.class));
        var binance = ExchangeData.closes(Provider.BINANCE, JSON.readTree("[[" + ((today-86400)*1000)
                + ",1,2,1,\"42000.01\"]]"), LocalDate.now(clock));
        assertThat(binance.getFirst().close()).isEqualByComparingTo("42000.01");
    }

    @SuppressWarnings("unchecked")
    @Test
    void providerRateLimitBlocksRepeatedRequestsAcrossAssets() throws Exception {
        var http = mock(HttpClient.class);
        HttpResponse<String> response = mock();
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(response.statusCode()).thenReturn(429);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Retry-After", List.of("120")), (k,v) -> true));
        var history = new DailyHistory(http, book, clock);
        assertThatThrownBy(() -> history.history(Asset.BTC, 30)).hasMessageContaining("429");
        assertThatThrownBy(() -> history.history(Asset.ETH, 30)).hasMessageContaining("cooling down");
        verify(http).send(any(), any(HttpResponse.BodyHandler.class));
    }
}
