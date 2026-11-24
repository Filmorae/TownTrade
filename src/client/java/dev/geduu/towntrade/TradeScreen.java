package dev.geduu.towntrade;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;

final class TradeScreen extends Screen {
    private static final int WHITE = 0xffeef3f3;
    private static final int MUTED = 0xffa8b5bc;
    private static final int GREEN = 0xff9de38e;
    private static final int RED = 0xffff9c8a;
    private static final int AMBER = 0xffffd583;
    private static final int LINE = 0xff35424a;
    private static final int PANEL = 0xe913181d;
    private static final int CARD = 0xf4212930;
    private static final int GRID_CELL = 40;
    private static final int ORDER_ROW = 21;
    private static final int ORDER_DELETE_WIDTH = 16;

    private enum Tab { CATALOG, ORDERS, MINE }
    private record Group(String title, List<OfferSearch.Entry> entries) {}
    private record Command(String action, String target, int count, long quote, String invalid) {}

    private final TradeWire.ScreenOpen open;
    private final CurrencyDisplay currencies;
    private final NumberScroll numberScroll = new NumberScroll();
    private final List<OfferSearch.Entry> entries;
    private final Map<String, OfferSearch.Entry> byId = new LinkedHashMap<>();
    private final List<String> categories;
    private final List<TradeButton> categoryButtons = new ArrayList<>();
    private List<Group> groups = List.of();
    private List<TradeWire.OrderView> visibleOrders = List.of();
    private TradeWire.Board board;
    private long receivedTime;
    private long requestTime;
    private boolean pending;
    private int sequence;
    private String result = "";
    private String category = "all";
    private String query = "";
    private String selected = "";
    private String selectedOrder = "";
    private String quantity = "1";
    private int variant;
    private int offset;
    private int categoryOffset;
    private boolean withdraw = true;
    private int denomination;
    private Tab tab = Tab.CATALOG;
    private EditBox searchBox;
    private EditBox quantityBox;
    private Button actionButton;
    private Button plus;
    private Button minus;
    private Button cancelButton;
    private Button variantNext;
    private Button variantPrevious;
    private int x;
    private int y;
    private int right;
    private int bottom;
    private int contentTop;
    private int listLeft;
    private int listRight;
    private int detailsLeft;

    TradeScreen(TradeWire.ScreenOpen open) {
        super(key("screen." + open.view().name().toLowerCase(Locale.ROOT)));
        this.open = open;
        currencies = new CurrencyDisplay(open.currencies());
        entries = new OfferSearch().index(open.offers());
        entries.forEach(entry -> byId.put(entry.offer().id(), entry));
        var names = new ArrayList<String>();
        names.add("all");
        if (open.view() == TradeWire.View.BUY) {
            for (String value : List.of("building", "nature", "farming", "minerals", "ingredients", "potions",
                    "bucket_mobs", "collectibles", "rare")) {
                if (entries.stream().anyMatch(entry -> entry.offer().category().equals(value))) names.add(value);
            }
        }
        categories = List.copyOf(names);
        setBoard(open.board());
    }

    @Override
    protected void init() {
        x = (width - Math.min(width - 12, 740)) / 2;
        right = width - x;
        y = (height - Math.min(height - 12, 438)) / 2;
        bottom = height - y;
        int innerWidth = right - x;
        contentTop = y + (open.view() == TradeWire.View.BANK ? 57 : 82);
        detailsLeft = right - Math.max(115, Math.min(190, innerWidth * 30 / 100));
        listLeft = x + (open.view() == TradeWire.View.BUY ? (innerWidth >= 470 ? 94 : 73) : 11);
        listRight = detailsLeft - 8;
        addButton("close", right - 26, y + 8, 18, 18, this::onClose);
        if (open.view() == TradeWire.View.BANK) {
            buildBank();
        } else {
            buildCatalog();
        }
        buildControls();
        refilter();
        updateControls();
    }

    private void buildBank() {
        int buttonWidth = (detailsLeft - x - 26) / 2;
        addButton("deposit", x + 10, y + 32, buttonWidth, 18, () -> selectBankMode(false)).selected = () -> !withdraw;
        addButton("withdraw", x + 14 + buttonWidth, y + 32, buttonWidth, 18, () -> selectBankMode(true)).selected = () -> withdraw;
        for (int index = 0; index < 3; index++) {
            int coin = index;
            var button = addButton("coin." + denominationKey(index), listLeft, contentTop + index * 25,
                    Math.max(64, detailsLeft - listLeft - 12), 20, () -> {
                        denomination = coin;
                        setQuantity(1);
                    });
            button.selected = () -> denomination == coin;
            button.icon = currencies.icon(coin);
        }
    }

