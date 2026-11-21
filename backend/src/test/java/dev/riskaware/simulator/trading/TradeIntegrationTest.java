package dev.riskaware.simulator.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.riskaware.simulator.market.MarketBook;
import dev.riskaware.simulator.security.TokenService;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties = "market.mode=MOCK_FIXED")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TradeIntegrationTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired TokenService tokens;
    @MockitoSpyBean TradeRepository repository;
    @MockitoSpyBean MarketBook market;
    UUID user;
    String bearer;

    @BeforeEach
    void openAccount() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        var response = http.perform(post("/api/auth/register").contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("email", email, "password", "trade-test-password"))))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(201);
        user = UUID.fromString(tree(response).get("id").asText());
        bearer = "Bearer " + tokens.issue(user).accessToken();
    }

    JsonNode tree(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsByteArray());
    }

    MockHttpServletResponse order(UUID key, String asset, String side, String quantity) throws Exception {
        return http.perform(post("/api/trades").header("Authorization", bearer).header("Idempotency-Key", key)
                .contentType("application/json").content(json.writeValueAsBytes(Map.of(
                        "asset", asset, "side", side, "quantity", quantity)))).andReturn().getResponse();
    }

    JsonNode read(String path) throws Exception {
        var response = http.perform(get(path).header("Authorization", bearer)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return tree(response);
    }

    void state(String cash, String held, int trades) {
        assertThat(db.queryForObject("select cash_usd from wallets where user_id=?", BigDecimal.class, user))
                .isEqualByComparingTo(cash);
        assertThat(db.queryForObject("select coalesce(sum(quantity),0) from holdings where user_id=?",
                BigDecimal.class, user)).isEqualByComparingTo(held);
        assertThat(db.queryForObject("select count(*) from trades where user_id=?", Integer.class, user)).isEqualTo(trades);
    }

    @Test
    void portfolioTracksSettledAverageCostAndIsolatesOwners() throws Exception {
        UUID firstOwner = user;
        assertThat(order(UUID.randomUUID(), "ETH", "BUY", "1").getStatus()).isEqualTo(200);
        var price = new BigDecimal("3500.00");
        doReturn(new MarketBook.Price(TradingApi.Asset.ETH, price, "COINBASE_USD", java.time.Instant.now()))
                .when(market).executable(TradingApi.Asset.ETH);
        assertThat(order(UUID.randomUUID(), "ETH", "BUY", "1").getStatus()).isEqualTo(200);
        assertThat(order(UUID.randomUUID(), "ETH", "SELL", "0.5").getStatus()).isEqualTo(200);
        var portfolio = read("/api/portfolio");
        var position = portfolio.get("positions").get(0);
        assertThat(portfolio.get("cashUsd").decimalValue()).isEqualByComparingTo("5750");
        assertThat(position.get("quantity").decimalValue()).isEqualByComparingTo("1.5");
        assertThat(position.get("averagePriceUsd").decimalValue()).isEqualByComparingTo("3000");
        assertThat(position.get("costUsd").decimalValue()).isEqualByComparingTo("4500");
        assertThat(order(UUID.randomUUID(), "ETH", "SELL", "1.5").getStatus()).isEqualTo(200);
        assertThat(read("/api/portfolio").get("positions").isEmpty()).isTrue();
        assertThat(order(UUID.randomUUID(), "ETH", "BUY", "1").getStatus()).isEqualTo(200);
        assertThat(read("/api/portfolio").get("positions").get(0).get("costUsd").decimalValue()).isEqualByComparingTo(price);
        openAccount();
        var other = read("/api/portfolio?userId=" + firstOwner);
        assertThat(other.get("cashUsd").decimalValue()).isEqualByComparingTo("10000");
        assertThat(other.get("positions").isEmpty()).isTrue();
    }

    @Test
    void outageBlocksNewOrdersButReplaysStoredLiveReceipt() throws Exception {
        var asset = TradingApi.Asset.BTC;
        var time = java.time.Instant.parse("2026-11-20T06:12:01.123456Z");
        var price = new BigDecimal("50000.12345678");
        doReturn(new MarketBook.Price(asset, price, "COINBASE_USD", time)).when(market).executable(asset);
        UUID key = UUID.randomUUID();
        var receipt = tree(order(key, "BTC", "BUY", "0.1"));
        doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "BTC price is STALE")).when(market).executable(asset);
        assertThat(order(UUID.randomUUID(), "BTC", "BUY", "0.1").getStatus()).isEqualTo(503);
        assertThat(tree(order(key, "BTC", "BUY", "0.10"))).isEqualTo(receipt);
        var stored = read("/api/trades").get("trades").get(0);
        assertThat(stored.get("priceTime").asText()).isEqualTo(time.toString());
        assertThat(stored.get("priceUsd").decimalValue()).isEqualByComparingTo(price);
        assertThat(stored.get("priceSource").asText()).isEqualTo("COINBASE_USD");
        state("4999.98", "0.1", 1);
    }

    @Test
    void retriesReplayOriginalAndConflictingReuseIsRejected() throws Exception {
        UUID key = UUID.randomUUID();
        var first = order(key, "BTC", "BUY", "0.1");
        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(tree(order(key, "BTC", "BUY", "0.10000000"))).isEqualTo(tree(first));
        assertThat(order(key, "ETH", "BUY", "0.1").getStatus()).isEqualTo(409);
        state("5000", "0.1", 1);
        var holding = read("/api/holdings").get(0);
        assertThat(holding.get("asset").asText()).isEqualTo("BTC");
        assertThat(holding.get("quantity").decimalValue()).isEqualByComparingTo("0.1");
        openAccount();
        assertThat(read("/api/trades").get("trades").size()).isZero();
        assertThat(read("/api/holdings").size()).isZero();
        assertThat(order(key, "BTC", "BUY", "0.1").getStatus()).isEqualTo(200);
    }

    @Test
    void invalidQuantityInsufficientCashAndOversellLeaveStateIntact() throws Exception {
        for (String quantity : List.of("-1", "0", "0.000000001", "0.00000001")) {
            assertThat(order(UUID.randomUUID(), "ETH", "BUY", quantity).getStatus()).isEqualTo(400);
        }
        assertThat(order(UUID.randomUUID(), "BTC", "BUY", "1").getStatus()).isEqualTo(409);
        assertThat(order(UUID.randomUUID(), "ETH", "SELL", "1").getStatus()).isEqualTo(409);
        state("10000", "0", 0);
    }

    @Test
    void fractionsSettleInCentsAndHistorySupportsFiltersAndPaging() throws Exception {
        assertThat(order(UUID.randomUUID(), "ETH", "BUY", "0.010001").getStatus()).isEqualTo(200);
        state("9974.99", "0.010001", 1);
        var sell = tree(order(UUID.randomUUID(), "ETH", "SELL", "0.010001"));
        assertThat(sell.get("priceSource").asText()).isEqualTo("MOCK_FIXED");
        assertThat(sell.get("totalUsd").decimalValue()).isEqualByComparingTo("25");
        state("9999.99", "0", 2);
        assertThat(read("/api/holdings").size()).isZero();
        assertThat(read("/api/trades?size=1").get("hasNext").asBoolean()).isTrue();
        assertThat(read("/api/trades?page=1&size=1").get("trades").get(0).get("side").asText()).isEqualTo("BUY");
        assertThat(read("/api/trades?asset=ETH&side=SELL&from=2000-01-01T00:00:00Z").get("trades").get(0)).isEqualTo(sell);
        assertThat(read("/api/trades?to=2000-01-01T00:00:00Z").get("trades").size()).isZero();
    }

    List<MockHttpServletResponse> race(UUID firstKey, UUID secondKey, String side) throws Exception {
        var start = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<Future<MockHttpServletResponse>>();
            for (UUID key : List.of(firstKey, secondKey)) {
                results.add(workers.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return order(key, "BTC", side, "0.15");
                }));
            }
            return List.of(results.get(0).get(20, TimeUnit.SECONDS), results.get(1).get(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void simultaneousOrdersCannotOverspendOrOversell() throws Exception {
        var buys = race(UUID.randomUUID(), UUID.randomUUID(), "BUY");
        assertThat(buys).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 409);
        state("2500", "0.15", 1);
        var sells = race(UUID.randomUUID(), UUID.randomUUID(), "SELL");
        assertThat(sells).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 409);
        state("10000", "0", 2);
    }

    @Test
    void simultaneousRetriesCommitOnlyOnce() throws Exception {
        UUID key = UUID.randomUUID();
        var responses = race(key, key, "BUY");
        assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsOnly(200);
        assertThat(tree(responses.get(0))).isEqualTo(tree(responses.get(1)));
        state("2500", "0.15", 1);
    }

    @Test
    void failureAfterHistoryInsertRollsBackAllWritesAndAllowsRetry() throws Exception {
        UUID key = UUID.randomUUID();
        doAnswer(call -> {
            call.callRealMethod();
            throw new IllegalStateException("injected after trade insert");
        }).doCallRealMethod().when(repository).append(eq(user), eq(key), any());
        assertThatThrownBy(() -> order(key, "ETH", "BUY", "1"))
                .hasRootCauseMessage("injected after trade insert");
        state("10000", "0", 0);
        assertThat(order(key, "ETH", "BUY", "1").getStatus()).isEqualTo(200);
        state("7500", "1", 1);
    }
}
