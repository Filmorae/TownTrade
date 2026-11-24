package dev.geduu.towntrade;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

final class InventoryPlan {
    private static final int MAIN_SLOTS = 36;
    private record Slot(int index, ItemStack before, ItemStack after) {}

    private final Inventory inventory;
    private final List<Slot> slots;

    private InventoryPlan(Inventory inventory, List<Slot> slots) {
        this.inventory = inventory;
        this.slots = slots;
    }

    static int count(Inventory inventory, ItemStack item) {
        int amount = 0;
        if (item.isEmpty()) return amount;
        for (int index = 0; index < MAIN_SLOTS; index++) {
            var stack = inventory.getItem(index);
            if (stack.is(item.getItem()) && ItemStack.isSameItemSameComponents(stack, item)) {
                amount += stack.getCount();
            }
        }
        return amount;
    }

    static InventoryPlan remove(Inventory inventory, ItemStack item, int count) {
        var slots = new ArrayList<Slot>();
        for (int index = 0; index < MAIN_SLOTS && count > 0; index++) {
            var stack = inventory.getItem(index);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, item)) continue;
            int removed = Math.min(stack.getCount(), count);
            var after = stack.copy();
            after.shrink(removed);
            slots.add(new Slot(index, stack.copy(), after));
            count -= removed;
        }
        return count == 0 ? new InventoryPlan(inventory, slots) : null;
    }

    static InventoryPlan add(Inventory inventory, ItemStack item, int count) {
        var slots = new ArrayList<Slot>();
        for (int index = 0; index < MAIN_SLOTS && count > 0; index++) {
            var stack = inventory.getItem(index);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, item)) continue;
            int added = Math.min(count, Math.max(0, stack.getMaxStackSize() - stack.getCount()));
            if (added == 0) continue;
            var after = stack.copy();
            after.grow(added);
            slots.add(new Slot(index, stack.copy(), after));
            count -= added;
        }
        for (int index = 0; index < MAIN_SLOTS && count > 0; index++) {
            if (!inventory.getItem(index).isEmpty()) continue;
            int added = Math.min(count, item.getMaxStackSize());
            slots.add(new Slot(index, ItemStack.EMPTY, item.copyWithCount(added)));
            count -= added;
        }
        return count == 0 ? new InventoryPlan(inventory, slots) : null;
    }

    static InventoryPlan replace(Inventory inventory, int index, ItemStack before, ItemStack after) {
        return new InventoryPlan(inventory, List.of(new Slot(index, before.copy(), after.copy())));
    }

    boolean stillMatches() {
        for (Slot slot : slots) {
            if (!ItemStack.matches(slot.before(), inventory.getItem(slot.index()))) return false;
        }
        return true;
    }

    void apply() {
        for (Slot slot : slots) inventory.setItem(slot.index(), slot.after().copy());
        inventory.setChanged();
    }
}
