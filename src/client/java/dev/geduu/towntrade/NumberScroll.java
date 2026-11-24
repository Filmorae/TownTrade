package dev.geduu.towntrade;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

final class NumberScroll {
    private Object target;
    private boolean shift;
    private double remainder;

    int change(Minecraft client, Object control, double vertical) {
        var window = client.getWindow();
        boolean fast = InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RSHIFT);
        if (control != target || fast != shift || Math.signum(vertical) != Math.signum(remainder)) remainder = 0;
        target = control;
        shift = fast;
        double total = remainder + vertical;
        int steps = (int) total;
        remainder = total - steps;
        return Math.clamp(steps, -100, 100) * (shift ? 10 : 1);
    }

    void reset() {
        target = null;
        remainder = 0;
    }

    static int adjust(int current, int delta, int minimum, int maximum) {
        if (delta == 0) return current;
        // An out-of-range typed value can move toward the permitted range in either direction.
        return delta > 0 ? Math.min(Math.max(current, maximum), Math.max(minimum, current + delta))
                : Math.max(Math.min(current, minimum), Math.min(maximum, current + delta));
    }

    static boolean over(AbstractWidget control, double x, double y) {
        return control.visible && x >= control.getX() && x < control.getRight()
                && y >= control.getY() && y < control.getBottom();
    }

    static void hint(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.setTooltipForNextFrame(Component.translatable("towntrade.number.wheel_hint"), x, y);
    }
}
