package dev.geduu.towntrade;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

final class OfferSearch {
    record Entry(TradeWire.Offer offer, ItemStack stack, String name, String terms, String source, List<String> tags) {
        boolean matches(String[] tokens) {
            for (String token : tokens) {
                if (token.isBlank()) continue;
                if (token.startsWith("@")) {
                    if (!source.contains(token.substring(1))) return false;
                } else if (token.startsWith("#")) {
                    String tag = token.substring(1);
                    if (tags.stream().noneMatch(value -> value.contains(tag))) return false;
                } else if (!terms.contains(token)) {
                    return false;
                }
            }
            return true;
        }
    }

    private final Map<String, Map<String, String>> languages = new HashMap<>();

    List<Entry> index(List<TradeWire.Offer> offers) {
        var entries = new ArrayList<Entry>();
        for (var offer : offers) {
            var stack = TradeItems.stack(offer.item(), offer.potion());
            if (stack.isEmpty()) continue;
            String namespace = Identifier.parse(offer.item()).getNamespace();
            String source = FabricLoader.getInstance().getModContainer(namespace)
                    .map(container -> container.getMetadata().getName()).orElse(namespace);
            var locale = languages.computeIfAbsent(namespace, this::translations);
            var component = stack.getHoverName();
            String name = component.getString();
            String key = component.getContents() instanceof TranslatableContents text
                    ? text.getKey() : stack.getItem().getDescriptionId();
            String terms = name + " " + locale.getOrDefault(key, "") + " " + offer.item()
                    + " " + offer.id() + " " + offer.item().replace('_', ' ');
            var tags = stack.typeHolder().tags().map(tag -> normalize(tag.location().toString())).toList();
            entries.add(new Entry(offer, stack, name, normalize(terms), normalize(namespace + " " + source), tags));
        }
        return entries;
    }

    private Map<String, String> translations(String namespace) {
        var results = new HashMap<String, String>();
        var resources = Minecraft.getInstance().getResourceManager();
        for (String language : List.of("en_us", "zh_cn")) {
            var id = Identifier.fromNamespaceAndPath(namespace, "lang/" + language + ".json");
            for (var resource : resources.getResourceStack(id)) {
                try (var reader = resource.openAsReader()) {
                    JsonObject data = JsonParser.parseReader(reader).getAsJsonObject();
                    for (var entry : data.entrySet()) {
                        if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) {
                            results.merge(entry.getKey(), entry.getValue().getAsString(), (first, second) -> first + " " + second);
                        }
                    }
                } catch (IOException | RuntimeException ignored) {
                    // Names of items in this locale are still available from Minecraft.
                }
            }
        }
        return results;
    }

    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
