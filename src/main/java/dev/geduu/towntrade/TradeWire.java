package dev.geduu.towntrade;

import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Map;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

final class TradeWire {
    enum View {
        BANK, BUY, SELL, PERMIT, ENCHANT, INFO
    }

    record Offer(String id, String item, String potion, String category, int batch,
                 int basePrice, int quota, boolean orderOnly, long waitMillis) {}
    record Listing(int count, long next, int price) {}
    record QuotaInfo(long units, long ceiling, int rate) {}
    record OrderView(String id, String product, String owner, boolean own, long price,
                     long placed, long arrives, long protection, long expires, String status) {}

    record PermitView(ServiceConfig.PermitPricing pricing, long unusedMillis, long activeUntil) {}
    record GearSlot(int slot, String item, String name) {}
    record EnchantOption(String id, String translation, String name, int level, int maximum, int weight) {}
    record EnchantView(int slot, String token, String name, List<EnchantPricing.Power> powers,
                       List<EnchantOption> options) {}
    record EnchantChoice(String id, int level) {}

    record Board(long serverTime, long balance, String season, int orderLimit, int activeOrders,
                 List<Integer> coins, QuotaInfo quota, Map<String, Listing> listings, List<OrderView> orders,
                 PermitView permit, List<GearSlot> gear, EnchantView enchant) {}

    record ScreenOpen(String token, View view, String pool, List<Offer> offers, Board board,
                      List<TradeConfig.Coin> currencies) {}
    record Update(String token, Board board, String result, boolean response) {}
    record Request(String token, int sequence, String action, String target, int count, long quote,
                   String preview, List<EnchantChoice> choices) {
        Request(String token, int sequence, String action, String target, int count, long quote) {
            this(token, sequence, action, target, count, quote, null, null);
        }
    }

    record OpenPacket(String data) implements CustomPacketPayload {
        static final Type<OpenPacket> TYPE = payloadType("open_v2");
        static final StreamCodec<ByteBuf, OpenPacket> CODEC = codec(1_500_000, OpenPacket::data, OpenPacket::new);
        @Override public Type<OpenPacket> type() { return TYPE; }
    }
    record UpdatePacket(String data) implements CustomPacketPayload {
        static final Type<UpdatePacket> TYPE = payloadType("update_v2");
        static final StreamCodec<ByteBuf, UpdatePacket> CODEC = codec(1_000_000, UpdatePacket::data, UpdatePacket::new);
        @Override public Type<UpdatePacket> type() { return TYPE; }
    }
    record ActionPacket(String data) implements CustomPacketPayload {
        static final Type<ActionPacket> TYPE = payloadType("action_v2");
        static final StreamCodec<ByteBuf, ActionPacket> CODEC = codec(8192, ActionPacket::data, ActionPacket::new);
        @Override public Type<ActionPacket> type() { return TYPE; }
    }

    private static <T extends CustomPacketPayload> Type<T> payloadType(String name) {
        return new Type<>(Identifier.fromNamespaceAndPath("towntrade", name));
    }

    private static <T> StreamCodec<ByteBuf, T> codec(int limit, java.util.function.Function<T, String> getter,
                                                     java.util.function.Function<String, T> constructor) {
        return StreamCodec.composite(ByteBufCodecs.stringUtf8(limit), getter, constructor);
    }

    private TradeWire() {}
}
