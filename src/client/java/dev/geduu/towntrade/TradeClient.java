package dev.geduu.towntrade;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.Minecraft;

public final class TradeClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        BlockEntityRendererRegistry.register(TradeBoard.ENTITY_TYPE, TradeBoardRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(TradeWire.OpenPacket.TYPE, (packet, context) -> {
            if (isReplay(context.client())) return;
            var data = TradeConfig.JSON.fromJson(packet.data(), TradeWire.ScreenOpen.class);
            context.client().execute(() -> context.client().setScreen(switch (data.view()) {
                case PERMIT -> new PermitScreen(data);
                case INFO -> new BalanceScreen(data);
                case ENCHANT -> new EnchantScreen(data);
                default -> new TradeScreen(data);
            }));
        });
        ClientPlayNetworking.registerGlobalReceiver(TradeWire.UpdatePacket.TYPE, (packet, context) -> {
            if (isReplay(context.client())) return;
            var data = TradeConfig.JSON.fromJson(packet.data(), TradeWire.Update.class);
            context.client().execute(() -> {
                if (context.client().screen instanceof TradeScreen screen) screen.update(data);
                else if (context.client().screen instanceof ServiceScreen screen) screen.update(data);
                else if (context.client().screen instanceof BalanceScreen screen) screen.update(data);
            });
        });
    }

    static void send(TradeWire.Request request) {
        if (!isReplay(Minecraft.getInstance()) && ClientPlayNetworking.canSend(TradeWire.ActionPacket.TYPE)) {
            ClientPlayNetworking.send(new TradeWire.ActionPacket(TradeConfig.JSON.toJson(request)));
        }
    }

    static boolean isReplay(Minecraft client) {
        return TownTrade.isReplayServer(client.getSingleplayerServer());
    }
}
