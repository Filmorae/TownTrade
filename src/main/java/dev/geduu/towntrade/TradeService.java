package dev.geduu.towntrade;

import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import dev.geduu.towntrade.mixin.PlayerListAccess;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

final class TradeService {
    private static final long SESSION_LIMIT = 30 * TradeRules.MINUTE;
    private static final int SERVER_ORDER_LIMIT = 1024;
    private static final long RESULT_HISTORY = 7 * 24 * 60 * TradeRules.MINUTE;

    private static final class Session {
        final String token = UUID.randomUUID().toString();
        final TradeWire.View view;
        final String pool;
        int sequence;
        long expires;
        long refreshAt = TradeRules.NO_TIME;
        long lastRequest;
        EnchantDesk.Inspection enchant;

        Session(TradeWire.View view, String pool, long now) {
            this.view = view;
            this.pool = pool;
            expires = view == TradeWire.View.INFO ? TradeRules.NO_TIME : now + SESSION_LIMIT;
        }
    }

    private record ListingData(TradeConfig.Product rule, ItemStack item) {}
    private record Deal(TradeStore.Change change, InventoryPlan inventory) {}

    private static final class Rejected extends RuntimeException {
        final String reason;
        Rejected(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }
    }

    @FunctionalInterface
    private interface DatabaseWork<T> {
        T run() throws Exception;
    }

