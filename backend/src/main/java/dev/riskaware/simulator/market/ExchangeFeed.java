package dev.riskaware.simulator.market;

import jakarta.annotation.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import static dev.riskaware.simulator.market.MarketConfig.Provider;

@Component
class ExchangeFeed {
    private static final Logger LOG = LoggerFactory.getLogger(ExchangeFeed.class);
    private final MarketBook book;
    private final MarketConfig.Settings settings;
    private final HttpClient http;
    private Thread worker;

    ExchangeFeed(MarketBook book, MarketConfig.Settings settings, HttpClient http) {
        this.book = book;
        this.settings = settings;
        this.http = http;
    }

    @PostConstruct void start() {
        if (!book.mock()) worker = Thread.ofVirtual().name("market-feed").start(this::run);
    }

    @PreDestroy void stop() {
        if (worker != null) worker.interrupt();
    }

    private void run() {
        Provider provider = book.provider();
        int delay = 1;
        while (!Thread.currentThread().isInterrupted()) {
            long generation = book.begin(provider);
            var listener = new Listener(provider, generation);
            WebSocket socket = null;
            try {
                socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(6))
                        .buildAsync(ExchangeData.stream(provider), listener).get();
                if (provider == Provider.COINBASE) socket.sendText(ExchangeData.COINBASE_SUBSCRIPTION, true).join();
                // A socket can stay open while one subscription silently stops producing prices.
                while (!listener.closed.await(settings.maxAgeSeconds() + 2L, TimeUnit.SECONDS)) {
                    if (book.snapshot().prices().stream().anyMatch(row -> !row.status().equals("LIVE"))) break;
                    delay = 1;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                LOG.warn("{} market connection failed: {}", provider, ex.toString());
            } finally {
                book.disconnect(generation);
                if (socket != null) socket.abort();
            }

            if (settings.provider() == Provider.AUTO) {
                provider = provider == Provider.BINANCE ? Provider.COINBASE : Provider.BINANCE;
            }
            try {
                Thread.sleep(Duration.ofSeconds(delay));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            delay = Math.min(60, delay * 2);
        }
    }

    private final class Listener implements WebSocket.Listener {
        final CountDownLatch closed = new CountDownLatch(1);
        private final StringBuilder message = new StringBuilder();
        private final Provider provider;
        private final long generation;

        Listener(Provider provider, long generation) {
            this.provider = provider;
            this.generation = generation;
        }

        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence part, boolean last) {
            if (message.length() + part.length() > 65536) {
                finish();
                socket.abort();
                return null;
            }
            message.append(part);
            if (last) {
                try {
                    var tick = ExchangeData.tick(provider, ExchangeData.JSON.readTree(message.toString()));
                    if (tick != null) book.accept(generation, tick);
                } catch (Exception invalid) {
                    LOG.debug("Ignored invalid {} quote", provider, invalid);
                } finally {
                    message.setLength(0);
                }
            }
            socket.request(1);
            return null;
        }

        private void finish() {
            book.disconnect(generation);
            closed.countDown();
        }

        @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            finish();
            return null;
        }

        @Override public void onError(WebSocket socket, Throwable error) { finish(); }
    }
}
