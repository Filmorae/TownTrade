package dev.geduu.towntrade;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

final class EnchantScreen extends ServiceScreen {
    private static final int ROW = 20;
    private final Map<String, Integer> selections = new LinkedHashMap<>();
    private List<TradeWire.EnchantOption> filtered = List.of();
    private int gearOffset;
    private int optionOffset;
    private String filter = "";
    private String token = "";
    private String selected = "";
    private TradeButton plus;
    private TradeButton minus;
    private TradeButton confirm;

    EnchantScreen(TradeWire.ScreenOpen open) {
        super("screen.enchant", open);
    }

    @Override
    protected void createControls() {
        edit("enchant.search", filter, split + 8, top + 36, right - split - 21, 96, value -> {
            filter = value;
            optionOffset = 0;
            refilter();
            refreshControls();
        });
        int rightSide = right - 28;
        minus = button("minus", rightSide - 25, bottom - 82, 19, 18, () -> adjust(-1));
        plus = button("plus", rightSide, bottom - 82, 19, 18, () -> adjust(1));
        confirm = button("enchant.confirm", split + 8, bottom - 29, right - split - 22, 19, this::submit);
        confirm.primary = true;
        refilter();
    }

    @Override
    protected void boardChanged() {
        String newToken = board.enchant() == null ? "" : board.enchant().token();
        if (!newToken.equals(token)) {
            token = newToken;
            selections.clear();
            selected = "";
            optionOffset = 0;
        }
        gearOffset = Math.min(gearOffset, Math.max(0, board.gear().size() - gearRows()));
        refilter();
    }

    private void refilter() {
        if (board.enchant() == null) {
            filtered = List.of();
            return;
        }
        var query = OfferSearch.normalize(filter.strip());
        filtered = board.enchant().options().stream()
                .filter(option -> OfferSearch.normalize(name(option).getString() + " " + option.id()).contains(query))
                .toList();
        if (filtered.stream().noneMatch(option -> option.id().equals(selected))) {
            selected = filtered.isEmpty() ? "" : filtered.getFirst().id();
        }
        optionOffset = Math.min(optionOffset, Math.max(0, filtered.size() - enchantRows()));
    }

    private static Component name(TradeWire.EnchantOption option) {
        return option.translation() == null ? Component.literal(option.name())
                : Component.translatableWithFallback(option.translation(), option.name());
    }

    private TradeWire.EnchantOption option() {
        return filtered.stream().filter(entry -> entry.id().equals(selected)).findFirst().orElse(null);
    }

    private int level(TradeWire.EnchantOption option) {
        return selections.getOrDefault(option.id(), option.level());
    }

    private void adjust(int amount) {
        var option = option();
        if (pending || option == null) return;
        int current = level(option);
        int updated = Math.clamp(current + amount, option.level(), Math.max(option.level(), option.maximum()));
        if (updated == option.level()) selections.remove(option.id());
        else if (selections.containsKey(option.id()) || selections.size() < EnchantPricing.MAX_CHOICES) {
            selections.put(option.id(), updated);
        }
        result = "";
        refreshControls();
    }

    private long quote() {
        return board.enchant() == null || selections.isEmpty() ? 0 : EnchantPricing.quote(board.enchant(), selections);
    }

    private void submit() {
        long cost = quote();
        if (cost <= 0 || cost > board.balance()) return;
        var entries = selections.entrySet().stream()
                .map(entry -> new TradeWire.EnchantChoice(entry.getKey(), entry.getValue())).toList();
        request("enchant", "", board.enchant().slot(), cost, token, entries);
    }

