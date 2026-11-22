package dev.riskaware.simulator.market;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
class PriceSocket extends TextWebSocketHandler {
    private final MarketBook book;
    private final ObjectMapper json;
    private final Set<WebSocketSession> clients = ConcurrentHashMap.newKeySet();
    private MarketBook.Snapshot previous;
    private long lastSent;

    PriceSocket(MarketBook book, ObjectMapper json) { this.book = book; this.json = json; }

    @Override public synchronized void afterConnectionEstablished(WebSocketSession client) throws IOException {
        client.sendMessage(new TextMessage(json.writeValueAsString(book.snapshot())));
        clients.add(client);
    }

    @Override public void afterConnectionClosed(WebSocketSession client, CloseStatus status) { clients.remove(client); }

    // Coalesce high-frequency trades into at most one public snapshot per second.
    @Scheduled(fixedDelay = 1000)
    synchronized void publish() throws IOException {
        if (clients.isEmpty()) return;
        var snapshot = book.snapshot();
        long now = System.nanoTime();
        if (snapshot.equals(previous) && now - lastSent < 10_000_000_000L) return;
        previous = snapshot;
        lastSent = now;
        var message = new TextMessage(json.writeValueAsString(snapshot));
        for (var client : clients) {
            try {
                client.sendMessage(message);
            } catch (Exception ex) {
                clients.remove(client);
                try { client.close(CloseStatus.SERVER_ERROR); } catch (IOException ignored) { }
            }
        }
    }
}
