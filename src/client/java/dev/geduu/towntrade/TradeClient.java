package dev.geduu.towntrade;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class TradeClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(TradeWire.OpenPacket.TYPE, (packet, context) -> {
            var data = TradeConfig.JSON.fromJson(packet.data(), TradeWire.ScreenOpen.class);
            context.client().execute(() -> context.client().setScreen(switch (data.view()) {
                case PERMIT -> new PermitScreen(data);
                case INFO -> new BalanceScreen(data);
                case ENCHANT -> new EnchantScreen(data);
                default -> new TradeScreen(data);
            }));
        });
        ClientPlayNetworking.registerGlobalReceiver(TradeWire.UpdatePacket.TYPE, (packet, context) -> {
            var data = TradeConfig.JSON.fromJson(packet.data(), TradeWire.Update.class);
            context.client().execute(() -> {
                if (context.client().screen instanceof TradeScreen screen) screen.update(data);
                else if (context.client().screen instanceof ServiceScreen screen) screen.update(data);
                else if (context.client().screen instanceof BalanceScreen screen) screen.update(data);
            });
        });
    }

    static void send(TradeWire.Request request) {
        if (ClientPlayNetworking.canSend(TradeWire.ActionPacket.TYPE)) {
            ClientPlayNetworking.send(new TradeWire.ActionPacket(TradeConfig.JSON.toJson(request)));
        }
    }
}
