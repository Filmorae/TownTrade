package dev.geduu.towntrade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

record BoardSnapshot(Status status, String timezone, List<Restock> restocks, List<Offer> orders) {
    static final int RESTOCK_LIMIT = 3;

    enum Status {
        STARTING, READY, OFFLINE;
        static final Codec<Status> CODEC = Codec.intRange(0, 2).xmap(index -> values()[index], Status::ordinal);
    }

    record Restock(Component item, long at) {
        static final Codec<Restock> CODEC = RecordCodecBuilder.create(fields -> fields.group(
                ComponentSerialization.CODEC.fieldOf("item").forGetter(Restock::item),
                Codec.LONG.fieldOf("at").forGetter(Restock::at)
        ).apply(fields, Restock::new));
    }

    enum Phase {
        WAITING, PROTECTED, PUBLIC;
        static final Codec<Phase> CODEC = Codec.intRange(0, 2).xmap(index -> values()[index], Phase::ordinal);
    }

    record Offer(Component item, int quantity, long price, long expires, String requester,
                 long arrives, long protectsUntil, Phase phase) {
        static final Codec<Offer> CODEC = RecordCodecBuilder.create(fields -> fields.group(
                ComponentSerialization.CODEC.fieldOf("item").forGetter(Offer::item),
                Codec.intRange(1, 2304).fieldOf("quantity").forGetter(Offer::quantity),
                Codec.LONG.fieldOf("price").forGetter(Offer::price),
                Codec.LONG.fieldOf("expires").forGetter(Offer::expires),
                Codec.STRING.optionalFieldOf("requester", "").forGetter(Offer::requester),
                Codec.LONG.optionalFieldOf("arrives", 0L).forGetter(Offer::arrives),
                Codec.LONG.optionalFieldOf("protects_until", 0L).forGetter(Offer::protectsUntil),
                Phase.CODEC.optionalFieldOf("phase", Phase.PUBLIC).forGetter(Offer::phase)
        ).apply(fields, Offer::new));
    }

    static final BoardSnapshot STARTING = empty(Status.STARTING);
    static final BoardSnapshot OFFLINE = empty(Status.OFFLINE);
    static final Codec<BoardSnapshot> CODEC = RecordCodecBuilder.create(fields -> fields.group(
            Status.CODEC.fieldOf("status").forGetter(BoardSnapshot::status),
            Codec.STRING.fieldOf("timezone").forGetter(BoardSnapshot::timezone),
            Restock.CODEC.listOf(0, RESTOCK_LIMIT).fieldOf("restocks").forGetter(BoardSnapshot::restocks),
            Offer.CODEC.listOf(0, TradeRules.SERVER_ORDER_LIMIT).fieldOf("orders").forGetter(BoardSnapshot::orders)
    ).apply(fields, BoardSnapshot::new));

    private static BoardSnapshot empty(Status status) {
        return new BoardSnapshot(status, "UTC", List.of(), List.of());
    }
}
