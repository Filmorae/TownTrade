package dev.geduu.towntrade;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;

final class PermitScreen extends ServiceScreen {
    private String duration;
    private TradeButton confirm;
    private EditBox durationBox;

    PermitScreen(TradeWire.ScreenOpen open) {
        super("screen.permit", open);
        duration = Integer.toString(board.permit().pricing().minimumMinutes());
    }

    @Override
    protected void createControls() {
        int x = left + 10;
        int size = split - x - 12;
        durationBox = edit("permit.minutes", duration, x, top + 58, size, 4, value -> {
            duration = value;
            result = "";
            refreshControls();
        });
        confirm = button("permit.buy", split + 9, bottom - 30, right - split - 24, 20, () -> {
            if (problem().isEmpty()) request("permit", "", minutes(), price(), null, null);
        });
        confirm.primary = true;
    }

    private int minutes() {
        try {
            return Integer.parseInt(duration);
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    private long price() {
        return board.permit().pricing().price(minutes());
    }

    private String problem() {
        var pricing = board.permit().pricing();
        int minutes = minutes();
        if (minutes < pricing.minimumMinutes() || minutes > pricing.maximumMinutes()) return "permit_duration";
        return price() > board.balance() ? "balance_low" : "";
    }

    @Override
    protected void refreshControls() {
        if (confirm != null) confirm.active = !pending && problem().isEmpty();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int x = left + 10;
        int x2 = split + 10;
        var permit = board.permit();
        var pricing = permit.pricing();
        text(graphics, key("permit.minutes"), x, top + 44, split - 12, TEXT);
        text(graphics, key("permit.range", pricing.minimumMinutes(), pricing.maximumMinutes()),
                x, top + 81, split - 12, MUTED);
        text(graphics, key("permit.activation_label"), x, top + 109, split - 12, ACCENT);
        graphics.text(font, "/towntrade active", x, top + 125, TEXT);
        text(graphics, key("permit.real_time"), x, top + 148, split - 12, MUTED);

        long remaining = permit.activeUntil() - now();
        text(graphics, key("permit.unused", TradeRules.permitDuration(permit.unusedMillis())),
                x2, top + 44, right - 12, TEXT);
        if (remaining > 0) {
            text(graphics, key("permit.remaining", TradeRules.permitDuration(remaining)),
                    x2, top + 59, right - 12, ACCENT);
        } else {
            text(graphics, key("permit.inactive"), x2, top + 59, right - 12, MUTED);
        }

        String problem = problem();
        if (!problem.equals("permit_duration")) {
            currencies.price(graphics, font, key("money.total"), price(), x2, right - 12,
                    bottom - 64, TEXT, PRICE);
        }
        if (!pending && problem.isEmpty() && result.isEmpty()) {
            currencies.price(graphics, font, key("money.after_balance"), board.balance() - price(),
                    x2, right - 12, bottom - 47, MUTED, TEXT);
        } else {
            footer(graphics, problem, x2, bottom - 47);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (NumberScroll.over(durationBox, mouseX, mouseY)) NumberScroll.hint(graphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical == 0 || !NumberScroll.over(durationBox, x, y)) {
            numberScroll.reset();
            return super.mouseScrolled(x, y, horizontal, vertical);
        }
        if (!pending) {
            var pricing = board.permit().pricing();
            int delta = numberScroll.change(minecraft, durationBox, vertical);
            int current = minutes();
            int minimum = pricing.minimumMinutes();
            int updated = NumberScroll.adjust(current < 0 ? minimum : current, delta, minimum, pricing.maximumMinutes());
            if (delta != 0 && updated != current) durationBox.setValue(Integer.toString(updated));
        }
        return true;
    }
}