    private void buildCatalog() {
        int w = right - x;
        if (open.view() == TradeWire.View.BUY) {
            int tabWidth = Math.min((w - 38) / 3, 114);
            addButton("catalog", x + 8, y + 30, tabWidth, 18, () -> setTab(Tab.CATALOG)).selected = () -> tab == Tab.CATALOG;
            addButton("orders", x + 12 + tabWidth, y + 30, tabWidth, 18, () -> setTab(Tab.ORDERS)).selected = () -> tab == Tab.ORDERS;
            addButton("mine", x + 16 + 2 * tabWidth, y + 30, tabWidth, 18, () -> setTab(Tab.MINE)).selected = () -> tab == Tab.MINE;
            categoryButtons.clear();
            for (String value : categories) {
                var button = addButton("category." + value, x + 8, contentTop,
                        listLeft - x - 14, 16, () -> {
                            category = value;
                            offset = 0;
                            refilter();
                            updateControls();
                        });
                button.selected = () -> category.equals(value);
                categoryButtons.add(button);
            }
            updateCategoryButtons();
        }
        searchBox = new EditBox(font, x + 10, y + 55, right - x - 20, 18, key("search"));
        searchBox.setMaxLength(100);
        searchBox.setHint(key("search"));
        searchBox.setValue(query);
        searchBox.setResponder(value -> {
            query = value;
            selected = "";
            selectedOrder = "";
            offset = 0;
            refilter();
        });
        addRenderableWidget(searchBox);
        if (open.view() == TradeWire.View.SELL) setInitialFocus(searchBox);
    }

    private int visibleCategoryRows() {
        return Math.max(1, (bottom - contentTop - 8) / 18);
    }

    private int maxCategoryOffset() {
        return Math.max(0, categoryButtons.size() - visibleCategoryRows());
    }

    private void updateCategoryButtons() {
        categoryOffset = Math.clamp(categoryOffset, 0, maxCategoryOffset());
        int rows = visibleCategoryRows();
        for (int index = 0; index < categoryButtons.size(); index++) {
            var button = categoryButtons.get(index);
            int row = index - categoryOffset;
            button.visible = tab == Tab.CATALOG && row >= 0 && row < rows;
            button.active = button.visible;
            button.setY(contentTop + row * 18);
            if (!button.visible && getFocused() == button) setFocused(null);
        }
    }

    private void buildControls() {
        int controlY = bottom - 44;
        int left = detailsLeft + 10;
        int available = right - left - 10;
        minus = addButton("minus", left, controlY, 19, 18, () -> setQuantity(Math.max(1, amount() - 1)));
        plus = addButton("plus", left + available - 19, controlY, 19, 18, () -> setQuantity(Math.max(1, amount() + 1)));
        quantityBox = new EditBox(font, left + 22, controlY, available - 44, 18, key("quantity"));
        quantityBox.setMaxLength(4);
        quantityBox.setValue(quantity);
        quantityBox.setResponder(value -> {
            String digits = value.replaceAll("[^0-9]", "");
            if (!digits.equals(value)) {
                quantityBox.setValue(digits);
                return;
            }
            quantity = digits;
            updateControls();
        });
        addRenderableWidget(quantityBox);
        var confirm = addButton("confirm", left, bottom - 24, available, 20, this::submit);
        confirm.primary = true;
        actionButton = confirm;
        cancelButton = addButton("cancel_order", left, bottom - 44, available, 18, () -> {
            var order = order();
            if (!pending && order != null && order.own()) send("cancel", order.id(), 1, 0);
        });
        variantPrevious = addButton("previous", left, contentTop + 49, 20, 17, () -> moveVariant(-1));
        variantNext = addButton("next", left + available - 20, contentTop + 49, 20, 17, () -> moveVariant(1));
    }

    private TradeButton addButton(String label, int x, int y, int width, int height, Runnable callback) {
        return addRenderableWidget(new TradeButton(font, key(label), x, y, width, height, callback));
    }

    private void selectBankMode(boolean value) {
        withdraw = value;
        setQuantity(1);
    }

