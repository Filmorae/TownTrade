package dev.geduu.towntrade;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

final class TradeItems {
    static ItemStack stack(String item, String potion) {
        var type = BuiltInRegistries.ITEM.getOptional(Identifier.parse(item)).orElse(Items.AIR);
        if (type == Items.AIR) return ItemStack.EMPTY;
        var result = new ItemStack(type);
        if (potion != null) {
            var holder = BuiltInRegistries.POTION.get(Identifier.parse(potion));
            if (holder.isEmpty()) return ItemStack.EMPTY;
            result.set(DataComponents.POTION_CONTENTS, new PotionContents(holder.get()));
        }
        return result;
    }

    private TradeItems() {}
}
