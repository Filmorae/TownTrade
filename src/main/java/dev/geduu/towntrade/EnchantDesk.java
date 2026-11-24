package dev.geduu.towntrade;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

final class EnchantDesk {
    private record Entry(TradeWire.EnchantOption option, Holder<Enchantment> enchantment) {}

    record Inspection(int slot, ItemStack stack, TradeWire.EnchantView view, Map<String, Entry> entries) {}
    record Upgrade(long cost, ItemStack result) {}

    private final ServiceConfig config;

    EnchantDesk(ServiceConfig config) {
        this.config = config;
    }

    static List<TradeWire.GearSlot> inventory(ServerPlayer player) {
        var inventory = player.getInventory();
        var result = new ArrayList<TradeWire.GearSlot>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!eligible(stack)) continue;
            result.add(new TradeWire.GearSlot(slot, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    stack.getHoverName().getString()));
        }
        return result;
    }

    Inspection inspect(ServerPlayer player, int slot) {
        var inventory = player.getInventory();
        if (slot < 0 || slot >= inventory.getContainerSize()) return null;
        var stack = inventory.getItem(slot);
        if (!eligible(stack)) return null;
        var current = EnchantmentHelper.getEnchantmentsForCrafting(stack);
        var registry = player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        var entries = new LinkedHashMap<String, Entry>();
        registry.listElements().sorted(Comparator.comparing(holder -> holder.key().identifier().toString()))
                .forEach(holder -> {
                    if (!holder.value().isSupportedItem(stack)) return;
                    if (!config.sellCurses() && holder.is(EnchantmentTags.CURSE)) return;
                    int level = current.getLevel(holder);
                    int max = Math.min(holder.value().getMaxLevel(), 255);
                    if (max < 1) return;
                    String id = holder.key().identifier().toString();
                    var description = holder.value().description();
                    String translation = description.getContents() instanceof TranslatableContents text ? text.getKey() : null;
                    var option = new TradeWire.EnchantOption(id, translation, description.getString(), level, max,
                            weight(holder));
                    entries.put(id, new Entry(option, holder));
                });
        var existing = new HashMap<String, EnchantPricing.Power>();
        for (var enchantment : current.entrySet()) {
            var holder = enchantment.getKey();
            if (!config.sellCurses() && holder.is(EnchantmentTags.CURSE)) continue;
            holder.unwrapKey().ifPresent(key -> existing.put(key.identifier().toString(),
                    new EnchantPricing.Power(enchantment.getIntValue(), weight(holder))));
        }
        var view = new TradeWire.EnchantView(slot, UUID.randomUUID().toString(), stack.getHoverName().getString(),
                List.copyOf(existing.values()), entries.values().stream().map(Entry::option).toList());
        return new Inspection(slot, stack.copy(), view, Map.copyOf(entries));
    }

    Upgrade upgrade(Inspection inspection, List<TradeWire.EnchantChoice> choices) {
        if (choices == null || choices.isEmpty() || choices.size() > EnchantPricing.MAX_CHOICES) return null;
        var levels = new HashMap<String, Integer>();
        var enchantments = new ItemEnchantments.Mutable(
                EnchantmentHelper.getEnchantmentsForCrafting(inspection.stack()));
        for (var choice : choices) {
            if (choice == null || choice.id() == null || levels.putIfAbsent(choice.id(), choice.level()) != null) return null;
            var entry = inspection.entries().get(choice.id());
            if (entry == null || choice.level() <= entry.option().level() || choice.level() > entry.option().maximum()) return null;
            enchantments.set(entry.enchantment(), choice.level());
        }
        long cost = EnchantPricing.quote(inspection.view(), levels);
        var stack = inspection.stack().copy();
        EnchantmentHelper.setEnchantments(stack, enchantments.toImmutable());
        return new Upgrade(cost, stack);
    }

    private int weight(Holder<Enchantment> holder) {
        int fallback = 16 + 8 * Math.max(0, Math.min(128, holder.value().getAnvilCost()));
        return holder.unwrapKey().map(key -> config.enchantWeights()
                .getOrDefault(key.identifier().toString(), fallback)).orElse(fallback);
    }

    private static boolean eligible(ItemStack stack) {
        return !stack.isEmpty() && stack.getCount() == 1 && !stack.is(Items.BOOK) && !stack.is(Items.ENCHANTED_BOOK)
                && EnchantmentHelper.canStoreEnchantments(stack);
    }
}