    private void setTab(Tab value) {
        if (open.view() != TradeWire.View.BUY) return;
        tab = value;
        category = value == Tab.CATALOG ? category : "all";
        offset = 0;
        updateCategoryButtons();
        refilter();
        updateControls();
    }

    private void setQuantity(int amount) {
        quantityBox.setValue(Integer.toString(Math.min(amount, 9999)));
        updateControls();
    }

    private int amount() {
        return quantity.isBlank() ? 0 : Integer.parseInt(quantity);
    }

    private void moveVariant(int delta) {
        var group = group();
        if (group == null) return;
        variant = Math.max(0, Math.min(group.entries().size() - 1, variant + delta));
        setQuantity(1);
    }

    private void setBoard(TradeWire.Board current) {
        board = current;
        receivedTime = System.nanoTime();
    }

    void update(TradeWire.Update update) {
        if (!open.token().equals(update.token())) return;
        setBoard(update.board());
        if (update.response()) {
            pending = false;
            result = update.result();
        }
        refilter();
        updateControls();
    }

    private void refilter() {
        if (open.view() == TradeWire.View.BANK) return;
        String[] tokens = OfferSearch.normalize(query).strip().split("\\s+");
        if (tab == Tab.CATALOG) {
            var grouped = new LinkedHashMap<String, List<OfferSearch.Entry>>();
            for (var entry : entries) {
                var offer = entry.offer();
                if (!category.equals("all") && !category.equals(offer.category())) continue;
                if (!entry.matches(tokens)) continue;
                String id = offer.potion() == null ? offer.id() : potionGroup(offer.potion());
                grouped.computeIfAbsent(id, value -> new ArrayList<>()).add(entry);
            }
            var filtered = new ArrayList<Group>();
            grouped.forEach((name, matches) -> filtered.add(new Group(name, List.copyOf(matches))));
            groups = List.copyOf(filtered);
            if (group() == null && !groups.isEmpty()) {
                var first = groups.getFirst();
                selected = first.title();
                variant = 0;
                if (first.entries().getFirst().offer().orderOnly()) setQuantity(1);
            }
        } else {
            visibleOrders = board.orders().stream().filter(value -> {
                if (tab == Tab.MINE && !value.own()) return false;
                if (tab == Tab.ORDERS && !active(value)) return false;
                var entry = byId.get(value.product());
                return entry != null && entry.matches(tokens);
            }).toList();
            if (order() == null && !visibleOrders.isEmpty()) selectedOrder = visibleOrders.getFirst().id();
        }
        offset = Math.max(0, Math.min(offset, maxOffset()));
    }

    private static String potionGroup(String id) {
        return id.replace(":long_", ":").replace(":strong_", ":");
    }

    private Group group() {
        for (Group group : groups) if (group.title().equals(selected)) return group;
        return null;
    }

    private TradeWire.OrderView order() {
        for (var item : visibleOrders) if (item.id().equals(selectedOrder)) return item;
        return null;
    }

    private OfferSearch.Entry selectedOffer() {
        if (tab != Tab.CATALOG) {
            var order = order();
            return order == null ? null : byId.get(order.product());
        }
        var group = group();
        return group == null ? null : group.entries().get(Math.min(variant, group.entries().size() - 1));
    }

    private static boolean active(TradeWire.OrderView order) {
        return switch (order.status()) {
            case "waiting", "protected", "available" -> true;
            default -> false;
        };
    }

