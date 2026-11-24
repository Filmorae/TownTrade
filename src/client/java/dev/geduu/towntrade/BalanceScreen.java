package dev.geduu.towntrade;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;

final class BalanceScreen extends Screen {
    private static final int[] VALUES = {1, 16, 256};

    private final String token;
    private final CurrencyDisplay currencies;
    private long balance;
    private long received;
    private long serverTime;
    private long activeUntil;
    private long unusedMillis;
    private int permitLines;
    private TradeButton close;
    private int denomination;
    private int heldShifts;
    private int left, top, right, bottom;

    BalanceScreen(TradeWire.ScreenOpen open) {
        super(ServiceScreen.key("info.title"));
        token = open.token();
        currencies = new CurrencyDisplay(open.currencies());
        setBoard(open.board());
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(270, width - 16);
        left = (width - panelWidth) / 2;
        right = left + panelWidth;
        close = addRenderableWidget(new TradeButton(font, ServiceScreen.key("close"),
                right - 26, 0, 18, 18, this::onClose));
        updateLayout();
    }

    private void setBoard(TradeWire.Board board) {
        balance = board.balance();
        received = System.nanoTime();
        serverTime = board.serverTime();
        var permit = board.permit();
        activeUntil = permit == null ? 0 : permit.activeUntil();
        unusedMillis = permit == null ? 0 : permit.unusedMillis();
        if (close != null) updateLayout();
    }

    private long remaining() {
        return activeUntil - (serverTime + (System.nanoTime() - received) / 1_000_000);
    }

    private void updateLayout() {
        permitLines = (remaining() > 0 ? 1 : 0) + (unusedMillis > 0 ? 1 : 0);
        int panelHeight = 134 + (permitLines == 0 ? 0 : 14 + 14 * permitLines);
        top = (height - panelHeight) / 2;
        bottom = top + panelHeight;
        close.setY(top + 8);
    }

    @Override
    public void tick() {
        int lines = (remaining() > 0 ? 1 : 0) + (unusedMillis > 0 ? 1 : 0);
        if (lines != permitLines) updateLayout();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(left, top, right, bottom, 0xe913181d);
        graphics.outline(left, top, right - left, bottom - top, 0xff4b5962);
        graphics.fill(left + 12, top + 32, right - 12, top + 101, 0xf4212930);
        if (permitLines > 0) graphics.fill(left + 12, top + 106, right - 12, bottom - 29, 0xf4212930);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int center = (left + right) / 2;
        graphics.text(font, getTitle(), left + 12, top + 13, ServiceScreen.TEXT);
        long value = balance / VALUES[denomination];
        int amountWidth = currencies.width(font, denomination, value, 12, false);
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(center - amountWidth, top + 47);
        pose.scale(2);
        currencies.amount(graphics, font, denomination, value, 0, 0, 12, ServiceScreen.PRICE, false);
        pose.popMatrix();

        graphics.centeredText(font, CurrencyDisplay.name(denomination), center, top + 68, ServiceScreen.TEXT);
        long remainder = balance % VALUES[denomination];
        if (remainder > 0) drawRemainder(graphics, center, remainder);
        long time = remaining();
        int permitY = top + 113;
        if (time > 0) {
            graphics.centeredText(font, ServiceScreen.key("permit.remaining", TradeRules.permitDuration(time)),
                    center, permitY, ServiceScreen.ACCENT);
            permitY += 14;
        }
        if (unusedMillis > 0) {
            graphics.centeredText(font, ServiceScreen.key("permit.unused", TradeRules.permitDuration(unusedMillis)),
                    center, permitY, ServiceScreen.TEXT);
        }
        graphics.centeredText(font, ServiceScreen.key("info.cycle"), center, bottom - 21, ServiceScreen.MUTED);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private void drawRemainder(GuiGraphicsExtractor graphics, int center, long remainder) {
        long iron = remainder / VALUES[1];
        long copper = remainder % VALUES[1];
        int ironWidth = iron == 0 ? 0 : currencies.width(font, 1, iron, 10, true);
        int copperWidth = copper == 0 ? 0 : currencies.width(font, 0, copper, 10, true);
        String plus = "+ ";
        int gap = iron > 0 && copper > 0 ? 8 : 0;
        int x = center - (ironWidth + copperWidth + gap + font.width(plus)) / 2;
        int y = top + 84;
        graphics.text(font, plus, x, y, ServiceScreen.MUTED);
        x += font.width(plus);
        if (iron > 0) currencies.amount(graphics, font, 1, iron, x, y, 10, ServiceScreen.MUTED, true);
        if (copper > 0) currencies.amount(graphics, font, 0, copper, x + ironWidth + gap, y, 10, ServiceScreen.MUTED, true);
    }

    void update(TradeWire.Update update) {
        if (token.equals(update.token())) setBoard(update.board());
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int shift = shiftBit(event);
        if (shift == 0) return super.keyPressed(event);
        if (heldShifts == 0) denomination = (denomination + 1) % VALUES.length;
        heldShifts |= shift;
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        int shift = shiftBit(event);
        if (shift == 0) return super.keyReleased(event);
        heldShifts &= ~shift;
        return true;
    }

    private static int shiftBit(KeyEvent event) {
        return switch (event.key()) {
            case InputConstants.KEY_LSHIFT -> 1;
            case InputConstants.KEY_RSHIFT -> 2;
            default -> 0;
        };
    }

    @Override
    public void removed() {
        TradeClient.send(new TradeWire.Request(token, 1, "close", "", 0, 0));
        super.removed();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
