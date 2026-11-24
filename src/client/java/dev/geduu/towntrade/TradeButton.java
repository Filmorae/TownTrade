package dev.geduu.towntrade;

import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

final class TradeButton extends Button {
    private final Font font;
    BooleanSupplier selected = () -> false;
    boolean primary;
    ItemStack icon = ItemStack.EMPTY;

    TradeButton(Font font, Component title, int x, int y, int width, int height, Runnable action) {
        super(x, y, width, height, title, ignored -> action.run(), DEFAULT_NARRATION);
        this.font = font;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        boolean chosen = selected.getAsBoolean();
        int background = !active ? 0xff222a2e : primary ? 0xff355446 : chosen ? 0xff31434a : 0xff232c33;
        int border = !active ? 0xff364147 : isHoveredOrFocused() ? 0xffc5dfcd
                : primary || chosen ? 0xff83b69b : 0xff4b5962;
        int foreground = active ? 0xffedf4f1 : 0xff9aa6ac;
        int left = getX();
        int top = getY();
        graphics.fill(left, top, getRight(), getBottom(), background);
        graphics.outline(left, top, getWidth(), getHeight(), border);
        if (chosen && active) graphics.fill(left + 1, top + 2, left + 3, getBottom() - 2, 0xffa5d4ad);
        String original = getMessage().getString();
        int iconSpace = icon.isEmpty() ? 0 : 19;
        String label = font.plainSubstrByWidth(original, Math.max(1, getWidth() - 8 - iconSpace));
        int textX = left + (getWidth() - iconSpace - font.width(label)) / 2;
        CurrencyDisplay.drawIcon(graphics, icon, textX, top + (getHeight() - 16) / 2, 16);
        graphics.text(font, label, textX + iconSpace, top + (getHeight() - 8) / 2, foreground);
        if (isHovered() && !label.equals(original)) graphics.setTooltipForNextFrame(getMessage(), mouseX, mouseY);
    }
}