    private Command command(int count) {
        if (open.view() == TradeWire.View.BANK) {
            long cost = (long) count * denominationValue();
            String issue = "";
            if (count < 1) issue = "invalid";
            else if (withdraw && cost > board.balance()) issue = "balance_low";
            else if (!withdraw && count > board.coins().get(denomination)) issue = "not_enough_items";
            else if (count > 2304) issue = "backpack_full";
            return new Command(withdraw ? "withdraw" : "deposit", denominationKey(denomination), count, cost, issue);
        }
        var entry = selectedOffer();
        if (entry == null) return new Command("", "", 0, 0, "no_selection");
        var offer = entry.offer();
        if (tab != Tab.CATALOG) {
            var order = order();
            String issue = switch (order.status()) {
                case "available" -> "";
                case "protected" -> order.own() ? "" : "order_unavailable";
                default -> "order_unavailable";
            };
            if (issue.isEmpty() && order.price() > board.balance()) issue = "balance_low";
            return new Command("claim", order.id(), 1, order.price(), issue);
        }
        if (offer.orderOnly()) {
            int maximum = TradeRules.maxOrderBatches(offer.batch());
            if (count < 1 || count > maximum) {
                return new Command("order", offer.id(), count, 0, "invalid");
            }
            boolean duplicate = board.orders().stream().anyMatch(order ->
                    order.own() && order.product().equals(offer.id()) && active(order));
            String issue = duplicate ? "already_ordered" : board.activeOrders() >= board.orderLimit() ? "order_limit" : "";
            return new Command("order", offer.id(), count, TradeRules.orderPrice(offer.basePrice(), count), issue);
        }
        var listing = board.listings().get(offer.id());
        long price = listing == null ? offer.basePrice() : listing.price();
        long total = price * count;
        long units = (long) count * offer.batch();
        boolean sell = open.view() == TradeWire.View.SELL;
        String issue = "";
        if (count < 1 || listing == null) issue = "invalid";
        else if (units > listing.count()) issue = sell ? "not_enough_items" : "no_stock";
        else if (!sell && total > board.balance()) issue = "balance_low";
        else if (sell && (long) offer.quota() * count * TradeRules.POINT > quotaNow()) issue = "quota_low";
        else if (units > 2304) issue = "backpack_full";
        return new Command(sell ? "sell" : "buy", offer.id(), count, total, issue);
    }

    private int denominationValue() {
        return 1 << (4 * denomination);
    }

    private static String denominationKey(int index) {
        return List.of("copper", "iron", "gold").get(index);
    }

    private long now() {
        return board.serverTime() + Math.max(0, (System.nanoTime() - receivedTime) / 1_000_000);
    }

    private long quotaNow() {
        var quota = board.quota();
        if (quota == null) return 0;
        return Math.min(quota.ceiling(), quota.units() + (now() - board.serverTime()) * quota.rate());
    }

    private static String points(long units) {
        return String.format(Locale.ROOT, "%.1f", units / (double) TradeRules.POINT);
    }

    private void updateControls() {
        if (actionButton == null) return;
        var command = command(amount());
        actionButton.setMessage(key(pending ? "working" : command.action().isEmpty() ? "confirm" : command.action()));
        actionButton.active = !pending && command.invalid().isEmpty();
        boolean quantityVisible = open.view() == TradeWire.View.BANK
                || (tab == Tab.CATALOG && selectedOffer() != null);
        quantityBox.visible = plus.visible = minus.visible = quantityVisible;
        minus.active = !pending && amount() > 1;
        plus.active = !pending && command(amount() + 1).invalid().isEmpty();
        var group = group();
        boolean variants = open.view() != TradeWire.View.BANK && tab == Tab.CATALOG && group != null && group.entries().size() > 1;
        variantPrevious.visible = variantNext.visible = variants;
        variantPrevious.active = variant > 0;
        variantNext.active = variants && variant < group.entries().size() - 1;
        var order = tab == Tab.CATALOG ? null : order();
        cancelButton.visible = order != null && order.own() && active(order);
        cancelButton.active = !pending;
    }

    @Override
    public void tick() {
        if (pending && System.nanoTime() - requestTime > 12_000_000_000L) {
            pending = false;
            result = "timed_out";
        }
        updateControls();
    }

    private void submit() {
        var command = command(amount());
        if (!pending && command.invalid().isEmpty()) send(command.action(), command.target(), command.count(), command.quote());
    }

    private void send(String action, String target, int count, long quote) {
        pending = true;
        result = "";
        requestTime = System.nanoTime();
        TradeClient.send(new TradeWire.Request(open.token(), ++sequence, action, target, count, quote));
        updateControls();
    }

