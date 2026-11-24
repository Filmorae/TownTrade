package dev.geduu.towntrade;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

final class CurrencyDisplay {
    private static final List<String> NAMES = List.of("copper", "iron", "gold");
    private final List<ItemStack> icons;

    CurrencyDisplay(List<TradeConfig.Coin> coins) {
        icons = coins == null ? List.of() : coins.stream().map(coin -> TradeItems.stack(coin.item(), null)).toList();
    }

    static Component name(int denomination) {
        return Component.translatable("towntrade.coin." + NAMES.get(denomination));
    }

    ItemStack icon(int denomination) {
        return denomination < icons.size() ? icons.get(denomination) : ItemStack.EMPTY;
    }

    void price(GuiGraphicsExtractor graphics, Font font, Component label, long value,
               int left, int right, int y, int labelColor, int amountColor) {
        int size = 12;
        int amountX = right - width(font, 0, value, size, true);
        int available = amountX - left - 5;
        if (available >= font.width("…")) {
            String text = label.getString();
            if (font.width(text) > available) text = font.plainSubstrByWidth(text, available - font.width("…")) + "…";
            graphics.text(font, text, left, y, labelColor);
        }
        amount(graphics, font, 0, value, amountX, y, size, amountColor, true);
    }

    int width(Font font, int denomination, long value, int size, boolean showUnit) {
        return font.width(text(denomination, value, showUnit)) + (icon(denomination).isEmpty() ? 0 : size + 3);
    }

    void amount(GuiGraphicsExtractor graphics, Font font, int denomination, long value,
                int x, int y, int size, int color, boolean showUnit) {
        var item = icon(denomination);
        drawIcon(graphics, item, x, y + (8 - size) / 2, size);
        graphics.text(font, text(denomination, value, showUnit), x + (item.isEmpty() ? 0 : size + 3), y, color);
    }

    private String text(int denomination, long value, boolean showUnit) {
        return showUnit && icon(denomination).isEmpty() ? value + " " + name(denomination).getString() : Long.toString(value);
    }

    static void drawIcon(GuiGraphicsExtractor graphics, ItemStack item, int x, int y, int size) {
        if (item.isEmpty()) return;
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(size / 16f);
        graphics.fakeItem(item, 0, 0);
        pose.popMatrix();
    }
}
