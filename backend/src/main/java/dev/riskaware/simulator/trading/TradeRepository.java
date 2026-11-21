package dev.riskaware.simulator.trading;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import static dev.riskaware.simulator.trading.TradingApi.*;

@Repository
class TradeRepository {
    private final JdbcClient db;
    private static final RowMapper<Trade> TRADE = (rs, row) -> new Trade(
            rs.getObject("id", UUID.class), Asset.valueOf(rs.getString("asset")),
            Side.valueOf(rs.getString("side")), rs.getBigDecimal("quantity"), rs.getBigDecimal("price_usd"),
            rs.getBigDecimal("total_usd"), rs.getString("price_source"), rs.getTimestamp("executed_at").toInstant());

    TradeRepository(JdbcClient db) { this.db = db; }

    BigDecimal lockCash(UUID user) {
        return db.sql("SELECT cash_usd FROM wallets WHERE user_id = ? FOR UPDATE")
                .param(user).query(BigDecimal.class).single();
    }

    Optional<Trade> previous(UUID user, UUID key) {
        return db.sql("SELECT * FROM trades WHERE user_id = ? AND request_key = ?")
                .params(user, key).query(TRADE).optional();
    }

    BigDecimal quantity(UUID user, Asset asset) {
        return db.sql("SELECT quantity FROM holdings WHERE user_id = ? AND asset = ?")
                .params(user, asset.name()).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
    }

    void updateCash(UUID user, BigDecimal cash) {
        db.sql("UPDATE wallets SET cash_usd = ? WHERE user_id = ?").params(cash, user).update();
    }

    void updateHolding(UUID user, Asset asset, BigDecimal before, BigDecimal after) {
        if (after.signum() == 0) {
            db.sql("DELETE FROM holdings WHERE user_id = ? AND asset = ?").params(user, asset.name()).update();
        } else if (before.signum() == 0) {
            db.sql("INSERT INTO holdings (user_id, asset, quantity) VALUES (?, ?, ?)")
                    .params(user, asset.name(), after).update();
        } else {
            db.sql("UPDATE holdings SET quantity = ? WHERE user_id = ? AND asset = ?")
                    .params(after, user, asset.name()).update();
        }
    }

    void append(UUID user, UUID key, Trade t) {
        db.sql("""
                INSERT INTO trades (id, user_id, request_key, asset, side, quantity,
                                    price_usd, total_usd, price_source, executed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(t.id(), user, key, t.asset().name(), t.side().name(), t.quantity(),
                        t.priceUsd(), t.totalUsd(), t.priceSource(), Timestamp.from(t.executedAt())).update();
    }

    List<Holding> holdings(UUID user) {
        return db.sql("SELECT asset, quantity FROM holdings WHERE user_id = ? ORDER BY asset")
                .param(user).query((rs, i) -> new Holding(Asset.valueOf(rs.getString("asset")),
                        rs.getBigDecimal("quantity"))).list();
    }

    History history(UUID user, Asset asset, Side side, Instant from, Instant to, int page, int size) {
        var sql = new StringBuilder("SELECT * FROM trades WHERE user_id = :user");
        var params = new HashMap<String, Object>();
        params.put("user", user);
        if (asset != null) {
            sql.append(" AND asset = :asset");
            params.put("asset", asset.name());
        }
        if (side != null) {
            sql.append(" AND side = :side");
            params.put("side", side.name());
        }
        if (from != null) {
            sql.append(" AND executed_at >= :fromTime");
            params.put("fromTime", Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND executed_at < :toTime");
            params.put("toTime", Timestamp.from(to));
        }
        sql.append(" ORDER BY executed_at DESC, id DESC LIMIT :limit OFFSET :offset");
        params.put("limit", size + 1);
        params.put("offset", (long) page * size);
        var rows = db.sql(sql.toString()).params(params).query(TRADE).list();
        boolean hasNext = rows.size() > size;
        return new History(hasNext ? rows.subList(0, size) : rows, page, size, hasNext);
    }
}