    @Override
    public void removed() {
        TradeClient.send(new TradeWire.Request(open.token(), ++sequence, "close", "", 0, 0));
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(x, y, right, bottom, PANEL);
        graphics.outline(x, y, right - x, bottom - y, LINE);
        graphics.fill(detailsLeft, contentTop - 3, right - 5, bottom - 5, CARD);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.text(font, getTitle(), x + 10, y + 11, WHITE);
        int balanceWidth = currencies.width(font, 0, board.balance(), 14, true);
        int balanceX = right - 35 - balanceWidth;
        String label = key("money.balance").getString();
        int labelX = balanceX - font.width(label) - 6;
        if (labelX > x + 20 + font.width(getTitle())) graphics.text(font, label, labelX, y + 13, MUTED);
        currencies.amount(graphics, font, 0, board.balance(), balanceX, y + 13, 14, AMBER, true);
        if (open.view() == TradeWire.View.BANK) drawBank(graphics);
        else {
            if (tab == Tab.CATALOG) {
                drawGrid(graphics, mouseX, mouseY);
                drawCategoryScroll(graphics);
            } else {
                drawOrders(graphics, mouseX, mouseY);
            }
            drawDetails(graphics, mouseX, mouseY);
            drawIndicator(graphics);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (overQuantity(mouseX, mouseY)) NumberScroll.hint(graphics, mouseX, mouseY);
    }

    private void drawCategoryScroll(GuiGraphicsExtractor graphics) {
        if (maxCategoryOffset() == 0) return;
        int left = listLeft - 4;
        int height = visibleCategoryRows() * 18 - 2;
        int thumb = Math.max(6, height * visibleCategoryRows() / categoryButtons.size());
        int top = contentTop + (height - thumb) * categoryOffset / maxCategoryOffset();
        graphics.fill(left, contentTop, left + 2, contentTop + height, LINE);
        graphics.fill(left, top, left + 2, top + thumb, MUTED);
    }

    private void drawBank(GuiGraphicsExtractor graphics) {
        int left = detailsLeft + 10;
        int lineY = contentTop + 7;
        var command = command(amount());
        graphics.text(font, key("coin." + denominationKey(denomination)), left, lineY, WHITE);
        currencies.price(graphics, font, key("money.rate"), denominationValue(),
                left, right - 10, lineY + 14, MUTED, AMBER);
        graphics.text(font, key("in_inventory", board.coins().get(denomination)), left, lineY + 28, MUTED);
        detailText(graphics, key(withdraw ? "withdraw_preview" : "deposit_preview"), left, lineY + 48, WHITE);
        long balance = board.balance() + (withdraw ? -command.quote() : command.quote());
        currencies.amount(graphics, font, 0, balance, left, lineY + 64, 16,
                command.invalid().isEmpty() ? GREEN : RED, true);
        drawResult(graphics, command, left, bottom - 55);
    }

    private void drawGrid(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int columns = gridColumns();
        int rows = rows(GRID_CELL);
        if (open.view() == TradeWire.View.SELL && board.quota() != null) {
            String season = board.season().isEmpty() ? "" : "  ·  " + key("season." + board.season()).getString();
            graphics.text(font, key("quota_heading", points(quotaNow()), points(board.quota().ceiling()),
                    board.quota().rate()).getString() + season, x + 12, y + 36, GREEN);
        }
        if (groups.isEmpty()) graphics.text(font, key("empty"), listLeft, contentTop + 6, MUTED);
        for (int slot = 0; slot < columns * rows; slot++) {
            int index = offset * columns + slot;
            if (index >= groups.size()) break;
            var item = groups.get(index);
            var entry = item.entries().getFirst();
            int left = listLeft + slot % columns * GRID_CELL;
            int top = contentTop + slot / columns * GRID_CELL;
            boolean hovered = contains(mouseX, mouseY, left, top, GRID_CELL - 3, GRID_CELL - 3);
            int border = item.title().equals(selected) ? GREEN : hovered ? AMBER : LINE;
            graphics.fill(left, top, left + GRID_CELL - 3, top + GRID_CELL - 3, CARD);
            graphics.outline(left, top, GRID_CELL - 3, GRID_CELL - 3, border);
            graphics.fakeItem(entry.stack(), left + 10, top + 3);
            var listing = board.listings().get(entry.offer().id());
            if (entry.offer().orderOnly()) {
                graphics.centeredText(font, key("order_badge"), left + 18, top + 23, AMBER);
            } else {
                long price = listing == null ? entry.offer().basePrice() : listing.price();
                boolean missing = listing != null && listing.count() < entry.offer().batch();
                int width = currencies.width(font, 0, price, 8, false);
                int color = missing ? MUTED : open.view() == TradeWire.View.SELL ? GREEN : AMBER;
                if (width > GRID_CELL - 4) graphics.centeredText(font, Long.toString(price), left + 18, top + 23, color);
                else currencies.amount(graphics, font, 0, price, left + 18 - width / 2, top + 23, 8, color, false);
            }
            if (hovered) graphics.setTooltipForNextFrame(font, entry.stack(), mouseX, mouseY);
        }
    }

    private void drawOrders(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (visibleOrders.isEmpty()) graphics.text(font, key("empty"), listLeft, contentTop + 6, MUTED);
        for (int slot = 0; slot < rows(ORDER_ROW); slot++) {
            int index = offset + slot;
            if (index >= visibleOrders.size()) break;
            var order = visibleOrders.get(index);
            var item = byId.get(order.product());
            int top = contentTop + slot * ORDER_ROW;
            boolean hovered = contains(mouseX, mouseY, listLeft, top, listRight - listLeft, ORDER_ROW - 2);
            graphics.fill(listLeft, top, listRight, top + ORDER_ROW - 2, CARD);
            if (hovered || order.id().equals(selectedOrder)) {
                graphics.outline(listLeft, top, listRight - listLeft, ORDER_ROW - 2, hovered ? AMBER : GREEN);
            }
            graphics.fakeItem(item.stack(), listLeft + 3, top + 1);
            String label = order.quantity() + " × " + item.name() + " · " + order.owner();
            boolean canDelete = canDelete(order);
            int reserve = canDelete ? ORDER_DELETE_WIDTH + 3 : 0;
            graphics.text(font, font.plainSubstrByWidth(label, Math.max(12, listRight - listLeft - 26 - reserve)),
                    listLeft + 23, top + 6, WHITE);
            if (canDelete) {
                boolean hoveredDelete = overOrderDelete(mouseX, mouseY, top);
                int at = listRight - ORDER_DELETE_WIDTH - 3;
                graphics.fill(at, top + 2, listRight - 3, top + ORDER_ROW - 4,
                        hoveredDelete && !pending ? 0xff533734 : 0xff2c333a);
                graphics.centeredText(font, key("close"), at + ORDER_DELETE_WIDTH / 2, top + 6,
                        !pending && hoveredDelete ? RED : MUTED);
                if (hoveredDelete) graphics.setTooltipForNextFrame(key("delete_order"), mouseX, mouseY);
            }
        }
    }

    private boolean canDelete(TradeWire.OrderView order) {
        return tab == Tab.MINE && order.own() && order.status().equals("cancelled");
    }

    private boolean overOrderDelete(double x, double y, int top) {
        return contains(x, y, listRight - ORDER_DELETE_WIDTH - 3, top + 2, ORDER_DELETE_WIDTH, ORDER_ROW - 6);
    }

    private void drawDetails(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        var entry = selectedOffer();
        if (entry == null) return;
        var offer = entry.offer();
        int left = detailsLeft + 10;
        int top = contentTop + 5;
        graphics.fakeItem(entry.stack(), left, top);
        graphics.text(font, font.plainSubstrByWidth(entry.name(), Math.max(12, right - left - 30)),
                left + 21, top + 4, WHITE);
        if (contains(mouseX, mouseY, left, top, right - left - 9, 18)) {
            graphics.setTooltipForNextFrame(font, entry.stack(), mouseX, mouseY);
        }
        var command = command(amount());
        var listing = board.listings().get(offer.id());
        if (tab != Tab.CATALOG) {
            drawOrderDetails(graphics, order(), left, top + 22);
        } else if (offer.orderOnly()) {
            graphics.text(font, key("arrival_estimate", duration(offer.waitMillis())), left, top + 23, MUTED);
            graphics.text(font, key("order_slots", board.activeOrders(), board.orderLimit()), left, top + 37, MUTED);
            int maximum = TradeRules.maxOrderBatches(offer.batch());
            if (top + 59 <= bottom - 77) {
                detailText(graphics, key("order_quantity", amount() * offer.batch(), maximum * offer.batch()),
                        left, top + 50, MUTED);
            }
        } else if (listing != null) {
            if (offer.potion() != null) {
                drawPotion(graphics, entry, left, top + 21);
                currencies.price(graphics, font, key("money.potion_stock", listing.count()), listing.price(),
                        left, right - 10, top + 34, MUTED, AMBER);
                var group = group();
                if (group != null && group.entries().size() > 1) {
                    graphics.centeredText(font, (variant + 1) + " / " + group.entries().size(), (detailsLeft + right) / 2,
                            contentTop + 54, WHITE);
                }
                if (listing.next() != TradeRules.NO_TIME && bottom - contentTop > 160) {
                    graphics.text(font, key("restock_in", duration(listing.next() - now())), left, contentTop + 70, MUTED);
                }
            } else {
                graphics.text(font, key(open.view() == TradeWire.View.SELL ? "held" : "in_stock", listing.count()),
                        left, top + 23, GREEN);
                currencies.price(graphics, font, key("money.per_batch", offer.batch()), listing.price(),
                        left, right - 10, top + 36, MUTED, open.view() == TradeWire.View.SELL ? GREEN : AMBER);
                if (open.view() == TradeWire.View.SELL) {
                    graphics.text(font, key("quota_cost", offer.quota() * (long) amount()), left, top + 50, MUTED);
                } else if (listing.next() != TradeRules.NO_TIME) {
                    graphics.text(font, key("restock_in", duration(listing.next() - now())), left, top + 50, MUTED);
                }
            }
        }
        if (!command.action().isEmpty() && (!command.action().equals("order") || command.quote() > 0)) {
            boolean sell = command.action().equals("sell");
            String label = command.action().equals("order") ? "locked_price" : sell ? "payout" : "total";
            currencies.price(graphics, font, key("money." + label), command.quote(),
                    left, right - 10, bottom - 77, WHITE, sell ? GREEN : AMBER);
            if (!command.action().equals("order") && command.quote() > 0) {
                long after = board.balance() + (sell ? command.quote() : -command.quote());
                currencies.price(graphics, font, key("money.after_balance"), after,
                        left, right - 10, bottom - 66, MUTED, after >= 0 ? WHITE : RED);
            }
        }
        drawResult(graphics, command, left, bottom - 55);
    }

    private void drawIndicator(GuiGraphicsExtractor graphics) {
        int count = tab == Tab.CATALOG ? groups.size() : visibleOrders.size();
        graphics.text(font, key("listing_count", count), listLeft, bottom - 12, MUTED);
        if (maxOffset() == 0) return;
        int bottomOfRail = bottom - 19;
        int total = bottomOfRail - contentTop;
        int left = detailsLeft - 5;
        int thumb = Math.max(15, total / 4);
        int top = contentTop + (total - thumb) * offset / maxOffset();
        graphics.fill(left, contentTop, left + 2, bottomOfRail, LINE);
        graphics.fill(left, top, left + 2, top + thumb, MUTED);
    }

    private void drawOrderDetails(GuiGraphicsExtractor graphics, TradeWire.OrderView order, int left, int top) {
        if (order == null) return;
        detailText(graphics, key("order_owner", order.owner()).copy().append(" · ×" + order.quantity()), left, top, MUTED);
        graphics.text(font, key("status." + order.status()), left, top + 13, GREEN);
        if (order.status().equals("waiting")) {
            graphics.text(font, key("arrival_estimate", duration(order.arrives() - now())), left, top + 26, MUTED);
        } else if (order.status().equals("protected")) {
            graphics.text(font, key("protection", duration(order.protection() - now())), left, top + 26, MUTED);
        } else if (order.status().equals("available")) {
            graphics.text(font, key("expires_in", duration(order.expires() - now())), left, top + 26, MUTED);
        }
    }

    private void drawPotion(GuiGraphicsExtractor graphics, OfferSearch.Entry entry, int left, int top) {
        var potion = entry.stack().get(DataComponents.POTION_CONTENTS);
        if (potion == null) return;
        for (var effect : potion.getAllEffects()) {
            String time = effect.getEffect().value().isInstantenous() ? key("instant").getString()
                    : duration(effect.getDuration() * 50L);
            graphics.text(font, key("potion_strength", effect.getAmplifier() + 1, time), left, top, MUTED);
            break;
        }
    }

    private void drawResult(GuiGraphicsExtractor graphics, Command command, int left, int top) {
        Component text = null;
        int color = RED;
        if (pending) {
            text = key("working");
            color = AMBER;
        } else if (!result.isEmpty() && !result.equals("updated")) {
            text = key("result." + result);
            if (result.equals("completed")) color = GREEN;
        }
        if (!command.invalid().isEmpty() && !command.invalid().equals("no_selection") && !pending
                && (text == null || command.invalid().equals("balance_low"))) {
            color = RED;
            if (command.invalid().equals("quota_low") && board.quota() != null && selectedOffer() != null) {
                long missing = (long) amount() * selectedOffer().offer().quota() * TradeRules.POINT - quotaNow();
                long needed = (long) amount() * selectedOffer().offer().quota() * TradeRules.POINT;
                text = needed > board.quota().ceiling() ? key("result.over_pool")
                        : key("quota_wait", duration(TradeRules.ceilDiv(Math.max(0, missing), board.quota().rate())));
            } else {
                text = key("result." + command.invalid());
            }
        }
        if (text != null) detailText(graphics, text, left, top, color);
    }

    private void detailText(GuiGraphicsExtractor graphics, Component label, int left, int top, int color) {
        String original = label.getString();
        int available = Math.max(18, right - left - 9);
        String fit = font.width(original) <= available ? original
                : font.plainSubstrByWidth(original, Math.max(10, available - font.width("…"))) + "…";
        graphics.text(font, fit, left, top, color);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != 0 || !contains(event.x(), event.y(), listLeft, contentTop,
                listRight - listLeft, bottom - contentTop - 8)) return false;
        if (open.view() == TradeWire.View.BANK) return false;
        if (tab == Tab.CATALOG) {
            int column = (int) (event.x() - listLeft) / GRID_CELL;
            int row = (int) (event.y() - contentTop) / GRID_CELL;
            int index = (offset + row) * gridColumns() + column;
            if (column < gridColumns() && row < rows(GRID_CELL) && index < groups.size()) {
                selected = groups.get(index).title();
                variant = 0;
                result = "";
                setQuantity(1);
                return true;
            }
        } else {
            int row = (int) (event.y() - contentTop) / ORDER_ROW;
            int index = offset + row;
            if (row < rows(ORDER_ROW) && index < visibleOrders.size()) {
                var clicked = visibleOrders.get(index);
                if (canDelete(clicked) && overOrderDelete(event.x(), event.y(), contentTop + row * ORDER_ROW)) {
                    if (!pending) send("delete_order", clicked.id(), 1, 0);
                    return true;
                }
                selectedOrder = clicked.id();
                result = "";
                updateControls();
                return true;
            }
        }
        return false;
    }