    @Override
    protected void refreshControls() {
        if (confirm == null) return;
        long quote = quote();
        confirm.active = !pending && quote > 0 && quote <= board.balance();
        var option = option();
        plus.active = !pending && option != null && level(option) < option.maximum()
                && (selections.size() < EnchantPricing.MAX_CHOICES || selections.containsKey(option.id()));
        minus.active = !pending && option != null && level(option) > option.level();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int x = left + 10;
        int x2 = split + 8;
        text(graphics, key("enchant.choose_gear"), x, top + 43, split - 9, ACCENT);
        if (board.gear().isEmpty()) text(graphics, key("enchant.no_gear"), x, top + 65, split - 9, MUTED);
        for (int row = 0; row < gearRows() && row + gearOffset < board.gear().size(); row++) {
            var item = board.gear().get(row + gearOffset);
            int y = top + 61 + row * ROW;
            boolean active = board.enchant() != null && board.enchant().slot() == item.slot();
            outline(graphics, mouseX, mouseY, x, y, split - 7, active);
            var player = minecraft.player;
            Component itemName = Component.literal(item.name());
            if (player != null && item.slot() < player.getInventory().getContainerSize()) {
                var stack = player.getInventory().getItem(item.slot());
                if (!stack.isEmpty()) {
                    itemName = stack.getHoverName();
                    graphics.fakeItem(stack, x + 2, y + 1);
                    if (within(mouseX, mouseY, x, y, x + 19, y + ROW - 2)) {
                        graphics.setTooltipForNextFrame(font, stack, mouseX, mouseY);
                    }
                }
            }
            text(graphics, key("enchant.gear_item", item.slot() + 1, itemName), x + 21, y + 6, split - 11,
                    active ? ACCENT : TEXT);
        }

        if (board.enchant() == null) text(graphics, key("enchant.inspect"), x2, top + 72, right - 14, MUTED);
        else if (filtered.isEmpty()) text(graphics, key("enchant.no_options"), x2, top + 72, right - 14, MUTED);
        for (int row = 0; row < enchantRows() && row + optionOffset < filtered.size(); row++) {
            var option = filtered.get(row + optionOffset);
            int y = top + 61 + row * ROW;
            boolean active = option.id().equals(selected);
            outline(graphics, mouseX, mouseY, x2, y, right - 11, active);
            text(graphics, name(option), x2 + 4, y + 6, right - 70, selections.containsKey(option.id()) ? ACCENT : TEXT);
            text(graphics, key("enchant.levels", option.level(), level(option), option.maximum()),
                    right - 65, y + 6, right - 14, MUTED);
            if (within(mouseX, mouseY, x2, y, right - 11, y + ROW - 2)) {
                graphics.setTooltipForNextFrame(name(option), mouseX, mouseY);
            }
        }

        var selectedOption = option();
        if (selectedOption != null) {
            text(graphics, key("enchant.level_label", level(selectedOption)), x2, bottom - 77, right - 58, TEXT);
        }
        long quote = quote();
        currencies.price(graphics, font, key("money.enchant_total", selections.size()), quote,
                x2, right - 13, bottom - 60, TEXT, PRICE);
        if (!pending && quote > 0 && quote <= board.balance() && result.isEmpty()) {
            currencies.price(graphics, font, key("money.after_balance"), board.balance() - quote,
                    x2, right - 12, bottom - 45, MUTED, TEXT);
        } else {
            footer(graphics, quote > board.balance() ? "balance_low" : "", x2, bottom - 45);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (selectedOption != null && overLevel(mouseX, mouseY)) NumberScroll.hint(graphics, mouseX, mouseY);
    }

    private static void outline(GuiGraphicsExtractor graphics, int mx, int my, int x, int y, int right, boolean selected) {
        graphics.fill(x, y, right, y + ROW - 2, 0xff273139);
        if (selected || within(mx, my, x, y, right, y + ROW - 2)) {
            graphics.outline(x, y, right - x, ROW - 2, selected ? ACCENT : PRICE);
        }
    }

    private int gearRows() {
        return Math.max(1, (bottom - top - 71) / ROW);
    }

    private int enchantRows() {
        return Math.max(1, (bottom - top - 149) / ROW);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (pending || event.button() != 0 || event.y() < top + 61) return false;
        int row = (int) (event.y() - top - 61) / ROW;
        if (event.x() >= left + 10 && event.x() < split - 7 && row < gearRows()) {
            int index = gearOffset + row;
            if (index >= board.gear().size()) return false;
            var item = board.gear().get(index);
            request("inspect", "", item.slot(), 0, null, null);
            return true;
        }
        if (event.x() >= split + 8 && event.x() < right - 10 && row < enchantRows()) {
            int index = optionOffset + row;
            if (index >= filtered.size()) return false;
            selected = filtered.get(index).id();
            result = "";
            refreshControls();
            return true;
        }
        return false;
    }

    private boolean overLevel(double x, double y) {
        return within(x, y, split + 8, bottom - 82, right - 8, bottom - 64);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical != 0 && overLevel(x, y) && option() != null) {
            int delta = numberScroll.change(minecraft, selected, vertical);
            if (delta != 0) adjust(delta);
            return true;
        }
        numberScroll.reset();
        if (vertical == 0 || y < top + 60 || y >= bottom - 7) return super.mouseScrolled(x, y, horizontal, vertical);
        int change = vertical < 0 ? 1 : -1;
        if (x >= left + 9 && x < split - 6) {
            gearOffset = Math.clamp(gearOffset + change, 0, Math.max(0, board.gear().size() - gearRows()));
            return true;
        }
        if (x >= split && x < right - 8 && y < bottom - 85) {
            optionOffset = Math.clamp(optionOffset + change, 0, Math.max(0, filtered.size() - enchantRows()));
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
}
