package dev.geduu.towntrade;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

abstract class ServiceScreen extends Screen {
    protected static final int TEXT = 0xffedf4f1;
    protected static final int MUTED = 0xffaebdc2;
    protected static final int ACCENT = 0xffa5d4ad;
    protected static final int PRICE = 0xffffd583;
    protected static final int WARNING = 0xffff9c8a;

    protected final CurrencyDisplay currencies;
    protected final NumberScroll numberScroll = new NumberScroll();
    protected TradeWire.Board board;
    protected int left, right, top, bottom, split;
    protected boolean pending;
    protected String result = "";
    private final String token;
    private int sequence;
    private long received;
    private long requested;

    ServiceScreen(String title, TradeWire.ScreenOpen open) {
        super(key(title));
        token = open.token();
        currencies = new CurrencyDisplay(open.currencies());
        board = open.board();
        received = System.nanoTime();
    }

    @Override
    protected final void init() {
        left = (width - Math.min(width - 12, 668)) / 2;
        right = width - left;
        top = (height - Math.min(height - 12, 424)) / 2;
        bottom = height - top;
        split = left + (right - left) * 48 / 100;
        button("close", right - 27, top + 8, 19, 18, this::onClose);
        createControls();
        refreshControls();
    }

    protected abstract void createControls();
    protected abstract void refreshControls();

    protected void boardChanged() {}

    final void update(TradeWire.Update update) {
        if (!token.equals(update.token())) return;
        board = update.board();
        received = System.nanoTime();
        if (update.response()) {
            pending = false;
            result = update.result();
        }
        boardChanged();
        refreshControls();
    }

    @Override
    public void tick() {
        if (pending && System.nanoTime() - requested > 12_000_000_000L) {
            pending = false;
            result = "timed_out";
        }
        refreshControls();
    }

    @Override
    public void removed() {
        TradeClient.send(new TradeWire.Request(token, ++sequence, "close", "", 0, 0));
        super.removed();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(left, top, right, bottom, 0xe913181d);
        graphics.outline(left, top, right - left, bottom - top, 0xff4b5962);
        graphics.fill(split, top + 33, right - 7, bottom - 7, 0xf4212930);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        text(graphics, getTitle(), left + 10, top + 13, split - 16, TEXT);
        currencies.price(graphics, font, key("money.balance"), board.balance(), split + 8, right - 34,
                top + 13, MUTED, PRICE);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    protected final TradeButton button(String label, int x, int y, int w, int h, Runnable action) {
        return addRenderableWidget(new TradeButton(font, key(label), x, y, w, h, action));
    }

    protected final EditBox edit(String label, String value, int x, int y, int w, int limit,
                                 java.util.function.Consumer<String> change) {
        var field = new EditBox(font, x, y, w, 19, key(label));
        field.setHint(key(label));
        field.setMaxLength(limit);
        field.setValue(value);
        field.setResponder(change);
        return addRenderableWidget(field);
    }

    protected final void text(GuiGraphicsExtractor graphics, Component label, int x, int y, int end, int color) {
        String value = label.getString();
        int space = Math.max(0, end - x);
        if (font.width(value) > space) {
            value = font.plainSubstrByWidth(value, Math.max(0, space - font.width("…"))) + "…";
        }
        graphics.text(font, value, x, y, color);
    }

    protected final void footer(GuiGraphicsExtractor graphics, String problem, int x, int y) {
        if (pending) {
            text(graphics, key("working"), x, y, right - 12, PRICE);
        } else if (!result.isEmpty() && !result.equals("updated")
                && !result.equals("invalid")) {
            text(graphics, key("result." + result), x, y, right - 12,
                    result.equals("completed") ? ACCENT : WARNING);
        } else if (!problem.isEmpty()) {
            text(graphics, key("result." + problem), x, y, right - 12, WARNING);
        }
    }

    protected final void request(String action, String target, int count, long quote, String preview,
                                 List<TradeWire.EnchantChoice> choices) {
        if (pending) return;
        pending = true;
        result = "";
        requested = System.nanoTime();
        TradeClient.send(new TradeWire.Request(token, ++sequence, action, target, count, quote, preview, choices));
        refreshControls();
    }

    protected final long now() {
        return board.serverTime() + (System.nanoTime() - received) / 1_000_000;
    }

    protected static Component key(String id, Object... params) {
        return Component.translatable("towntrade." + id, params);
    }

    protected static boolean within(double x, double y, int x1, int y1, int x2, int y2) {
        return x >= x1 && x < x2 && y >= y1 && y < y2;
    }
}