    private boolean overQuantity(double mouseX, double mouseY) {
        return quantityBox.visible && contains(mouseX, mouseY, minus.getX(), minus.getY(),
                plus.getRight() - minus.getX(), minus.getHeight());
    }

    private void scrollQuantity(int change) {
        if (change == 0) return;
        int current = amount();
        int target = Math.max(1, current + change);
        if (change < 0) {
            setQuantity(target);
            return;
        }
        int low = current;
        int high = Math.min(target, 2304);
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (command(middle).invalid().isEmpty()) low = middle;
            else high = middle - 1;
        }
        if (low > current) setQuantity(low);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (vertical != 0 && overQuantity(mouseX, mouseY)) {
            if (!pending) scrollQuantity(numberScroll.change(minecraft, quantityBox, vertical));
            return true;
        }
        numberScroll.reset();
        if (vertical != 0 && tab == Tab.CATALOG && maxCategoryOffset() > 0
                && contains(mouseX, mouseY, x + 8, contentTop, listLeft - x - 8, bottom - contentTop)) {
            categoryOffset += vertical < 0 ? 1 : -1;
            updateCategoryButtons();
            return true;
        }
        if (open.view() == TradeWire.View.BANK || vertical == 0 || !contains(mouseX, mouseY, listLeft, contentTop,
                listRight - listLeft, bottom - contentTop)) return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        offset = Math.max(0, Math.min(maxOffset(), offset + (vertical < 0 ? 1 : -1)));
        return true;
    }

    private int gridColumns() {
        return Math.max(1, (listRight - listLeft) / GRID_CELL);
    }

    private int rows(int height) {
        return Math.max(1, (bottom - contentTop - 15) / height);
    }

    private int maxOffset() {
        return tab == Tab.CATALOG ? Math.max(0, (groups.size() + gridColumns() - 1) / gridColumns() - rows(GRID_CELL))
                : Math.max(0, visibleOrders.size() - rows(ORDER_ROW));
    }

    private static boolean contains(double mx, double my, int x, int y, int width, int height) {
        return mx >= x && mx < x + width && my >= y && my < y + height;
    }

    private static String duration(long milliseconds) {
        long seconds = Math.max(0, (milliseconds + 999) / 1000);
        if (seconds >= 3600) return String.format(Locale.ROOT, "%dh %02dm", seconds / 3600, seconds / 60 % 60);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static Component key(String name, Object... arguments) {
        return Component.translatable("towntrade." + name, arguments);
    }
}