    private final MinecraftServer server;
    private final Path playerData;
    private final TradeConfig config;
    private final ServiceConfig serviceConfig;
    private final EnchantDesk enchantDesk;
    private final FlightPermits flight = new FlightPermits();
    private final ZoneId timezone;
    private final SeasonPrices seasons = new SeasonPrices();
    private final Map<String, ListingData> products = new LinkedHashMap<>();
    private final Map<String, ListingData> stockedItems = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<String, Set<String>> notified = new HashMap<>();
    private final List<ItemStack> coins;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("towntrade-database").factory());

    private TradeStore store;
    private TradeStore.State state;
    private boolean busy;
    private boolean unavailable;
    private boolean closing;
    private long nextSweep;
    private long nextPrune;
    private String season = "";

    TradeService(MinecraftServer server, TradeConfig config, ServiceConfig serviceConfig) {
        this.server = server;
        this.config = config;
        this.serviceConfig = serviceConfig;
        enchantDesk = new EnchantDesk(serviceConfig);
        timezone = ZoneId.of(config.timezone());
        playerData = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        coins = config.coins().stream().map(coin -> TradeItems.stack(coin.item(), null)).toList();
        if (coins.stream().anyMatch(ItemStack::isEmpty)) {
            TownTrade.LOGGER.error("Town Trade requires the configured coin items: {}", config.coins());
        }
        for (var product : config.products()) {
            var item = TradeItems.stack(product.item(), product.potion());
            if (item.isEmpty()) {
                if (product.item().startsWith("minecraft:")) {
                    TownTrade.LOGGER.warn("Town Trade skipped unknown vanilla product {}", product.id());
                }
                continue;
            }
            var entry = new ListingData(product, item);
            products.put(product.id(), entry);
            if (product.stocked()) stockedItems.put(itemKey(product), entry);
        }
        season = seasons.season(server);
        nextPrune = System.currentTimeMillis() + 60 * TradeRules.MINUTE;
        background(() -> {
            store = new TradeStore(server.getWorldPath(LevelResource.ROOT).resolve("towntrade/economy.sqlite"));
            return store.recoverAndLoad(this::isSaved, System.currentTimeMillis());
        }).whenComplete((saved, failure) -> server.execute(() -> {
            if (closing) return;
            if (failure != null) {
                fail(failure);
                return;
            }
            state = saved;
            TownTrade.LOGGER.info("Town Trade ready with {} resolved products", products.size());
            tick(true);
        }));
    }

    boolean open(ServerPlayer player, TradeWire.View view, String pool) {
        if (unavailable || state == null || closing) {
            player.sendSystemMessage(message("offline"));
            return false;
        }
        if (!ServerPlayNetworking.canSend(player, TradeWire.OpenPacket.TYPE)) {
            player.sendSystemMessage(message("missing_client"));
            return false;
        }
        var session = new Session(view, pool, System.currentTimeMillis());
        sessions.put(player.getUUID(), session);
        var offers = offers(session);
        var data = new TradeWire.ScreenOpen(session.token, view, pool, offers, board(player, session), config.coins());
        ServerPlayNetworking.send(player, new TradeWire.OpenPacket(TradeConfig.JSON.toJson(data)));
        return true;
    }

    void handle(ServerPlayer player, TradeWire.Request request) {
        var session = sessions.get(player.getUUID());
        if (session == null || request == null || !session.token.equals(request.token())
                || request.sequence() <= session.sequence || request.action() == null
                || request.action().length() > 20 || session.expires < System.currentTimeMillis()) return;
        session.sequence = request.sequence();
        if (request.action().equals("close")) {
            sessions.remove(player.getUUID());
            return;
        }
        if (unavailable || closing || state == null) {
            player.sendSystemMessage(message("offline"));
            return;
        }
        long now = System.currentTimeMillis();
        if (session.view != TradeWire.View.INFO) session.expires = now + SESSION_LIMIT;
        if (now - session.lastRequest < 75) return;
        session.lastRequest = now;
        if (request.action().equals("refresh")) {
            send(player, session, "updated", true);
            return;
        }
        if (session.view == TradeWire.View.INFO) return;
        if (session.view == TradeWire.View.ENCHANT && request.action().equals("inspect")) {
            session.enchant = enchantDesk.inspect(player, request.count());
            send(player, session, session.enchant == null ? "inventory_changed" : "updated", true);
            return;
        }
        if (busy) {
            send(player, session, "busy", true);
            return;
        }
        try {
            var deal = decide(player, session, request, now);
            start(player, session, request.action(), deal);
        } catch (Rejected rejection) {
            send(player, session, rejection.reason, true);
        }
    }

    private Deal decide(ServerPlayer player, Session session, TradeWire.Request request, long now) {
        String action = request.action();
        if (request.target() == null || request.target().length() > 128) throw reject("invalid");
        String owner = player.getStringUUID();
        long balance = state.balances.getOrDefault(owner, 0L);
        return switch (session.view) {
            case INFO -> throw reject("invalid");
            case BANK -> bank(player, owner, balance, request);
            case PERMIT -> permit(owner, balance, request);
            case ENCHANT -> enchant(player, session, owner, balance, request);
            case SELL -> {
                if (!action.equals("sell")) throw reject("invalid");
                yield sell(player, session, owner, balance, request, now);
            }
            case BUY -> switch (action) {
                case "buy" -> buy(player, owner, balance, request, now);
                case "order" -> order(player, owner, request, now);
                case "claim", "cancel" -> claim(player, owner, balance, request, now);
                case "delete_order" -> deleteOrder(owner, request.target());
                default -> throw reject("invalid");
            };
        };
    }

    boolean activate(ServerPlayer player) {
        if (unavailable || state == null || closing) {
            player.sendSystemMessage(message("offline"));
            return false;
        }
        if (busy) {
            player.sendSystemMessage(message("busy"));
            return false;
        }
        String owner = player.getStringUUID();
        var permit = permitCredit(owner);
        long now = System.currentTimeMillis();
        if (permit.unusedMillis() == 0) {
            var response = permit.activeUntil() > now
                    ? Component.translatable("towntrade.permit.already_active",
                            TradeRules.permitDuration(permit.activeUntil() - now))
                    : Component.translatable("towntrade.permit.no_credit");
            player.sendSystemMessage(response);
            return false;
        }
        start(player, null, "activate", new Deal(new TradeStore.Change(owner, null, permit.activate(now)), null));
        return true;
    }

    private FlightPermits.Credit permitCredit(String owner) {
        return state.permits.getOrDefault(owner, FlightPermits.Credit.EMPTY);
    }

    private Deal permit(String owner, long balance, TradeWire.Request request) {
        if (!request.action().equals("permit")) throw reject("invalid");
        var pricing = serviceConfig.permit();
        int minutes = request.count();
        if (minutes < pricing.minimumMinutes() || minutes > pricing.maximumMinutes()) throw reject("permit_duration");
        long total = pricing.price(minutes);
        verifyQuote(request, total);
        return new Deal(new TradeStore.Change(owner, debit(balance, total),
                permitCredit(owner).add(minutes * TradeRules.MINUTE)), null);
    }

    private Deal enchant(ServerPlayer player, Session session, String owner, long balance, TradeWire.Request request) {
        if (!request.action().equals("enchant")) throw reject("invalid");
        var inspected = session.enchant;
        if (inspected == null || request.count() != inspected.slot()
                || !inspected.view().token().equals(request.preview())
                || !ItemStack.matches(inspected.stack(), player.getInventory().getItem(inspected.slot()))) {
            throw reject("inventory_changed");
        }
        var current = enchantDesk.inspect(player, inspected.slot());
        if (current == null) throw reject("inventory_changed");
        var upgrade = enchantDesk.upgrade(current, request.choices());
        if (upgrade == null || upgrade.cost() <= 0) throw reject("enchant_invalid");
        verifyQuote(request, upgrade.cost());
        var plan = InventoryPlan.replace(player.getInventory(), inspected.slot(), current.stack(), upgrade.result());
        return new Deal(change(owner, debit(balance, upgrade.cost())), plan);
    }

    private Deal bank(ServerPlayer player, String owner, long balance, TradeWire.Request request) {
        int index = switch (request.target()) {
            case "copper" -> 0;
            case "iron" -> 1;
            case "gold" -> 2;
            default -> throw reject("invalid");
        };
        int count = positive(request.count(), 2304);
        var coin = coins.get(index);
        if (coin.isEmpty()) throw reject("missing_coin");
        long total = (long) config.coins().get(index).value() * count;
        verifyQuote(request, total);
        return switch (request.action()) {
            case "deposit" -> {
                var plan = InventoryPlan.remove(player.getInventory(), coin, count);
                if (plan == null) throw reject("not_enough_items");
                yield new Deal(change(owner, credit(balance, total)), plan);
            }
            case "withdraw" -> {
                long result = debit(balance, total);
                var plan = InventoryPlan.add(player.getInventory(), coin, count);
                if (plan == null) throw reject("backpack_full");
                yield new Deal(change(owner, result), plan);
            }
            default -> throw reject("invalid");
        };
    }

    private Deal buy(ServerPlayer player, String owner, long balance, TradeWire.Request request, long now) {
        var entry = requireProduct(request.target());
        var rule = entry.rule();
        if (!rule.stocked()) throw reject("invalid");
        int count = positive(request.count(), 2304);
        if ((long) count * rule.buyBatch() > 2304) throw reject("backpack_full");
        int quantity = count * rule.buyBatch();
        long total = (long) count * rule.buyPrice();
        verifyQuote(request, total);
        var stock = stock(rule, now);
        if (stock.count() < quantity) throw reject("no_stock");
        long result = debit(balance, total);
        var plan = InventoryPlan.add(player.getInventory(), entry.item(), quantity);
        if (plan == null) throw reject("backpack_full");
        var restock = TradeRules.stockAfter(stock, -quantity, rule.capacity(), rule.interval(), now);
        return new Deal(new TradeStore.Change(owner, result, rule.id(), restock, null, null, null), plan);
    }

    private Deal sell(ServerPlayer player, Session session, String owner, long balance,
                      TradeWire.Request request, long now) {
        var entry = requireProduct(request.target());
        var rule = entry.rule();
        if (!rule.buysFromPlayer() || !rule.pool().equals(session.pool)) throw reject("invalid");
        int count = positive(request.count(), 2304);
        if ((long) rule.sellBatch() * count > 2304) throw reject("not_enough_items");
        int quantity = rule.sellBatch() * count;
        long cost = (long) rule.quota() * count * TradeRules.POINT;
        long available = quota(owner, rule.pool(), now);
        if (cost > available) throw reject("quota_low");
        season = seasons.season(server);
        long total = (long) count * rule.seasonalPrice(season);
        verifyQuote(request, total);
        var plan = InventoryPlan.remove(player.getInventory(), entry.item(), quantity);
        if (plan == null) throw reject("not_enough_items");

        var retail = stockedItems.get(itemKey(rule));
        TradeRules.Stock supplied = null;
        if (retail != null) {
            var retailRule = retail.rule();
            supplied = TradeRules.stockAfter(stock(retailRule, now), quantity,
                    retailRule.capacity(), retailRule.interval(), now);
        }
        return new Deal(new TradeStore.Change(owner, credit(balance, total),
                retail == null ? null : retail.rule().id(), supplied, rule.pool(),
                new TradeRules.Quota(available - cost, now), null), plan);
    }

    private Deal order(ServerPlayer player, String owner, TradeWire.Request request, long now) {
        var rule = requireProduct(request.target()).rule();
        if (!rule.requestOnly()) throw reject("invalid");
        int batches = positive(request.count(), TradeRules.maxOrderBatches(rule.buyBatch()));
        int quantity = batches * rule.buyBatch();
        long price = TradeRules.orderPrice(rule.buyPrice(), batches);
        verifyQuote(request, price);
        int active = 0;
        int personal = 0;
        for (var previous : state.orders.values()) {
            if (!previous.active(now) || orderProduct(previous) == null) continue;
            active++;
            if (!previous.owner().equals(owner)) continue;
            personal++;
            if (previous.product().equals(rule.id())) throw reject("already_ordered");
        }
        if (personal >= config.orderLimit() || active >= SERVER_ORDER_LIMIT) throw reject("order_limit");
        long arrival = now + rule.interval();
        var order = new TradeRules.Order(UUID.randomUUID().toString(), owner, player.getScoreboardName(),
                rule.id(), price, now, arrival, arrival + config.protectionMinutes() * TradeRules.MINUTE,
                TradeRules.orderExpiry(arrival, timezone), "active", quantity);
        return new Deal(new TradeStore.Change(owner, null, null, null, null, null, order), null);
    }

    private Deal claim(ServerPlayer player, String owner, long balance, TradeWire.Request request, long now) {
        var order = state.orders.get(request.target());
        if (order == null || !order.active(now)) throw reject("order_unavailable");
        boolean own = owner.equals(order.owner());
        if (request.action().equals("cancel")) {
            if (!own) throw reject("invalid");
            return new Deal(new TradeStore.Change(owner, null, null, null, null, null, order.closed("cancelled")), null);
        }
        if (!order.arrived(now) || (!own && now < order.protectsUntil())) throw reject("order_unavailable");
        var entry = orderProduct(order);
        if (entry == null) throw reject("order_unavailable");
        long total = own ? order.price() : TradeRules.publicPrice(order.price(), config.publicPremium());
        verifyQuote(request, total);
        long result = debit(balance, total);
        var plan = InventoryPlan.add(player.getInventory(), entry.item(), order.quantityOrBatch(entry.rule().buyBatch()));
        if (plan == null) throw reject("backpack_full");
        return new Deal(new TradeStore.Change(owner, result, null, null, null, null,
                order.closed(own ? "claimed" : "bought_by_other")), plan);
    }

    private ListingData orderProduct(TradeRules.Order order) {
        var entry = products.get(order.product());
        return entry != null && entry.rule().requestOnly() ? entry : null;
    }

    private Deal deleteOrder(String owner, String id) {
        var order = state.orders.get(id);
        if (order == null || !order.owner().equals(owner) || !order.status().equals("cancelled")) {
            throw reject("order_unavailable");
        }
        return new Deal(new TradeStore.Change(owner, null, null, null, null, null, null, null, id), null);
    }

    private void start(ServerPlayer player, Session session, String action, Deal deal) {
        busy = true;
        var pending = new TradeStore.Pending(UUID.randomUUID().toString(), action, deal.change());
        if (deal.inventory() == null) {
            commit(player, session, pending, false);
            return;
        }
        background(() -> {
            store.prepare(pending);
            return null;
        }).whenComplete((ignored, failure) -> server.execute(() -> {
            if (closing) return;
            if (failure != null) {
                fail(failure);
                return;
            }
            if (server.getPlayerList().getPlayer(player.getUUID()) != player || !player.isAlive()
                    || !deal.inventory().stillMatches()) {
                discard(player, session, pending);
                return;
            }
            try {
                deal.inventory().apply();
                ((PlayerTransactionTag) player).towntrade$setTransaction(pending.id());
                ((PlayerListAccess) server.getPlayerList()).towntrade$save(player);
                player.inventoryMenu.broadcastChanges();
                if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastChanges();
                commit(player, session, pending, true);
            } catch (RuntimeException failureOnServer) {
                fail(failureOnServer);
            }
        }));
    }

    private void discard(ServerPlayer player, Session session, TradeStore.Pending pending) {
        background(() -> {
            store.discard(pending.id());
            return null;
        }).whenComplete((ignored, failure) -> server.execute(() -> {
            busy = false;
            if (closing) return;
            if (failure != null) fail(failure);
            else if (sessions.get(player.getUUID()) == session) send(player, session, "inventory_changed", true);
        }));
    }

    private void commit(ServerPlayer player, Session session, TradeStore.Pending pending, boolean verifyPlayer) {
        background(() -> {
            if (verifyPlayer && !isSaved(pending.change().player(), pending.id())) {
                throw new IllegalStateException("Player inventory save could not be verified for " + pending.id());
            }
            store.commit(pending, System.currentTimeMillis());
            return null;
        }).whenComplete((ignored, failure) -> server.execute(() -> {
            busy = false;
            if (closing) return;
            if (failure != null) {
                fail(failure);
                return;
            }
            state.apply(pending.change());
            if (pending.change().permits() != null) {
                var online = server.getPlayerList().getPlayer(player.getUUID());
                if (online != null) {
                    long now = System.currentTimeMillis();
                    syncFlight(online, now);
                    if (pending.action().equals("permit")) {
                        online.sendSystemMessage(Component.translatable("towntrade.permit.activate_hint",
                                TownTrade.commandLink("/towntrade active")));
                    } else if (pending.action().equals("activate")) {
                        online.sendSystemMessage(Component.translatable("towntrade.permit.activated",
                                TradeRules.permitDuration(pending.change().permits().activeUntil() - now)));
                    }
                }
            }
            // The buyer identity is present only in the receipt, not in an order sent to clients.
            refreshSessions(player, session, pending.change().stock() != null || pending.change().order() != null);
        }));
    }

    private boolean isSaved(String player, String transaction) throws Exception {
        String uuid = UUID.fromString(player).toString();
        try {
            return hasMarker(playerData.resolve(uuid + ".dat"), transaction);
        } catch (NoSuchFileException noPrimary) {
            // Prefer the same backup that Minecraft loads after an interrupted file replacement.
            try {
                return hasMarker(playerData.resolve(uuid + ".dat_old"), transaction);
            } catch (NoSuchFileException noBackup) {
                return false;
            }
        }
    }

    private static boolean hasMarker(Path path, String transaction) throws Exception {
        var tag = NbtIo.readCompressed(path, NbtAccounter.create(32 * 1024 * 1024));
        return tag.getStringOr(PlayerTransactionTag.KEY, "").equals(transaction);
    }

    private <T> CompletableFuture<T> background(DatabaseWork<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.run();
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        }, writer);
    }

    private void fail(Throwable failure) {
        unavailable = true;
        TownTrade.LOGGER.error("Town Trade suspended; check the economy database and player save before resuming", failure);
        for (UUID id : sessions.keySet()) {
            var player = server.getPlayerList().getPlayer(id);
            if (player != null) player.sendSystemMessage(message("offline"));
        }
        sessions.clear();
    }

    void joined(ServerPlayer player) {
        if (closing) return;
        syncFlight(player, System.currentTimeMillis());
        if (!unavailable && state != null) tick(true);
    }

    private void syncFlight(ServerPlayer player, long now) {
        if (state == null) return;
        long end = permitCredit(player.getStringUUID()).activeUntil();
        if (end > now || ((PlayerTransactionTag) player).towntrade$permitFlight()
                || ((PlayerTransactionTag) player).towntrade$landing() > 0) {
            flight.sync(player, end, now);
        }
    }

    boolean protectFall(ServerPlayer player) {
        return flight.protectFall(player, System.currentTimeMillis());
    }

    void disconnected(ServerPlayer player) {
        sessions.remove(player.getUUID());
        notified.remove(player.getStringUUID());
        flight.disconnected(player);
    }

    void tick(boolean force) {
        if (state == null || closing || server.getPlayerList().getPlayerCount() == 0) return;
        long now = System.currentTimeMillis();
        if (!force && now < nextSweep) return;
        nextSweep = now + 1000;
        for (var player : server.getPlayerList().getPlayers()) syncFlight(player, now);
        if (unavailable) return;
        String currentSeason = seasons.season(server);
        boolean seasonChanged = !currentSeason.equals(season);
        season = currentSeason;
        var online = new HashMap<String, ServerPlayer>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) online.put(player.getStringUUID(), player);
        for (var order : state.orders.values()) {
            if (!order.active(now) || !order.arrived(now)) continue;
            var player = online.get(order.owner());
            var offer = orderProduct(order);
            if (player == null || offer == null) continue;
            if (notified.computeIfAbsent(order.owner(), key -> new HashSet<>()).add(order.id())) {
                player.sendSystemMessage(Component.translatable("towntrade.notice.arrived",
                        offer.item().getHoverName(), order.quantityOrBatch(offer.rule().buyBatch())));
            }
        }
        var iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            var item = iterator.next();
            var player = server.getPlayerList().getPlayer(item.getKey());
            var session = item.getValue();
            if (player == null || now >= session.expires) {
                iterator.remove();
            } else if (session.refreshAt <= now || (seasonChanged && session.view == TradeWire.View.SELL)) {
                send(player, session, null, false);
            }
        }
        if (!busy && now >= nextPrune) {
            nextPrune = now + 60 * TradeRules.MINUTE;
            state.orders.values().removeIf(order -> order.expires() < now - RESULT_HISTORY);
            var retained = state.orders.keySet();
            notified.values().forEach(ids -> ids.retainAll(retained));
            background(() -> {
                store.prune(now);
                return null;
            }).exceptionally(failure -> {
                TownTrade.LOGGER.warn("Could not prune town trade history", failure);
                return null;
            });
        }
    }

    private void refreshSessions(ServerPlayer actor, Session original, boolean refreshBuy) {
        for (var entry : sessions.entrySet()) {
            var player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            boolean ownResponse = actor == player && entry.getValue() == original;
            if (actor.getUUID().equals(player.getUUID()) || (refreshBuy && entry.getValue().view == TradeWire.View.BUY)) {
                send(player, entry.getValue(), ownResponse ? "completed" : null, ownResponse);
            }
        }
    }

    void close() {
        closing = true;
        writer.execute(() -> {
            if (store != null) {
                try {
                    store.close();
                } catch (Exception failure) {
                    TownTrade.LOGGER.warn("Could not close the town trade database", failure);
                }
            }
        });
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) TownTrade.LOGGER.warn("Town Trade writer is still closing");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    private List<TradeWire.Offer> offers(Session session) {
        var result = new ArrayList<TradeWire.Offer>();
        for (var entry : products.values()) {
            var rule = entry.rule();
            if (session.view == TradeWire.View.BUY && rule.buyPrice() > 0) {
                result.add(new TradeWire.Offer(rule.id(), rule.item(), rule.potion(), rule.category(),
                        rule.buyBatch(), rule.buyPrice(), 0, rule.requestOnly(), rule.interval()));
            } else if (session.view == TradeWire.View.SELL && rule.buysFromPlayer() && rule.pool().equals(session.pool)) {
                result.add(new TradeWire.Offer(rule.id(), rule.item(), rule.potion(), rule.category(),
                        rule.sellBatch(), rule.sellPrice(), rule.quota(), false, 0));
            }
        }
        return result;
    }

    private TradeWire.Board board(ServerPlayer player, Session session) {
        long now = System.currentTimeMillis();
        long refreshAt = TradeRules.NO_TIME;
        String owner = player.getStringUUID();
        long balance = state.balances.getOrDefault(owner, 0L);
        var listings = new HashMap<String, TradeWire.Listing>();
        var orders = new ArrayList<TradeWire.OrderView>();
        int activeOrders = 0;
        TradeWire.QuotaInfo quotaInfo = null;
        List<Integer> coinCounts = List.of();
        TradeWire.PermitView permit = null;
        List<TradeWire.GearSlot> gear = List.of();
        TradeWire.EnchantView enchant = null;
        if (session.view == TradeWire.View.PERMIT || session.view == TradeWire.View.INFO) {
            var credit = permitCredit(owner);
            permit = new TradeWire.PermitView(serviceConfig.permit(), credit.unusedMillis(), credit.activeUntil());
            if (credit.activeUntil() > now) refreshAt = credit.activeUntil();
        } else if (session.view == TradeWire.View.ENCHANT) {
            gear = EnchantDesk.inventory(player);
            var inspected = session.enchant;
            if (inspected != null) {
                if (!ItemStack.matches(inspected.stack(), player.getInventory().getItem(inspected.slot()))) {
                    session.enchant = enchantDesk.inspect(player, inspected.slot());
                }
                if (session.enchant != null) enchant = session.enchant.view();
            }
        } else if (session.view == TradeWire.View.BANK) {
            coinCounts = coins.stream().map(coin -> InventoryPlan.count(player.getInventory(), coin)).toList();
        } else if (session.view == TradeWire.View.SELL) {
            var rule = config.pools().get(session.pool);
            quotaInfo = new TradeWire.QuotaInfo(quota(owner, session.pool, now), rule.ceiling(), rule.pointsPerMinute());
            for (var entry : products.values()) {
                var product = entry.rule();
                if (!product.buysFromPlayer() || !product.pool().equals(session.pool)) continue;
                int count = InventoryPlan.count(player.getInventory(), entry.item());
                listings.put(product.id(), new TradeWire.Listing(count, TradeRules.NO_TIME, product.seasonalPrice(season)));
            }
        } else if (session.view == TradeWire.View.BUY) {
            for (var entry : products.values()) {
                var product = entry.rule();
                if (!product.stocked()) continue;
                var stock = stock(product, now);
                if (stock.count() >= product.capacity()) continue;
                refreshAt = Math.min(refreshAt, stock.next());
                listings.put(product.id(), new TradeWire.Listing(stock.count(), stock.next(), product.buyPrice()));
            }
            // Full-stock entries are cheap to share and let the client quote exact maximum quantities.
            for (var entry : products.values()) {
                var product = entry.rule();
                if (product.stocked()) listings.putIfAbsent(product.id(),
                        new TradeWire.Listing(product.capacity(), TradeRules.NO_TIME, product.buyPrice()));
            }
            for (var order : state.orders.values()) {
                var entry = orderProduct(order);
                if (entry == null) continue;
                boolean own = owner.equals(order.owner());
                boolean active = order.active(now);
                if (!active && !own) continue;
                if (!active && order.expires() < now - RESULT_HISTORY) continue;
                if (active && own) activeOrders++;
                if (active) {
                    if (now < order.arrives()) refreshAt = Math.min(refreshAt, order.arrives());
                    else if (now < order.protectsUntil()) refreshAt = Math.min(refreshAt, order.protectsUntil());
                    refreshAt = Math.min(refreshAt, order.expires());
                }
                long price = own ? order.price() : TradeRules.publicPrice(order.price(), config.publicPremium());
                orders.add(new TradeWire.OrderView(order.id(), order.product(), order.ownerName(), own, price,
                        order.placed(), order.arrives(), order.protectsUntil(), order.expires(),
                        order.displayStatus(now), order.quantityOrBatch(entry.rule().buyBatch())));
            }
            orders.sort(Comparator.comparingLong(TradeWire.OrderView::placed).thenComparing(TradeWire.OrderView::id));
        }
        session.refreshAt = refreshAt;
        return new TradeWire.Board(now, balance, season, config.orderLimit(), activeOrders,
                coinCounts, quotaInfo, listings, orders, permit, gear, enchant);
    }

    private void send(ServerPlayer player, Session session, String result, boolean response) {
        if (!ServerPlayNetworking.canSend(player, TradeWire.UpdatePacket.TYPE)) return;
        var packet = new TradeWire.Update(session.token, board(player, session), result, response);
        ServerPlayNetworking.send(player, new TradeWire.UpdatePacket(TradeConfig.JSON.toJson(packet)));
    }

    private TradeRules.Stock stock(TradeConfig.Product product, long now) {
        return TradeRules.replenish(state.stocks.get(product.id()), product.capacity(), product.restock(), product.interval(), now);
    }

    private long quota(String owner, String pool, long now) {
        return TradeRules.quotaNow(state.quotas.get(TradeStore.State.quotaKey(owner, pool)), config.pools().get(pool), now);
    }

    private ListingData requireProduct(String id) {
        var result = products.get(id);
        if (result == null) throw reject("invalid");
        return result;
    }

    private static String itemKey(TradeConfig.Product product) {
        return product.item() + "|" + product.potion();
    }

    private static int positive(int count, int maximum) {
        if (count < 1 || count > maximum) throw reject("invalid");
        return count;
    }

    private static long credit(long balance, long amount) {
        if (amount > TradeRules.MAX_BALANCE - balance) throw reject("balance_cap");
        return balance + amount;
    }

    private static long debit(long balance, long amount) {
        if (balance < amount) throw reject("balance_low");
        return balance - amount;
    }

    private static void verifyQuote(TradeWire.Request request, long amount) {
        if (request.quote() != amount) throw reject("price_changed");
    }

    private static TradeStore.Change change(String owner, long balance) {
        return new TradeStore.Change(owner, balance, null, null, null, null, null);
    }

    private static Rejected reject(String reason) {
        return new Rejected(reason);
    }

    static Component message(String key) {
        return Component.translatable("towntrade.result." + key);
    }
}
