package dev.geduu.towntrade;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class TradeStore implements AutoCloseable {
    record Change(String player, Long balance, String stockKey, TradeRules.Stock stock,
                  String quotaPool, TradeRules.Quota quota, TradeRules.Order order,
                  FlightPermits.Credit permits, String deletedOrder) {
        Change(String player, Long balance, FlightPermits.Credit permits) {
            this(player, balance, null, null, null, null, null, permits, null);
        }

        Change(String player, Long balance, String stockKey, TradeRules.Stock stock,
               String quotaPool, TradeRules.Quota quota, TradeRules.Order order) {
            this(player, balance, stockKey, stock, quotaPool, quota, order, null, null);
        }
    }

    record Pending(String id, String action, Change change) {}

    static final class State {
        final Map<String, Long> balances = new HashMap<>();
        final Map<String, TradeRules.Stock> stocks = new HashMap<>();
        final Map<String, TradeRules.Quota> quotas = new HashMap<>();
        final Map<String, FlightPermits.Credit> permits = new HashMap<>();
        final Map<String, TradeRules.Order> orders = new HashMap<>();

        void apply(Change change) {
            if (change.balance() != null) balances.put(change.player(), change.balance());
            if (change.stock() != null) stocks.put(change.stockKey(), change.stock());
            if (change.quota() != null) quotas.put(quotaKey(change.player(), change.quotaPool()), change.quota());
            if (change.permits() != null) permits.put(change.player(), change.permits());
            if (change.order() != null) orders.put(change.order().id(), change.order());
            if (change.deletedOrder() != null) orders.remove(change.deletedOrder());
        }

        static String quotaKey(String player, String pool) {
            return player + "/" + pool;
        }
    }

    @FunctionalInterface
    interface SavedMarker {
        boolean isSaved(String player, String transaction) throws Exception;
    }

    private final Connection db;

    TradeStore(Path database) throws Exception {
        Files.createDirectories(database.getParent());
        Class.forName("org.sqlite.JDBC");
        db = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        try (var statement = db.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = FULL");
            statement.execute("PRAGMA busy_timeout = 2500");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS accounts (
                        id TEXT PRIMARY KEY, balance INTEGER NOT NULL CHECK(balance >= 0)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS stocks (
                        id TEXT PRIMARY KEY, count INTEGER NOT NULL CHECK(count >= 0), next INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS quotas (
                        id TEXT PRIMARY KEY, units INTEGER NOT NULL CHECK(units >= 0), at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS orders (
                        id TEXT PRIMARY KEY, expires INTEGER NOT NULL, data TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS pending (
                        id TEXT PRIMARY KEY, action TEXT NOT NULL, data TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS receipts (
                        id TEXT PRIMARY KEY, at INTEGER NOT NULL, action TEXT NOT NULL, data TEXT NOT NULL
                    )
                    """);
            statement.execute("CREATE TABLE IF NOT EXISTS permits (id TEXT PRIMARY KEY, data TEXT NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS order_expiry ON orders(expires)");
            statement.execute("CREATE INDEX IF NOT EXISTS receipt_time ON receipts(at)");
        }
    }

    State recoverAndLoad(SavedMarker marker, long now) throws Exception {
        var pending = new ArrayList<Pending>();
        try (var statement = db.createStatement();
             var rows = statement.executeQuery("SELECT id, action, data FROM pending ORDER BY rowid")) {
            while (rows.next()) {
                pending.add(new Pending(rows.getString(1), rows.getString(2),
                        TradeConfig.JSON.fromJson(rows.getString(3), Change.class)));
            }
        }
        for (Pending recovery : pending) {
            if (marker.isSaved(recovery.change().player(), recovery.id())) commit(recovery, now);
            else discard(recovery.id());
        }
        prune(now);
        migratePermits(now);
        return load();
    }

    private State load() throws SQLException {
        var state = new State();
        try (var statement = db.createStatement()) {
            try (var rows = statement.executeQuery("SELECT id, balance FROM accounts")) {
                while (rows.next()) state.balances.put(rows.getString(1), rows.getLong(2));
            }
            try (var rows = statement.executeQuery("SELECT id, count, next FROM stocks")) {
                while (rows.next()) state.stocks.put(rows.getString(1),
                        new TradeRules.Stock(rows.getInt(2), rows.getLong(3)));
            }
            try (var rows = statement.executeQuery("SELECT id, units, at FROM quotas")) {
                while (rows.next()) state.quotas.put(rows.getString(1),
                        new TradeRules.Quota(rows.getLong(2), rows.getLong(3)));
            }
            try (var rows = statement.executeQuery("SELECT id, data FROM permits")) {
                while (rows.next()) state.permits.put(rows.getString(1),
                        TradeConfig.JSON.fromJson(rows.getString(2), FlightPermits.Credit.class));
            }
            try (var rows = statement.executeQuery("SELECT id, data FROM orders")) {
                while (rows.next()) state.orders.put(rows.getString(1),
                        TradeConfig.JSON.fromJson(rows.getString(2), TradeRules.Order.class));
            }
        }
        return state;
    }

    private void migratePermits(long now) throws SQLException {
        var legacy = new HashMap<String, FlightPermits.Credit>();
        try (var statement = db.createStatement();
             var rows = statement.executeQuery("SELECT id, data FROM permits WHERE substr(ltrim(data), 1, 1) = '['")) {
            while (rows.next()) {
                var windows = TradeConfig.JSON.fromJson(rows.getString(2), FlightPermits.Window[].class);
                legacy.put(rows.getString(1), FlightPermits.Credit.migrate(List.of(windows), now));
            }
        }
        if (legacy.isEmpty()) return;
        db.setAutoCommit(false);
        try {
            for (var entry : legacy.entrySet()) {
                execute("UPDATE permits SET data = ? WHERE id = ?", TradeConfig.JSON.toJson(entry.getValue()), entry.getKey());
            }
            db.commit();
        } catch (SQLException failure) {
            db.rollback();
            throw failure;
        } finally {
            db.setAutoCommit(true);
        }
    }

    void prepare(Pending pending) throws SQLException {
        execute("INSERT INTO pending VALUES (?, ?, ?)", pending.id(), pending.action(),
                TradeConfig.JSON.toJson(pending.change()));
    }

    void discard(String id) throws SQLException {
        execute("DELETE FROM pending WHERE id = ?", id);
    }

    void commit(Pending pending, long now) throws SQLException {
        Change change = pending.change();
        db.setAutoCommit(false);
        try {
            int inserted = execute("INSERT OR IGNORE INTO receipts VALUES (?, ?, ?, ?)", pending.id(), now,
                    pending.action(), TradeConfig.JSON.toJson(change));
            if (inserted == 1) {
                if (change.balance() != null) execute("""
                        INSERT INTO accounts VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET balance = excluded.balance
                        """, change.player(), change.balance());
                if (change.stock() != null) execute("""
                        INSERT INTO stocks VALUES (?, ?, ?) ON CONFLICT(id) DO UPDATE
                        SET count = excluded.count, next = excluded.next
                        """, change.stockKey(), change.stock().count(), change.stock().next());
                if (change.quota() != null) execute("""
                        INSERT INTO quotas VALUES (?, ?, ?) ON CONFLICT(id) DO UPDATE
                        SET units = excluded.units, at = excluded.at
                        """, State.quotaKey(change.player(), change.quotaPool()),
                        change.quota().units(), change.quota().at());
                if (change.permits() != null) execute("""
                        INSERT INTO permits VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET data = excluded.data
                        """, change.player(), TradeConfig.JSON.toJson(change.permits()));
                if (change.order() != null) execute("""
                        INSERT INTO orders VALUES (?, ?, ?) ON CONFLICT(id) DO UPDATE
                        SET expires = excluded.expires, data = excluded.data
                        """, change.order().id(), change.order().expires(), TradeConfig.JSON.toJson(change.order()));
                if (change.deletedOrder() != null) execute("DELETE FROM orders WHERE id = ?", change.deletedOrder());
            }
            discard(pending.id());
            db.commit();
        } catch (SQLException failure) {
            db.rollback();
            throw failure;
        } finally {
            db.setAutoCommit(true);
        }
    }

    void prune(long now) throws SQLException {
        execute("DELETE FROM orders WHERE expires < ?", now - 7 * 24 * 60 * TradeRules.MINUTE);
        execute("DELETE FROM receipts WHERE at < ?", now - 90 * 24 * 60 * TradeRules.MINUTE);
    }

    private int execute(String sql, Object... parameters) throws SQLException {
        try (var statement = db.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            return statement.executeUpdate();
        }
    }

    @Override
    public void close() throws SQLException {
        db.close();
    }
}
